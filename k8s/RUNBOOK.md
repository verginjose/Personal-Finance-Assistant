# Runbook — deploy, access, debug

Everything below assumes:

```bash
export KUBECONFIG="$HOME/.kube/k3s.yaml"
cd ~/IdeaProjects/Personal-Finance-Assistant
```

## URLs

| What | URL |
|---|---|
| Public app (Cloudflare tunnel) | **https://verginjose.cloud** |
| Local ingress (Traefik, needs hosts entry) | http://finance.local |
| Local via port-forward | http://localhost:3001 |
| Grafana (port-forward) | http://localhost:3000 |
| Prometheus (port-forward) | http://localhost:9090 |

Add the local hostname once:

```bash
echo "127.0.0.1 finance.local" | sudo tee -a /etc/hosts
```

The tunnel deployment (`cloudflared`) uses the remote config from the Cloudflare
dashboard, which currently routes `verginjose.cloud → http://frontend:80`.

## Deploy / update

```bash
# full local pipeline: preflight -> build -> push -> render -> deploy -> verify
./k8s/pipeline.sh

# reuse existing images (fast path after a config-only change)
./k8s/pipeline.sh --skip-build

# build + validate manifests, do not touch the cluster
./k8s/pipeline.sh --skip-deploy

# publish images while deploying (needs `docker login`)
PUSH_REGISTRY=ghcr.io/you/pfa ./k8s/pipeline.sh

# plain deploy, no build
./k8s/apply.sh --with-cloudflared
```

Artifacts from every pipeline run land in `.pipeline/<run-id>/`
(`preflight.txt`, `rendered/`, `verify.txt`, `pipeline.log`).

Redeploy one service after a code change:

```bash
docker build -t finance/query-service:latest query-service
docker save finance/query-service:latest -o /tmp/q.tar
sudo k3s ctr images import /tmp/q.tar && rm /tmp/q.tar
kubectl -n pfa rollout restart deploy/query-service
kubectl -n pfa rollout status  deploy/query-service
```

## Health

```bash
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
| image not found / `ErrImagePull` | image not imported into containerd | `docker save … -o /tmp/i.tar && sudo k3s ctr images import /tmp/i.tar` |

## Reset

```bash
# delete the whole app namespace (keeps the cluster)
kubectl delete namespace pfa

# delete the cluster (keeps no data)
sudo /usr/local/bin/k3s-uninstall.sh
```
