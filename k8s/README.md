# Running Personal Finance Assistant on Kubernetes

This directory is an additive Kubernetes overlay for the existing
`docker-compose.yml`. Docker Compose keeps working; nothing here is referenced
from the services themselves.

```
k8s/
├── 00-namespace.yaml          # namespace: pfa
├── secrets.example.yaml       # template; apply.sh builds the real Secret from .env
├── ingress.yaml               # finance.local -> frontend
├── apply.sh                   # renders configmaps/secrets and deploys in order
├── build-images.sh            # builds images and imports them into k3s/kind
├── pipeline.sh                # local CI/CD: build -> push -> render -> deploy -> verify
├── setup-k3s-nvidia.sh        # one-time privileged k3s + GPU + firewalld setup
├── cleanup.sh                 # tear down app / images / host config / cluster
├── RUNBOOK.md                 # deploy/access/debug/query cheat sheet
├── CHEATSHEET.md              # learn the commands, then clean up step by step
├── infrastructure/            # stateful deps + observability
│   ├── postgres-primary.yaml
│   ├── postgres-replica.yaml
│   ├── redis.yaml             # master + replica
│   ├── rabbitmq.yaml
│   ├── minio.yaml
│   ├── clickhouse.yaml
│   ├── prometheus.yaml
│   ├── grafana.yaml
│   └── vector.yaml            # DaemonSet (kubelet log files, not the docker socket)
├── apps/
│   ├── api-gateway.yaml
│   ├── command-service.yaml
│   ├── query-service.yaml
│   ├── ocr-service.yaml       # requires an NVIDIA GPU
│   └── frontend.yaml
├── config/                    # templates rendered by apply.sh
└── optional/
    └── cloudflared.yaml
```

## Why this maps cleanly

The services already resolve each other by Compose service name, so plain
Kubernetes `Service` objects with the same names keep the configuration intact:

| Config entry | Compose DNS | Kubernetes Service |
|---|---|---|
| `spring.cloud.gateway.routes[].uri` | `command-service:8081`, `query-service:8084` | same |
| gateway `minio.endpoint` | `minio:9000` | same |
| `frontend/nginx.conf` `proxy_pass` | `api-gateway:8080` | same |
| `REDIS_HOST` / `SPRING_DATA_REDIS_HOST` | `redis` | same |
| `SPRING_DATASOURCE_URL` | `postgres-primary` / `postgres-replica` | same |
| OCR `RABBITMQ_URL`, `UPSERT_SERVICE_URL` | `rabbitmq`, `command-service` | same |

No application code or `application.yaml` needed changing.

## 1. Install a local cluster (k3s)

[k3s](https://k3s.io) is the recommended single-node option here: it ships
Traefik (used by `ingress.yaml`), a default `local-path` StorageClass, and
works with the NVIDIA device plugin.

The privileged setup is scripted. It installs k3s, fixes the SELinux label on
the binary, raises the inotify limits k3s needs, registers the NVIDIA containerd
runtime, writes `~/.kube/k3s.yaml`, and imports the locally built images:

```bash
sudo bash k8s/setup-k3s-nvidia.sh
export KUBECONFIG="$HOME/.kube/k3s.yaml"
kubectl get nodes
```

It is idempotent — rerun it after changing the `.env` or rebuilding images.
Two Fedora-specific things it handles, in case you install by hand:

- **SELinux label.** The installer moves the binary out of `/tmp`, so it keeps
  `user_tmp_t` and systemd fails with `status=203/EXEC`. Fix:
  `sudo restorecon -v /usr/local/bin/k3s` (or `chcon -t bin_t`).
- **inotify limits.** The default `fs.inotify.max_user_instances=128` is shared
  by k3s, kind and the IDE; pods then fail with `too many open files`. The
  script writes `/etc/sysctl.d/99-k3s-inotify.conf` (8192 instances).
- **firewalld.** Its default `FORWARD` policy drops pod-to-pod traffic, so
  Service ClusterIPs work but an ingress talking to a backend by pod IP returns
  `502 Bad Gateway`. The script trusts the k3s ranges
  (`firewall-cmd --permanent --zone=trusted --add-source=10.42.0.0/16`, likewise
  `10.43.0.0/16`, then `--reload`).

Note the script deliberately writes `~/.kube/k3s.yaml` rather than overwriting
`~/.kube/config`, so a kind cluster can coexist.

Alternative: [`kind`](https://kind.sigs.k8s.io). Create the cluster with port
mappings for the ingress (`kind create cluster --name pfa`), then use
`KIND_CLUSTER=pfa ./k8s/build-images.sh` below. Traefik is not included in kind;
install an ingress controller or use `port-forward`.

## 2. Enable the GPU for the OCR service (optional)

The OCR image is CUDA-based and the manifest requests `nvidia.com/gpu: 1`.

```bash
# 1. NVIDIA drivers + container toolkit on the host
sudo dnf install -y nvidia-container-toolkit

# 2. Make k3s' containerd aware of the NVIDIA runtime
#    (setup-k3s-nvidia.sh does this; manual steps below)
sudo nvidia-ctk runtime configure --runtime=containerd --set-as-default \
  --config=/var/lib/rancher/k3s/agent/etc/containerd/config.toml.tmpl
# With containerd config v3, nvidia-ctk writes the result to
# /etc/containerd/conf.d/99-nvidia.toml instead of editing that file, and k3s
# does not read conf.d. Install it as the k3s template:
sudo cp /etc/containerd/conf.d/99-nvidia.toml \
  /var/lib/rancher/k3s/agent/etc/containerd/config.toml.tmpl
sudo systemctl restart k3s

# 3. NVIDIA device plugin advertises the GPU to the scheduler
kubectl create -f https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/v0.17.0/deployments/static/nvidia-device-plugin.yml
kubectl get nodes -o jsonpath='{.items[0].status.allocatable}'
# expect: ... "nvidia.com/gpu":"1" ...
```

No GPU? `apply.sh` detects this: when no node advertises `nvidia.com/gpu` it
skips `apps/ocr-service.yaml` entirely (override with `--with-ocr` / `--skip-ocr`).
To run OCR on CPU anyway, also remove the `nvidia.com/gpu` limit from the
manifest and set `FLAGS_fraction_of_gpu_memory_to_use` to `0` — PaddlePaddle
then runs on CPU, significantly slower.

> **kind cannot pass the host GPU into the node container**, so a kind cluster
> always falls into the no-GPU path. Use k3s if you need OCR.

## 3. Build and import images

k3s does not use Docker's image store, so `build-images.sh` builds with Docker
and imports the tarballs into containerd.

```bash
./k8s/build-images.sh              # all five services
SKIP_OCR=1 ./k8s/build-images.sh   # skip the multi-GB CUDA image
```

The Spring Dockerfiles compile with Maven inside the image, so the first run is
slow (5–10 min per service). Rerun this script after any code change, then
restart the relevant Deployment with `kubectl -n pfa rollout restart deploy/<name>`.

If you prefer a registry, set `REGISTRY_PREFIX=ghcr.io/you`, push the images,
update the `image:` fields, and create an `imagePullSecret`.

## 4. Deploy

The script renders the Secret and the generated ConfigMaps from your existing
repo-level `.env`:

```bash
./k8s/apply.sh
```

It applies in dependency order and waits for each StatefulSet before starting
the apps. `--with-cloudflared` additionally starts the tunnel (see
`optional/cloudflared.yaml` for the Cloudflare-side route).

Check status:

```bash
kubectl -n pfa get pods -o wide
kubectl -n pfa get pvc
```

### Local pipeline (Buildkite-style, no server)

`k8s/pipeline.sh` runs the whole flow locally and keeps artifacts under
`.pipeline/<run-id>/`:

```
preflight -> build -> push -> render -> deploy -> verify
```

```bash
./k8s/pipeline.sh                          # full run
./k8s/pipeline.sh --skip-build             # config change only
./k8s/pipeline.sh --skip-deploy            # build + validate manifests only
PUSH_REGISTRY=ghcr.io/you/pfa ./k8s/pipeline.sh   # also docker push
```

The `render` stage writes the envsubst'd ClickHouse/Grafana files to the run
directory, renders any Helm charts found in `k8s/charts/` (optional), and
server-side dry-runs every manifest before deploying. `verify` waits for
rollouts, then probes the ingress and the public tunnel URL.

## 5. Reach the app

The Cloudflare tunnel is the simplest route: `./k8s/apply.sh --with-cloudflared`
(or `kubectl apply -f k8s/optional/cloudflared.yaml`) connects the tunnel whose
dashboard config routes `verginjose.cloud -> http://frontend:80`; the app is then
at **https://verginjose.cloud**.

```bash
# Option A — ingress (k3s Traefik listens on :80)
echo "127.0.0.1 finance.local" | sudo tee -a /etc/hosts
curl http://finance.local/

# Option B — port-forward
kubectl -n pfa port-forward svc/frontend 3001:80    # http://localhost:3001
kubectl -n pfa port-forward svc/grafana  3000:3000  # http://localhost:3000
```

Prometheus, MinIO console, RabbitMQ management and the databases are only
reachable with `port-forward` (they are `ClusterIP` services, matching the
`127.0.0.1`-only port bindings in compose).

## How the pieces differ from Compose

| Compose | Kubernetes |
|---|---|
| named volumes | `volumeClaimTemplates` / PVCs on the default StorageClass |
| `docker-compose` healthchecks | `readinessProbe` / `livenessProbe`; Spring services also get a `startupProbe` on `/actuator/health/liveness` |
| `restart: on-failure` | automatic pod restarts + probes |
| Postgres replica runs `pg_basebackup` inline | an `initContainer` seeds the empty PVC once, then streams WAL |
| Redis replica has no auth against a password-protected client | master runs with `--requirepass`, replica with `--masterauth` (compose's Redis had no password while clients sent one) |
| RabbitMQ guest user from another container | `loopback_users.guest = false` so in-cluster clients can use guest |
| Vector reads the Docker socket | Vector DaemonSet reads `/var/log/pods` via the Kubernetes API, filtered to the `pfa` namespace |
| `cloudflared` on the compose network | `optional/cloudflared.yaml`, routed to `frontend.pfa.svc.cluster.local:80` |
| Startup ordering via `depends_on` | services retry; the replica's init container explicitly waits for the primary |
| MinIO image `minio/minio` | `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` — the official Docker Hub repo was removed and Quay requires auth; this fork keeps the same entrypoint and API |
| `auth.users` created by the gateway's `schema.sql` | also created by `db-init/007_create_auth_users.sql`, because command/query Hibernate could create a 3-column version first on a fresh DB |

## Operational notes and caveats

- **Secrets:** `apply.sh` copies every key from `.env` into the `pfa-secrets`
  Secret. The rendered ClickHouse/Grafana ConfigMaps also contain credentials,
  so anyone with read access to ConfigMaps in the namespace can see them. Don't
  use this overlay unchanged on a shared cluster.
- **Changing secrets:** editing `.env` and rerunning `apply.sh` updates the
  Secret, but pods only pick up new env on restart:
  `kubectl -n pfa rollout restart deploy/api-gateway` (etc.).
- **Storage:** `local-path` volumes are node-bound. That is fine for a
  single-node cluster; for multi-node you need a networked StorageClass, and
  the Postgres/RabbitMQ/redis StatefulSets are not configured for HA.
- **`imagePullPolicy: IfNotPresent`** is intentional: it lets k3s use the
  imported images. With a registry and a mutable tag, change it to `Always`.
- **ClickHouse → Postgres engine:** the compose init file referenced a
  non-existent `postgres-db` host and hardcoded credentials. The overlay renders
  `config/clickhouse-postgres.sql.tmpl` with `postgres-primary` and `.env`
  values instead.
- **Observability profile:** compose gates these behind `--profile observability`;
  the overlay always deploys them. Delete `infrastructure/prometheus.yaml`,
  `grafana.yaml`, `clickhouse.yaml` and `vector.yaml` (and the `vector` scrape
  job) if you want the leaner app-only footprint.
- **GPU scheduling:** if no node advertises `nvidia.com/gpu`, the OCR pod stays
  `Pending`. That's the intended signal to either set up the device plugin or
  remove the GPU limit.
