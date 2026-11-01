# Cheatsheet — learn the stack, then clean it up

`export KUBECONFIG="$HOME/.kube/k3s.yaml"` for every command below.

## Part 1 — Learn the architecture with commands

Work top-down: cluster → workload → network → config → storage → data.

### 1. Cluster and node

```bash
kubectl cluster-info                      # who is the API server
kubectl config current-context            # default (k3s)
kubectl get nodes -o wide                 # node, kernel, container runtime
kubectl describe node jose-fedora         # capacity, allocatable, taints
kubectl top node                          # CPU/RAM usage (metrics-server)
kubectl get --raw /readyz                 # is the API healthy
```

GPU:

```bash
kubectl get node -o jsonpath='{.items[0].status.allocatable.nvidia\.com/gpu}{"\n"}'
kubectl -n kube-system get pods -l name=nvidia-device-plugin-ds
kubectl -n kube-system logs ds/nvidia-device-plugin-daemonset --tail=20
```

### 2. Workloads in the app namespace

```bash
kubectl -n pfa get all                    # pods/services/deployments/statefulsets
kubectl -n pfa get deploy,sts,ds -o wide
kubectl -n pfa get pods -o wide --sort-by=.spec.nodeName
kubectl -n pfa describe deploy/query-service     # strategy, env, probes, events
kubectl -n pfa get deploy/query-service -o yaml   # live object (the source of truth)
```

Watch a rollout:

```bash
kubectl -n pfa rollout status deploy/api-gateway
kubectl -n pfa rollout history deploy/api-gateway
kubectl -n pfa get pods -w            # Ctrl-C to stop
```

### 3. Services and DNS

```bash
kubectl -n pfa get svc                    # ClusterIPs
kubectl -n pfa get endpointslices         # which pod backs each service
kubectl -n pfa run dnsutils --rm -it --image=busybox:1.36 --restart=Never -- \
  nslookup command-service.pfa.svc.cluster.local
kubectl -n pfa run curl --rm -it --image=curlimages/curl --restart=Never -- \
  curl -s http://api-gateway:8080/actuator/health
```

The names match docker-compose on purpose: `postgres-primary`, `postgres-replica`,
`redis`, `rabbitmq`, `minio`, `clickhouse`, `prometheus`, `grafana`, `vector`,
`auth-service`, `api-gateway`, `command-service`, `query-service`, `ocr-service`, `frontend`.

### 4. Ingress and the public tunnel

```bash
kubectl -n pfa get ingress -o wide
kubectl get ingressclass
curl -s -H 'Host: finance.local' http://127.0.0.1/api/auth/health
kubectl -n kube-system logs deploy/traefik --tail=30
kubectl -n pfa logs deploy/cloudflared --tail=30   # shows the route config
```

### 5. Config and secrets

```bash
kubectl -n pfa get configmap
kubectl -n pfa get secret
# secrets are committed encrypted in k8s/sealed/ and decrypted by the controller
kubectl -n pfa get sealedsecrets
kubectl -n pfa describe sealedsecret auth-service-secrets | sed -n '/Status:/,$p'
# one secret per workload group: postgres-secrets, redis-secrets, minio-secrets, ...
kubectl -n pfa get secret auth-service-secrets -o jsonpath='{.data}' | python3 -m json.tool | head
# decode one key (example: DB_USER)
kubectl -n pfa get secret auth-service-secrets -o jsonpath='{.data.DB_USER}' | base64 -d; echo
# re-seal after changing .env, then commit the regenerated files
./k8s/seal-secrets.sh --apply
```

### 6. Storage

```bash
kubectl get storageclass                  # local-path (k3s default)
kubectl -n pfa get pvc
kubectl -n pfa describe pvc data-postgres-primary-0
kubectl -n pfa get pv
```

PVC data lives on the host at `/var/lib/rancher/k3s/storage/` (root-owned).

### 7. Logs and debugging

```bash
kubectl -n pfa logs deploy/query-service --tail=100
kubectl -n pfa logs -f deploy/query-service
kubectl -n pfa logs <pod> --previous              # after a crash
kubectl -n pfa get events --sort-by=.lastTimestamp | tail -30
kubectl -n pfa describe pod <pod>                 # last state, probes, events
kubectl -n pfa exec -it deploy/api-gateway -- sh
kubectl -n pfa debug <pod> --image=busybox:1.36 -it -- sh   # ephemeral container
kubectl -n pfa top pods
```

Observability stack:

```bash
kubectl -n pfa port-forward svc/prometheus 9090:9090 &
curl -s localhost:9090/api/v1/targets | python3 -m json.tool | grep -E 'job|health'
kubectl -n pfa port-forward svc/grafana 3000:3000 &     # admin / GRAFANA_ADMIN_PASSWORD
kubectl -n pfa exec clickhouse-0 -- clickhouse-client --password "$CH_PW" \
  -q 'select service, level, count() from default.logs group by service, level order by 3 desc limit 10'
```

### 8. Data (Postgres, Redis, RabbitMQ, MinIO)

```bash
DB_USER=$(sed -n 's/^DB_USER=//p' .env); DB_NAME=$(sed -n 's/^DB_NAME=//p' .env)
kubectl -n pfa exec postgres-primary-0 -- psql -U "$DB_USER" -d "$DB_NAME" -c '\dt *.*'
kubectl -n pfa exec postgres-primary-0 -- psql -U "$DB_USER" -d "$DB_NAME" \
  -c 'select client_addr, state, sync_state from pg_stat_replication;'
kubectl -n pfa exec postgres-replica-0 -- psql -U "$DB_USER" -d "$DB_NAME" -c 'select count(*) from finance.transactions;'
kubectl -n pfa exec redis-0 -- redis-cli -a "$(sed -n 's/^REDIS_PASSWORD=//p' .env)" --no-auth-warning info keyspace
kubectl -n pfa exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
```

### 9. OCR / RabbitMQ job flow

```bash
kubectl -n pfa logs deploy/ocr-service | grep -E 'OCR job|Bill parsing|RabbitMQ'
kubectl -n pfa exec deploy/ocr-service -- nvidia-smi
kubectl -n pfa exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
```

Flow: `POST /api/bill/process/{userId}` → MinIO `receipts` bucket → RabbitMQ
`finance-events` / `ocr.job` → queue `ocr-jobs` → OCR → `POST
/internal/ocr-complete` on command-service → Postgres + SSE.

### 10. Discover more without memorising

```bash
kubectl api-resources                     # everything the cluster knows
kubectl explain deployment.spec.strategy  # built-in docs
kubectl get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.podIP}{"\n"}{end}'
kubectl diff -f k8s/apps/query-service.yaml
kubectl apply --dry-run=server -f k8s/apps/
```

### 11. CI and delivery (registry → runner → Argo CD)

```bash
# everything at once: cluster, pods, registry, buildkit, runner, Argo, CI runs
./k8s/ci/debug.sh

# the build path
kubectl -n pfa get pods -l app=ci-runner
kubectl -n pfa logs deploy/ci-runner --tail=40              # want "Listening for Jobs"
kubectl -n pfa exec deploy/buildkitd -- buildctl --addr tcp://127.0.0.1:1234 debug workers
curl -fsS http://127.0.0.1:30500/v2/_catalog | jq .       # images in the registry

# what is deployed vs what git says
kubectl -n pfa get deploy -o custom-columns='NAME:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'
kubectl -n argocd get applications
kubectl -n argocd get application pfa-apps -o jsonpath='{.status.sync.status} {.status.health.status}{"\n"}'

# Argo CD UI
kubectl -n argocd port-forward svc/argocd-server 8080:443   # https://localhost:8080
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d

# trigger a build for one service (ref must contain the workflow)
TOKEN=$(sed -n 's/^GITHUB_PAT=//p' .env)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.github+json' \
  https://api.github.com/repos/verginjose/Personal-Finance-Assistant/actions/workflows/build.yml/dispatches \
  -d '{"ref":"master","inputs":{"service":"frontend"}}'

# registry housekeeping (tag retention + offline GC)
./k8s/ci/registry-gc.sh --dry-run
./k8s/ci/registry-gc.sh
```

Delivery chain: `git push` → runner builds changed services with BuildKit →
pushes to `registry.pfa.svc:5000/finance/<svc>:<sha>` → commits the tag bump →
Argo CD syncs `k8s/apps` on `master`.

## Part 2 — Cleanup (least → most destructive)

### 0. Day-to-day: start and stop

```bash
./k8s/start.sh                  # k3s up, ready, UI port-forwards started, passwords hidden
./k8s/start.sh --secrets        # print only the passwords (incl. Argo CD), then exit
./k8s/start.sh --no-port-forward # skip the :3000/:8080/... forwards
./k8s/stop.sh                   # stop k3s cleanly, delete nothing
./k8s/stop.sh --gc              # prune old registry tags first
```

### A. Remove the app, keep the cluster

```bash
kubectl delete -f k8s/optional/cloudflared.yaml      # stop the tunnel (keeps it in Cloudflare)
kubectl delete namespace pfa                         # everything in the app
sudo rm -rf /var/lib/rancher/k3s/storage/*           # local-path PVC data
```

### B. Remove the local images (OCR is ~17 GB)

```bash
# images built by CI live in the registry PVC, not containerd — they go with
# `kubectl delete namespace pfa` (§A). These are the pre-CI imported ones:
sudo k3s ctr images ls | grep finance
sudo k3s ctr images rm docker.io/finance/ocr-service:latest
docker rmi finance/api-gateway finance/command-service finance/query-service \
           finance/frontend finance/ocr-service
rm -rf .pipeline                                     # pipeline artifacts
```

### B2. Remove the CI stack

```bash
kubectl delete namespace argocd                       # Argo CD (stops auto-sync)
kubectl -n pfa delete secret pfa-ci-secrets           # runner credentials
sudo rm -f /etc/rancher/k3s/registries.yaml           # insecure-registry config
# registry/buildkit/runner pods and their PVC data go with `namespace pfa`
```

### C. Undo host-level setup

```bash
sudo firewall-cmd --permanent --zone=trusted --remove-source=10.42.0.0/16
sudo firewall-cmd --permanent --zone=trusted --remove-source=10.43.0.0/16
sudo firewall-cmd --reload

sudo rm /etc/sysctl.d/99-k3s-inotify.conf
sudo sysctl --system

sudo sed -i '/127.0.0.1 finance.local/d' /etc/hosts
```

### D. Remove the cluster

```bash
sudo /usr/local/bin/k3s-uninstall.sh                 # stops k3s, removes /var/lib/rancher
sudo rm -f ~/.kube/k3s.yaml
kubectl config unset contexts.default 2>/dev/null || true
```

### E. One-shot cleanup script

`k8s/cleanup.sh` wraps the above with flags and confirmation:

```bash
./k8s/cleanup.sh --ci             # Argo CD, runner secret, registries.yaml
./k8s/cleanup.sh --app            # namespace pfa (registry, buildkit, runner) + PVC data
./k8s/cleanup.sh --images         # containerd + Docker images, pipeline artifacts
./k8s/cleanup.sh --host           # firewall, sysctl, /etc/hosts
./k8s/cleanup.sh --cluster        # uninstall k3s entirely
./k8s/cleanup.sh --all            # everything, asks for confirmation
```
