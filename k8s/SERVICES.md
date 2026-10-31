# Services and pods — what each one is, and how to debug it

One page per workload: what it does, where it listens, what it needs, how to
check it, and the failure modes you will actually hit. For day-to-day
operations see [`RUNBOOK.md`](RUNBOOK.md); for the delivery pipeline see
[`README.md`](README.md).

```bash
export KUBECONFIG="$HOME/.kube/k3s.yaml"
cd ~/IdeaProjects/Personal-Finance-Assistant
```

Every Kubernetes `Service` has the same name as the Compose service, so the
in-cluster DNS names are `api-gateway`, `postgres-primary`, `rabbitmq`, … A
Service is only a DNS name + a port; the pods behind it are what you debug.

---

## How a request flows

```
browser
  └─ nginx (frontend)            /  → static files,  /api/ → api-gateway:8080
       └─ api-gateway            JWT check, rate limit, route
            ├─ /api/auth/**      → auth-service:8082      → postgres-primary, redis
            ├─ /api/upsert/**    → command-service:8081   → postgres-primary, redis, rabbitmq
            ├─ /api/analytics/** → query-service:8084     → postgres-replica, redis
            └─ /api/storage/**   → minio:9000
upload path:
  api-gateway ──PUT──▶ minio:9000
              └─publish "ocr.job"──▶ rabbitmq ──▶ ocr-service:8100
                                                    ├─ reads the object from MinIO
                                                    ├─ PaddleOCR + Groq
                                                    └─ POST results ──▶ command-service
                                                                          └─ SSE ──▶ browser
```

---

## Application services

| Workload | Image | Port | Replicas | Health | Talks to |
|---|---|---|---|---|---|
| `frontend` | nginx + static JS | 80 | 1 | `GET /` | api-gateway |
| `api-gateway` | `finance/api-gateway` | 8080 | 2 | `/actuator/health/*` | auth, command, query, minio, redis |
| `auth-service` | `finance/auth-service` | 8082 | 2 | `/actuator/health/*` | postgres-primary, redis |
| `command-service` | `finance/command-service` | 8081 | 1 | `/actuator/health/*` | postgres-primary, redis, rabbitmq |
| `query-service` | `finance/query-service` | 8084 | 1 | `/actuator/health/*` | postgres-replica, redis |
| `ocr-service` | `finance/ocr-service` | 8100 | 1 | `GET /health` | rabbitmq, minio, command-service, GPU |

### `frontend`

Nginx serving the vanilla-JS SPA and proxying `/api/` to the gateway. It is the
only pod the Cloudflare tunnel points at.

```bash
kubectl -n pfa logs deploy/frontend --tail=50
kubectl -n pfa exec deploy/frontend -- cat /etc/nginx/conf.d/default.conf
curl -H 'Host: finance.local' http://127.0.0.1/
```

Failures: `502` on `/api/*` means nginx cannot reach `api-gateway` (gateway
down or not Ready). A stale page after a deploy means the browser cached
`index.html`; the config sends `no-store`, so check the Cloudflare cache.

### `api-gateway`

The edge proxy. Routing, rate limiting (Redis token bucket), circuit breakers,
JWT validation + blacklist check, `X-User-Id` injection, and the three upload
endpoints (`/api/bill`, `/api/receipt`, `/api/profile`). It deliberately owns
**no** user data and no auth tables.

Key env: `JWT_SECRET`, `REDIS_HOST`, `RABBITMQ_*`, `MINIO_*`.
Key config: `api-gateway/src/main/resources/application.yaml` (routes).

```bash
kubectl -n pfa logs deploy/api-gateway --tail=100
kubectl -n pfa exec deploy/api-gateway -- wget -qO- localhost:8080/actuator/health
# which image is running?
kubectl -n pfa get deploy api-gateway -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
# is the auth route healthy?
curl -s -H 'Host: finance.local' http://127.0.0.1/api/auth/health
```

Failures: `503 "Authentication service is temporarily unavailable"` = the
circuit breaker is open because `auth-service` is down; `401` on every request =
`JWT_SECRET` differs between the gateway and auth-service; uploads returning
`500` are almost always MinIO credentials or the `receipts` bucket.

### `auth-service`

Registration, login, refresh-token rotation, logout/blacklisting, password
change, profile and user search. Owns the `auth.users` table
(`db-init/007_create_auth_users.sql`).

Key env: `SPRING_R2DBC_URL` (schema `auth`), `JWT_SECRET` (must equal the
gateway's), `REDIS_HOST`, `REDIS_PASSWORD`, `REDIS_SERVER_PORT` (named that
way on purpose — Kubernetes injects a bogus `REDIS_PORT` for the `redis`
Service). Redis keys: `refresh_token:*`, `user_tokens:*`,
`token_blacklist:*`.

```bash
kubectl -n pfa logs deploy/auth-service --tail=100
kubectl -n pfa exec deploy/auth-service -- wget -qO- localhost:8082/actuator/health
kubectl -n pfa exec postgres-primary-0 -- psql -U "$DB_USER" -d "$DB_NAME" \
  -c 'select count(*), max(id) from auth.users;'          # DB_USER/DB_NAME from .env
kubectl -n pfa exec redis-0 -- redis-cli -a "$REDIS_PW" --no-auth-warning keys 'refresh_token:*'
```

Failures: `401` on login = wrong password or a missing/blank `password` column
row; `500` on login = `auth.users` missing columns (rerun
`db-init/007_create_auth_users.sql`); a user logged out on one browser still
appears logged in elsewhere until the access token expires (blacklist only
covers the presented token).

### `command-service`

All writes and business logic: transactions, budgets, goals, subscriptions,
group splits. Publishes cache-eviction events and the SSE stream the frontend
subscribes to (`/api/upsert/notifications/**`).

```bash
kubectl -n pfa logs deploy/command-service --tail=100
kubectl -n pfa exec deploy/command-service -- wget -qO- localhost:8081/actuator/health
kubectl -n pfa exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
```

Failures: writes fail while reads work → check `postgres-primary` (write path);
`ddl-auto=update` means a bad entity change can alter the schema on boot — if
the service crash-loops after a deploy, check `logs --previous`.

### `query-service`

Read model: analytics, aggregations, insights. Reads the Postgres **replica**,
caches in Redis, calls the Groq LLM.

```bash
kubectl -n pfa logs deploy/query-service --tail=100
kubectl -n pfa exec deploy/query-service -- wget -qO- localhost:8084/actuator/health
# stale numbers after a write?
kubectl -n pfa exec redis-0 -- redis-cli -a "$REDIS_PW" --no-auth-warning dbsize
```

Failures: data lags by a few seconds after a write — that is replication.
Analytics empty while the DB has rows → check replica lag
(`pg_stat_replication` in the RUNBOOK).

### `ocr-service`

Python/FastAPI worker. Consumes `ocr-jobs` from RabbitMQ, reads the file from
MinIO, runs PaddleOCR on the GPU (CUDA), parses with Groq, POSTs the result to
`command-service`. Uses the `paddle-model-cache` PVC at `/root/.paddleocr`,
which is why its rollout strategy is `Recreate` and why it is slow to start the
first time and fast afterwards.

```bash
kubectl -n pfa logs deploy/ocr-service --tail=100 | grep -v '%|'
kubectl -n pfa exec deploy/ocr-service -- wget -qO- localhost:8100/health
kubectl -n pfa exec deploy/ocr-service -- nvidia-smi
kubectl -n pfa get pod -l app=ocr-service -o jsonpath='{.items[0].spec.containers[0].resources}{"\n"}'
```

Failures: pod `Pending` = no `nvidia.com/gpu` allocatable (device plugin);
jobs never consumed = queue consumer count 0 or RabbitMQ unreachable; parse
errors = `GROQ_*` credentials; slow first start = model download into the PVC.

---

## Data stores

| Workload | Image | Port | Storage | Notes |
|---|---|---|---|---|
| `postgres-primary` | `ankane/pgvector:0.5.1` | 5432 | `data-postgres-primary` | writes; schemas `auth`, `finance`, `groups`, `activity` |
| `postgres-replica` | same | 5432 | `data-postgres-replica` | streaming replica; read-only |
| `redis` | `redis:7-alpine` | 6379 | PVC | tokens, rate limits, query cache |
| `redis-replica` | `redis:7-alpine` | 6379 | PVC | replica of `redis` |
| `rabbitmq` | `rabbitmq:3-management-alpine` | 5672 / 15672 | PVC | exchange `finance-events`, queue `ocr-jobs` |
| `minio` | `pgsty/minio` | 9000 / 9001 | PVC | buckets `receipts`, `profiles` |
| `clickhouse` | `clickhouse:24.8` | 8123 / 9000 | PVC | log analytics (`observability/clickhouse`) |

```bash
kubectl -n pfa exec postgres-primary-0 -- psql -U "$DB_USER" -d "$DB_NAME" -c '\dn'
kubectl -n pfa exec postgres-primary-0 -- psql -U "$DB_USER" -d "$DB_NAME" \
  -c 'select client_addr, state, sync_state from pg_stat_replication;'
kubectl -n pfa exec redis-0 -- redis-cli -a "$REDIS_PW" --no-auth-warning info keyspace
kubectl -n pfa exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
kubectl -n pfa exec minio-0 -- ls /data/receipts | head
kubectl -n pfa exec clickhouse-0 -- clickhouse-client --password "$CH_PW" -q 'select count() from default.logs'
```

These are StatefulSets. Restarting one is safe (`kubectl -n pfa rollout
restart statefulset/postgres-primary`); deleting its PVC is not. See
[`START-STOP.md`](START-STOP.md) for what is on disk.

---

## Observability, delivery and plumbing

| Workload | Namespace | Port | What it does |
|---|---|---|---|
| `prometheus` | pfa | 9090 | scrapes `/actuator/prometheus` + kubelet cAdvisor (pod metrics), 2Gi PVC |
| `grafana` | pfa | 3000 | dashboards over Prometheus + ClickHouse |
| `vector` | pfa | 9090 / 8686 | reads kubelet log files, ships JSON logs to ClickHouse |
| `cloudflared` | pfa | — | tunnel `verginjose.cloud` → `frontend:80` |
| `registry` | pfa | 5000 / NodePort 30500 | in-cluster image registry (50Gi PVC) |
| `buildkitd` | pfa | 1234 | rootless image builder used by CI (40Gi cache PVC) |
| `ci-runner` | pfa | — | self-hosted GitHub Actions agent |
| `argocd-*` | argocd | 443 | GitOps controller watching `k8s/apps` on `master` |

```bash
# metrics: is a scrape target up?
kubectl -n pfa port-forward svc/prometheus 9090:9090 &
curl -s localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job) \(.health)"'
# logs pipeline
kubectl -n pfa logs deploy/vector --tail=50
kubectl -n pfa logs deploy/cloudflared --tail=30
# CI
kubectl -n pfa logs deploy/ci-runner --tail=40
kubectl -n pfa exec deploy/buildkitd -- buildctl --addr tcp://127.0.0.1:1234 debug workers
# delivery state
kubectl -n argocd get applications
```

---

## Generic debugging recipes

```bash
# why is this pod not starting?  (events are at the bottom)
kubectl -n pfa describe pod <pod>
kubectl -n pfa get events --sort-by=.lastTimestamp | tail -20

# it restarted — what did it say before dying?
kubectl -n pfa logs <pod> --previous --tail=200

# follow logs across a rollout
kubectl -n pfa logs -f deploy/api-gateway

# who is using memory / CPU?
kubectl -n pfa top pods --sort-by=memory
kubectl -n pfa top node

# which image is really deployed?
kubectl -n pfa get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'

# run a shell next to the code
kubectl -n pfa exec -it deploy/api-gateway -- sh
```

| Symptom | First thing to check | Likely cause |
|---|---|---|
| `ImagePullBackOff` on `192.168.0.7:30500/...` | `curl http://192.168.0.7:30500/v2/` | registry pod down, node IP changed, or `registries.yaml` lost (`k3s/ci/setup-registry.sh`) |
| `CrashLoopBackOff` | `logs --previous` | bad config/secret, DB unreachable, port already in use |
| `Pending` | `describe pod` → events | not enough memory/CPU, or GPU not allocatable (OCR) |
| `OOMKilled` (exit 137) | `kubectl describe pod` | JVM heap + pod limit too tight; compare `kubectl top pod` |
| `502` from the ingress / tunnel | `kubectl -n pfa get endpoints` | Service has no Ready pods |
| `401` everywhere | compare `JWT_SECRET` in `pfa-secrets` with both pods | gateway and auth-service disagree on the key |
| `413` on upload | `frontend/nginx.conf` `client_max_body_size` | file bigger than 12 MB |
| Argo `OutOfSync` but pods are fine | `kubectl -n argocd get app pfa-apps -o yaml` | Argo has not polled yet (≤3 min) or the repo credential expired |
