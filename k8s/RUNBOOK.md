# Runbook — deploy, access, debug

Everything below assumes:

```bash
export KUBECONFIG="$HOME/.kube/k3s.yaml"
cd ~/IdeaProjects/Personal-Finance-Assistant
```

Per-service reference (what a pod does, its port, config, logs, failure
modes): [`SERVICES.md`](SERVICES.md).

## URLs

| What | URL |
|---|---|
| Public app (Cloudflare tunnel) | **https://verginjose.cloud** |
| Local ingress (Traefik, needs hosts entry) | http://finance.local |
| Local via port-forward | http://localhost:3001 |
| Grafana (port-forward) | http://localhost:3000 |
| Prometheus (port-forward) | http://localhost:9090 |
| Argo CD UI (port-forward) | https://localhost:8080 |
| Grafana (public, Cloudflare Access) | https://grafana.verginjose.cloud |
| Argo CD UI (public, Cloudflare Access) | https://argocd.verginjose.cloud |
| Image registry (in-cluster) | http://127.0.0.1:30500 (`/v2/_catalog`) |
| CI runs | github.com/verginjose/Personal-Finance-Assistant/actions |

Add the local hostname once:

```bash
echo "127.0.0.1 finance.local" | sudo tee -a /etc/hosts
```

The tunnel deployment (`cloudflared`) uses the remote config from the Cloudflare
dashboard, which currently routes `verginjose.cloud → http://frontend:80`.

## Operator UIs

Grafana and Argo CD are also published under the same domain, each on its own
hostname because both assume they own the root path (Argo CD needs `--rootpath`
otherwise and breaks its gRPC streams behind a path prefix):

| Host | Backend | Defined in |
|---|---|---|
| `grafana.verginjose.cloud` | `pfa/grafana:3000` | `k8s/edge/ui-access.yaml` (applied by `apply.sh`) |
| `argocd.verginjose.cloud` | `argocd/argocd-server:80` | `argocd/ingress.yaml` (applied by `expose.sh`) |

Expose Argo CD:

```bash
./argocd/expose.sh
```

Then, **in this order**, in the Cloudflare dashboard:

1. **Zero Trust → Access → Applications** — add both hostnames to one
   self-hosted app with an email/SSO policy. Nothing else authenticates these
   UIs; the policy *is* the login.
2. **Networks → Tunnels → your tunnel → Public hostnames** — point both
   hostnames at `http://traefik.kube-system.svc.cluster.local:80`.

Reversing it: delete the two Ingress objects and the tunnel hostnames, and
`kubectl -n argocd patch cm argocd-cmd-params-cm --type merge -p '{"data":{"server.insecure":null}}'`.

Argo CD itself keeps its own login (the `admin` password from
`argocd-initial-admin-secret`) behind the Access policy — that is intentional
belt-and-braces, not a misconfiguration. The `argocd` CLI still needs the
port-forward, since Access blocks non-browser clients.

## Deploy / update

**Normal path — push code, nothing else:**

```bash
git push            # CI builds changed services → pushes to registry → commits tag
                    # → Argo CD (poll ≤3 min) applies k8s/apps on master
```

The runner builds only the service directories whose files changed. The OCR
image (~10 GB) is only rebuilt when `ocr-service/**` changes.

| Piece | Namespace | What it does |
|---|---|---|
| `ci-runner` | pfa | Self-hosted GitHub Actions agent |
| `buildkitd` | pfa | Rootless BuildKit, 40 Gi cache PVC |
| `registry` | pfa | `registry:2`, NodePort 30500, 50 Gi PVC |
| `argocd-*` | argocd | Watches `k8s/apps` on `master`, auto-syncs |

Trigger a build for one service (or all) without changing code:

```bash
TOKEN=$(sed -n 's/^GITHUB_PAT=//p' .env)
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  https://api.github.com/repos/verginjose/Personal-Finance-Assistant/actions/workflows/build.yml/dispatches \
  -d '{"ref":"master","inputs":{"service":"all"}}'
```

`service` is one of `api-gateway`, `command-service`, `query-service`,
`auth-service`, `frontend`, `ocr-service`, or `all`.

**Manual fallback (no CI) — offline rebuild of one service:**

```bash
docker build -t finance/query-service:latest query-service
docker save finance/query-service:latest -o /tmp/q.tar
sudo k3s ctr images import /tmp/q.tar && rm /tmp/q.tar
kubectl -n pfa rollout restart deploy/query-service
kubectl -n pfa rollout status  deploy/query-service
```

Note Argo CD's `selfHeal` will revert a manual image change on the next sync;
commit the tag in `k8s/apps/` if the change should stick.

**Infrastructure** (Postgres, RabbitMQ, ClickHouse, observability) is *not*
managed by Argo — it still comes from:

```bash
./k8s/apply.sh --with-cloudflared
./k8s/pipeline.sh                 # legacy full manual pipeline (offline fallback)
```

Artifacts from every `pipeline.sh` run land in `.pipeline/<run-id>/`
(`preflight.txt`, `rendered/`, `verify.txt`, `pipeline.log`).

## Health

```bash
./k8s/start.sh             # start, wait, forward the UIs, print URLs (passwords: ./k8s/start.sh --secrets)
./k8s/stop.sh              # stop k3s cleanly (deletes nothing)
# full shutdown/restart verification (baseline → stop → start → compare data):
#   see START-STOP.md
# one-shot report: cluster, workloads, registry, buildkit, runner, Argo, CI runs
./k8s/ci/debug.sh          # non-zero exit if anything is unhealthy

kubectl -n pfa get pods -o wide
kubectl -n pfa get pvc
kubectl get node -o jsonpath='{.items[0].status.allocatable.nvidia\.com/gpu}{"\n"}'
curl -s -H 'Host: finance.local' http://127.0.0.1/api/auth/health
curl -s -o /dev/null -w '%{http_code}\n' https://verginjose.cloud/
```

## Debug

```bash
# events, newest first
kubectl -n pfa get events --sort-by=.lastTimestamp | tail -30

# why is a pod stuck?
kubectl -n pfa describe pod <pod>
kubectl -n pfa logs <pod> --previous --tail=100

# follow logs
kubectl -n pfa logs -f deploy/query-service
kubectl -n pfa logs -f deploy/ocr-service | grep -v '%|'

# run a shell in a pod
kubectl -n pfa exec -it deploy/api-gateway -- sh
kubectl -n pfa exec -it postgres-primary-0 -- psql -U "$(sed -n 's/^DB_USER=//p' .env)" -d "$(sed -n 's/^DB_NAME=//p' .env)"

# resource use
kubectl -n pfa top pods
kubectl top node

# restart / rollback
kubectl -n pfa rollout restart deploy/query-service
kubectl -n pfa rollout undo deploy/query-service
```

## Debug: CI and Argo CD

```bash
# app health, one line each
kubectl -n pfa exec deploy/api-gateway -- wget -qO- localhost:8080/actuator/health
kubectl -n pfa exec deploy/auth-service -- wget -qO- localhost:8082/actuator/health

# is the runner online?  (want: "Listening for Jobs")
kubectl -n pfa logs deploy/ci-runner --tail=40
kubectl -n pfa get pods -l app=ci-runner

# is BuildKit able to build?
kubectl -n pfa exec deploy/buildkitd -- buildctl --addr tcp://127.0.0.1:1234 debug workers

# registry reachable and populated?
curl -fsS http://127.0.0.1:30500/v2/ && echo
curl -fsS http://127.0.0.1:30500/v2/_catalog | jq .
kubectl -n pfa exec deploy/registry -- du -sh /var/lib/registry

# what does Argo think?
kubectl -n argocd get applications
kubectl -n argocd get application pfa-apps -o yaml | \
  yq '.status.sync, .status.health, .status.operationState.message' 2>/dev/null || \
  kubectl -n argocd get application pfa-apps -o jsonpath='{.status.sync.status} {.status.health.status}{"\n"}'

# force a sync (after committing a manifest change)
kubectl -n argocd patch application pfa-apps --type merge \
  -p '{"operation":{"initiatedBy":{"username":"manual"},"sync":{"revision":"master"}}}'

# why did a build fail?  (Actions → run → job → "Build and push")
#   or from the CLI:
TOKEN=$(sed -n 's/^GITHUB_PAT=//p' .env)
curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/verginjose/Personal-Finance-Assistant/actions/runs?per_page=5" \
  | jq -r '.workflow_runs[] | "\(.id) \(.status)/\(.conclusion) \(.head_branch)"'

# image on a pod is not what you expect?
kubectl -n pfa get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'
```

Common CI causes are in the failure table below. If the cluster was just
restarted and pods are `ImagePullBackOff` on `127.0.0.1:30500/...`, the
insecure-registry config is missing or k3s has not restarted since it was
written (`k8s/ci/setup-registry.sh`).

## Data queries

```bash
DB_USER=$(sed -n 's/^DB_USER=//p' .env); DB_NAME=$(sed -n 's/^DB_NAME=//p' .env)
REDIS_PW=$(sed -n 's/^REDIS_PASSWORD=//p' .env); CH_PW=$(sed -n 's/^CLICKHOUSE_PASSWORD=//p' .env)

# Postgres primary
kubectl -n pfa exec postgres-primary-0 -- \
  psql -U "$DB_USER" -d "$DB_NAME" -c '\dt *.*' | head -40
kubectl -n pfa exec postgres-primary-0 -- \
  psql -U "$DB_USER" -d "$DB_NAME" -c 'select count(*) from finance.transactions;'

# replication health (expect state=streaming)
kubectl -n pfa exec postgres-primary-0 -- \
  psql -U "$DB_USER" -d "$DB_NAME" -c 'select client_addr, state, sync_state from pg_stat_replication;'

# read replica (should match the primary)
kubectl -n pfa exec postgres-replica-0 -- \
  psql -U "$DB_USER" -d "$DB_NAME" -c 'select count(*) from finance.transactions;'

# Redis
kubectl -n pfa exec redis-0 -- redis-cli -a "$REDIS_PW" --no-auth-warning info keyspace
kubectl -n pfa exec redis-replica-<pod> -- redis-cli -a "$REDIS_PW" --no-auth-warning info replication

# RabbitMQ queues (expect ocr-jobs, consumers=1)
kubectl -n pfa exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers

# ClickHouse logs
kubectl -n pfa exec clickhouse-0 -- clickhouse-client --password "$CH_PW" \
  -q 'select service, level, count() from default.logs group by service, level order by count() desc limit 20'
kubectl -n pfa exec clickhouse-0 -- clickhouse-client --password "$CH_PW" \
  -q 'select timestamp, service, message from default.logs order by timestamp desc limit 20'

# MinIO objects
kubectl -n pfa exec minio-0 -- sh -c 'ls -la /data/receipts | head'

# Prometheus scrape health
kubectl -n pfa port-forward svc/prometheus 9090:9090 &
curl -s localhost:9090/api/v1/targets | python3 -m json.tool | grep -E 'job|health'

# Grafana
kubectl -n pfa port-forward svc/grafana 3000:3000 &
# user: admin / password: GRAFANA_ADMIN_PASSWORD in .env
```

## Grafana — dashboards and pod metrics

```bash
kubectl -n pfa port-forward svc/grafana 3000:3000 &
# http://localhost:3000 — user admin, password GRAFANA_ADMIN_PASSWORD in .env
```

| Dashboard | What it answers |
|---|---|
| **Pods — CPU, memory, network** | which pod is eating memory/CPU right now, network per pod, containers restarted in the last hour |
| **Service Metrics** | requests, 2xx/4xx/5xx, JVM heap and threads per service |
| **Database** | Postgres connections, size, replication |
| **API Latency** | p50/p95/p99 per route |
| **Finance Assistant** | business-level counters |

Dashboards are files in `observability/grafana/dashboards/`, mounted from the
`grafana-dashboards` ConfigMap. Edit the JSON, then re-render and restart:

```bash
kubectl create configmap grafana-dashboards -n pfa \
  --from-file=observability/grafana/dashboards --dry-run=client -o yaml \
  | kubectl apply -f -
kubectl -n pfa rollout restart deploy/grafana
```

> Note: anything you change *inside* the Grafana UI is lost on the next
> rollout for provisioned dashboards. Change the JSON file instead.

### Where pod metrics come from

Prometheus scrapes two things: each app's `/actuator/prometheus`, and the
kubelet's cAdvisor endpoint (`/metrics/cadvisor`). The second is the source of
every `container_*` metric. The scrape config is
`observability/prometheus/prometheus.yml` (rendered into the
`prometheus-config` ConfigMap by `./k8s/apply.sh`). If the *Pods* dashboard is
empty:

```bash
# 1. is the kubelet scrape target up?
kubectl -n pfa port-forward svc/prometheus 9090:9090 &
curl -s localhost:9090/api/v1/targets \
  | jq -r '.data.activeTargets[] | "\(.labels.job) \(.health) \(.lastError)"'
#    expect: kubelet-cadvisor up
# 2. do any container series exist?
curl -s 'localhost:9090/api/v1/query?query=count(container_memory_working_set_bytes)' | jq '.data.result'
# 3. did Prometheus pick up the config? (config maps are not auto-reloaded)
kubectl -n pfa rollout restart deploy/prometheus
```

The scrape needs RBAC (`ClusterRole pfa-prometheus`: `nodes/metrics`,
`nodes/proxy`) and the `prometheus` ServiceAccount — both live in
`k8s/infrastructure/prometheus.yaml`. Without them the target shows
`403 Forbidden` and there are no pod metrics at all.

### Useful queries (Prometheus or any dashboard panel)

```promql
# memory per pod in pfa
sum by (pod) (container_memory_working_set_bytes{namespace="pfa",container!="",container!="POD"})

# CPU cores per pod (compare with the pod's limit)
sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="pfa",container!="",container!="POD"}[5m]))

# containers restarted in the last hour
sum by (pod) (changes(container_start_time_seconds{namespace="pfa",container!=""}[1h]))

# network per pod
sum by (pod) (rate(container_network_receive_bytes_total{namespace="pfa"}[5m]))

# request rate and error ratio for the gateway
sum(rate(http_server_requests_seconds_count{job="api-gateway"}[5m]))
sum(rate(http_server_requests_seconds_count{job="api-gateway",status=~"5.."}[5m]))
```

Rule of thumb: `container_memory_working_set_bytes` vs the pod's `limits.memory`
in `k8s/apps/*.yaml` is what decides an `OOMKilled`. `kubectl -n pfa top pod`
gives the same numbers as a point-in-time snapshot.

## OCR / GPU

```bash
# is the job reaching the service?
kubectl -n pfa logs deploy/ocr-service --tail=50 | grep -E 'OCR job|Bill parsing'

# GPU visible inside the pod
kubectl -n pfa exec deploy/ocr-service -- nvidia-smi
kubectl -n pfa exec deploy/ocr-service -- nvidia-smi --query-gpu=memory.used,utilization.gpu --format=csv

# trigger a job (upload a bill; returns 202 and publishes to RabbitMQ)
TOKEN=$(curl -s -H 'Host: finance.local' -X POST http://127.0.0.1/api/auth/login \
  -H 'Content-Type: application/json' -d '{"email":"you@example.com","password":"..."}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s -H 'Host: finance.local' -H "Authorization: Bearer $TOKEN" \
  -F 'file=@requests/sample-documents/busTicket.jpg' \
  http://127.0.0.1/api/bill/process/<userId>

# device plugin
kubectl -n kube-system logs ds/nvidia-device-plugin-daemonset --tail=20
```

## Cloudflare tunnel

```bash
kubectl -n pfa logs deploy/cloudflared --tail=30
kubectl -n pfa rollout restart deploy/cloudflared
# the tunnel's ingress rules come from the Cloudflare dashboard (remote config)
```

Changing a secret (`CLOUDFLARE_TUNNEL_TOKEN`, DB password, …): edit `.env`, then

```bash
kubectl create secret generic pfa-secrets -n pfa --from-env-file=.env \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n pfa rollout restart deploy/api-gateway deploy/command-service \
  deploy/query-service deploy/ocr-service deploy/cloudflared
```

## Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `502 Bad Gateway` from ingress | firewalld drops pod-to-pod traffic | trust `10.42.0.0/16` + `10.43.0.0/16` (see README) |
| pod `Pending`, OCR never starts | no `nvidia.com/gpu` allocatable | check device plugin; `./k8s/apply.sh --skip-ocr` |
| `too many open files` in pods | inotify limit | `sysctl fs.inotify.max_user_instances=8192` (persisted in `/etc/sysctl.d/99-k3s-inotify.conf`) |
| k3s won't start after install | SELinux label `user_tmp_t` | `sudo restorecon -v /usr/local/bin/k3s` |
| `column users.password does not exist` | Hibernate created `auth.users` first | `db-init/007_create_auth_users.sql` |
| OCR parse fails with an API-key error | `GROQ_URL` / `GROQ_API_KEY` in `.env` | correct the credentials, restart `ocr-service` |
| image not found / `ErrImagePull` (legacy `finance/<svc>:latest`) | image not imported into containerd | `docker save … -o /tmp/i.tar && sudo k3s ctr images import /tmp/i.tar` |
| `ImagePullBackOff` on `127.0.0.1:30500/...` | containerd does not know the registry is HTTP | `sudo ./k8s/ci/setup-registry.sh`, confirm `curl http://127.0.0.1:30500/v2/` |
| runner shows Offline in GitHub | PAT expired or lacks Administration write | new PAT → `.env` → `./k8s/ci/apply.sh` |
| build fails with "Push rejected / non-fast-forward" | branch moved during the build | fixed by rebase-retry; if seen again, check for overlapping runs |
| build fails with `Resource not accessible by personal access token` | PAT lacks Actions write (only affects dispatching) | add **Actions: Read and write** to the token |
| Argo application `OutOfSync` / `Unknown` | repo credential or branch wrong | `kubectl -n argocd logs deploy/argocd-repo-server`; check `targetRevision` |
| Argo reverts a manual image change | `selfHeal: true` | change the image in `k8s/apps/<svc>.yaml` and let CI/Argo apply it |
| pod stuck `ContainerCreating` after k3s restart | containerd still starting | wait ~30 s; `kubectl -n pfa get events` |
| buildkitd crash-loops with permission errors | SELinux or PVC ownership | `kubectl -n pfa logs deploy/buildkitd`; the `fix-perms` initContainer should chown 1000 |

## Reset

```bash
# CI + Argo only: drops argocd, the runner secret, registries.yaml
./k8s/cleanup.sh --ci

# app namespace + all local-path volume data (registry, buildkit cache, DBs)
./k8s/cleanup.sh --app

# everything, including the k3s install
./k8s/cleanup.sh --all --yes

# ad hoc, without the script
kubectl delete namespace pfa
kubectl delete namespace argocd
sudo rm -f /etc/rancher/k3s/registries.yaml
sudo /usr/local/bin/k3s-uninstall.sh
```

`--ci` leaves the in-cluster images in the registry volume; `--app` is what
actually deletes them (the PVC lives in namespace pfa).
