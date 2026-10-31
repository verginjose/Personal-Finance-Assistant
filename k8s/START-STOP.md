# Start, stop, and prove nothing was lost

Everything on this cluster — databases, receipts, images, build cache, the
registry, Argo CD's state — lives on PVCs under
`/var/lib/rancher/k3s/storage`. Stopping k3s (or shutting the laptop down)
removes the **processes**, never the **data**. This document is the procedure to
verify that for yourself, and the routine to use before closing the laptop.

```bash
export KUBECONFIG="$HOME/.kube/k3s.yaml"
cd ~/IdeaProjects/Personal-Finance-Assistant
```

## TL;DR

```bash
./k8s/start.sh        # k3s up, pods ready, URLs + passwords printed
./k8s/stop.sh         # k3s down, nothing deleted
# just power off — that is also safe
```

`./k8s/cleanup.sh` is the **destructive** script. It is not part of this
procedure.

## 1. Record a baseline before you stop

```bash
DB_USER=$(sed -n 's/^DB_USER=//p' .env); DB_NAME=$(sed -n 's/^DB_NAME=//p' .env)

kubectl -n pfa exec postgres-primary-0 -- \
  psql -U "$DB_USER" -d "$DB_NAME" -c 'select count(*) as transactions from finance.transactions;'

kubectl -n pfa get pvc --no-headers | wc -l     # expect 11
kubectl -n pfa get pods --no-headers | wc -l    # expect 22
```

Write the count down. Optional but recommended — a real dump you can keep:

```bash
kubectl -n pfa exec postgres-primary-0 -- \
  pg_dump -U "$DB_USER" "$DB_NAME" > ~/pfa-backup-$(date +%F).sql
ls -lh ~/pfa-backup-*.sql
```

## 2. Verify the scripts (no side effects)

```bash
bash -n k8s/start.sh && bash -n k8s/stop.sh && echo "syntax ok"
./k8s/stop.sh --help
./k8s/start.sh --help
```

## 3. Stop

```bash
./k8s/stop.sh            # prompts for confirmation, then sudo password
./k8s/stop.sh --yes      # no confirmation prompt
```

Useful variants:

```bash
./k8s/stop.sh --cancel-runs   # cancel running/queued GitHub Actions builds first
./k8s/stop.sh --gc            # prune old registry tags before stopping
```

Confirm it is actually down:

```bash
systemctl is-active k3s     # expect: inactive
kubectl get nodes           # expect: connection refused — correct, the cluster is down
```

## 4. Start again

```bash
./k8s/start.sh
```

Expected output, in order:

1. `ok k3s is already running` (or it starts it)
2. `ok node Ready (jose-fedora)`
3. `ok 22 pods Running/Completed` — a cold start takes 1–3 minutes
4. `ok pfa-apps: Synced Healthy`
5. the access table and the credentials table

If it reports unhealthy pods, run `./k8s/ci/debug.sh` for the full picture.

## 5. Prove the data survived

```bash
# must match the number recorded in step 1
kubectl -n pfa exec postgres-primary-0 -- \
  psql -U "$DB_USER" -d "$DB_NAME" -c 'select count(*) as transactions from finance.transactions;'

# other stores
kubectl -n pfa exec minio-0 -- ls /data/receipts | head
kubectl -n pfa exec redis-0 -- redis-cli -a "$(sed -n 's/^REDIS_PASSWORD=//p' .env)" \
  --no-auth-warning dbsize

# the app itself
curl -s https://verginjose.cloud/ -o /dev/null -w '%{http_code}\n'   # expect 200
```

If the transaction count differs, stop and investigate before doing anything
else. (A `select count(*)` changing because the app wrote rows is normal; a
count that drops to 0 or the table missing is not.)

## What is preserved, and where

| Data | Location |
|---|---|
| Postgres / Redis / RabbitMQ / MinIO / ClickHouse | `/var/lib/rancher/k3s/storage/pvc-*_pfa_data-*` |
| Registry images | `/var/lib/rancher/k3s/storage/pvc-*_pfa_registry-data` |
| BuildKit layer cache | `/var/lib/rancher/k3s/storage/pvc-*_pfa_buildkit-cache` |
| Paddle OCR models | `/var/lib/rancher/k3s/storage/pvc-*_pfa_paddle-model-cache` |
| Desired state (Deployments, StatefulSets, Argo app) | `/var/lib/rancher/k3s/server/db` |
| Credentials | `.env` in the repo (gitignored) |

## Troubleshooting

| Symptom after restart | Cause | Fix |
|---|---|---|
| Pods `ImagePullBackOff` on `192.168.0.7:30500/...` | registry pod not ready yet, or the node IP changed | wait ~1 min; `curl -s http://192.168.0.7:30500/v2/` must return `{}`; if the IP changed, reserve it in the router and rerun `k8s/ci/setup-registry.sh` |
| `kubectl: connection refused` after `start.sh` | API server still booting | wait 30 s; `systemctl status k3s`; `journalctl -u k3s -n 50` |
| One StatefulSet won't start | its PVC is still bound but the pod is ordered behind another | `kubectl -n pfa get pods`; `kubectl -n pfa describe pod <pod>` |
| `pfa-apps` shows `OutOfSync` | Argo has not polled yet (≤3 min) or the repo credential expired | `kubectl -n argocd get app pfa-apps -o yaml`; refresh the PAT if it rotated |
| `start.sh` says namespace `pfa` missing | the app was deleted (`cleanup.sh --app`) | `./k8s/apply.sh --with-cloudflared` to redeploy |
| Builds queue forever in GitHub | runner offline (k3s stopped) | start the cluster; the runner reconnects automatically |

## What *does* delete data

```bash
./k8s/cleanup.sh --ci            # Argo CD, runner secret, registries.yaml — images kept
./k8s/cleanup.sh --app           # namespace pfa → ALL PVCs and databases
./k8s/cleanup.sh --images        # containerd/Docker images, .pipeline artifacts
./k8s/cleanup.sh --cluster       # uninstall k3s
./k8s/cleanup.sh --all --yes     # everything
```

Only `--app` / `--all` touch your finance data. Take the `pg_dump` from step 1
first if there is anything you would miss.
