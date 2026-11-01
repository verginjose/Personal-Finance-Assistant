# Running Personal Finance Assistant on Kubernetes

This directory is an additive Kubernetes overlay for the existing
`docker-compose.yml`. Docker Compose keeps working; nothing here is referenced
from the services themselves.

```
k8s/
├── 00-namespace.yaml          # namespace: pfa
├── apply.sh                   # bootstrap: controllers, sealed secrets, infra, apps, ingress
├── apply-observability.sh     # re-render + restart Grafana/Prometheus/Vector only
├── seal-secrets.sh            # re-encrypt .env + env/<svc>.env into k8s/sealed/
├── sync-env.sh                # re-seal, apply, and roll consumers (infra workaround)
├── build-images.sh            # offline fallback: build images, import into k3s/kind
├── pipeline.sh                # offline fallback: full build -> render -> deploy -> verify
├── setup-k3s-nvidia.sh        # one-time privileged k3s + GPU + firewalld setup
├── cleanup.sh                 # tear down CI / app / images / host config / cluster
├── secrets.example.yaml       # template for the infrastructure Secret (reference only)
├── sealed/                    # committed SealedSecrets (source of truth for the cluster)
├── sealed-secrets/            # vendored controller manifests (v0.41.2)
├── reloader/                  # vendored Reloader manifests (v1.4.23)
├── edge/
│   ├── ingress.yaml           # finance.local -> frontend
│   └── ui-access.yaml         # public Grafana hostname
├── SERVICE(S).md etc.         # SERVICES.md, RUNBOOK.md, START-STOP.md, CHEATSHEET.md
├── ci/                        # CI/CD: in-cluster registry, BuildKit, runner
│   ├── registry.yaml          # registry:2 + NodePort 30500 + 50 Gi PVC
│   ├── buildkit.yaml          # rootless BuildKit + 40 Gi cache PVC
│   ├── runner.yaml            # self-hosted GitHub Actions runner
│   └── apply.sh, setup-registry.sh, registry-gc.sh, debug.sh, README.md
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
│   ├── auth-service.yaml
│   ├── api-gateway.yaml
│   ├── command-service.yaml
│   ├── query-service.yaml
│   ├── ocr-service.yaml       # requires an NVIDIA GPU
│   └── frontend.yaml
├── config/
│   ├── infra/                 # committed static ConfigMaps (Argo-managed)
│   ├── grafana/               # datasource template (password-bearing)
│   └── clickhouse-postgres.sql.tmpl
└── optional/
    └── cloudflared.yaml

argocd/                        # app-of-apps (see README.md "What Argo manages")
├── root.yaml
└── apps/                      # 05-secrets, 10-infra, 20-apps, 30-ci, 40-edge
```

## How delivery works (CI/CD architecture)

Nothing on the laptop builds images for the cluster any more. A push to GitHub
is the only input:

```
 your laptop                GitHub                    k3s node (this machine)
 ───────────                ──────                    ───────────────────────
 git push  ──────────────▶  build.yml  ──job──▶  ci-runner pod  (namespace pfa)
 (master or branch)             │                   │ buildctl over TCP
                                │                   ▼
                                │            buildkitd pod ──build+push──▶ registry pod
                                │                                         :30500
                                │                   │                        │
                                ▼                   │                        │
                            git history  ◀── tag-bump commit ───────────────┘
                                │                (image: ...:<sha>)
                                ▼
                        Argo CD (namespace argocd)
                        polls k8s/apps on master ≤3 min
                                │  applies the manifests
                                ▼
                        Deployments roll out the new pods
```

One push, step by step:

1. **GitHub queues `build.yml`.** It triggers on pushes to `master` or
   `k8s/local-deployment`, ignoring changes that only touch `**.md` or
   `k8s/ci/**`.
2. **`detect`** diffs the whole push (`BEFORE..AFTER`, falling back to
   `HEAD^` on a new branch) and keeps only services whose directory changed
   (`query-service/**` → `query-service`). No matches → no build.
3. **`build`** runs one service at a time (`max-parallel: 1`; OCR is ~10 GB and
   the node cannot host two builds at once). It calls BuildKit directly:

   ```bash
   buildctl build --local context="./query-service" \
     --output type=image,name=registry.pfa.svc.cluster.local:5000/finance/query-service:<sha>,push=true
   ```

4. **Tag bump.** The job rewrites the one `image:` line in
   `k8s/apps/query-service.yaml` to the new immutable SHA tag, commits it as
   `ci: query-service -> <sha> [skip ci]`, and pushes to the branch it built
   (rebasing and retrying if the branch moved).
5. **Argo CD** watches `k8s/apps` on **`master`**, polls every ≤3 min, and applies
   the change. Kubernetes rolls only that Deployment.

The runner has **no cluster permissions** — it only builds, pushes and commits.
Argo CD is the only component that mutates workload manifests.

### What Argo manages

Argo uses an **app-of-apps** tree under `argocd/`:

```
argocd/root.yaml              → points at argocd/apps
argocd/apps/05-secrets.yaml   → k8s/sealed            (SealedSecrets)
argocd/apps/10-infra.yaml     → k8s/infrastructure + k8s/config/infra
argocd/apps/20-apps.yaml      → k8s/apps              (the six services)
argocd/apps/30-ci.yaml        → k8s/ci                (registry, BuildKit, runner)
argocd/apps/40-edge.yaml      → k8s/edge              (ingress)
```

The `sync-wave` annotation on each child only orders their creation; after that
each reconciles independently. All children use `prune: false` and
`selfHeal: true`.

**Still outside Argo**, and applied by `./k8s/apply.sh`:

- the Sealed Secrets controller and Reloader (Argo depends on the former);
- `clickhouse-init` and `grafana-datasources` — ConfigMaps that embed a password
  from `.env`;
- `optional/cloudflared.yaml` (opt-in);
- `/etc/rancher/k3s/registries.yaml`, which is a host file, not a Kubernetes object.

**Branch vs merge.** A push to `k8s/local-deployment` builds a real image and
commits the tag bump on that branch, but Argo ignores it — so nothing deploys
until the branch is merged into `master`. Merging carries the manifest change
over, and Argo syncs it.

**Escape hatch.** `detect` infers the build set from changed paths, so an image
that needs rebuilding without a directory change (base-image bump, cache
eviction) is never picked up. Build any selection explicitly:

```bash
TOKEN=$(sed -n 's/^GITHUB_PAT=//p' .env)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.github+json' \
  https://api.github.com/repos/verginjose/Personal-Finance-Assistant/actions/workflows/build.yml/dispatches \
  -d '{"ref":"master","inputs":{"service":"all"}}'
```

`service` is `all` or one of the six service names. Setup and deeper
operations are in [`ci/README.md`](ci/README.md).

### What a push updates — and what it does not

Only the six service directories (`auth-service/`, `api-gateway/`,
`command-service/`, `query-service/`, `frontend/`, `ocr-service/`) go through
the build → tag-bump → Argo path. A commit that touches none of them still runs
`detect`, finds `services=[]`, **skips the build, and restarts nothing**.

Everything else lands by one of three routes:

| You changed | How it reaches the cluster |
|---|---|
| `<service>/**` | Automatic: CI builds `${REGISTRY}/finance/<svc>:<sha>`, bumps `k8s/apps/<svc>.yaml`, Argo syncs |
| `k8s/apps/*.yaml` non-image fields (env, replicas, resources, probes) | Argo applies on push via `selfHeal` — no build needed |
| `k8s/infrastructure/*.yaml` | **Manual**: `kubectl apply -f k8s/infrastructure/` (or `./k8s/apply.sh`) |
| `observability/grafana/dashboards/*.json` | Regenerate `k8s/config/infra/grafana-dashboards.yaml` (see below); Argo syncs it, Reloader restarts Grafana |
| `observability/grafana/provisioning/**` | **Manual**: re-render `grafana-datasources` + `grafana-dashboard-provider`, then restart Grafana |
| `observability/prometheus/prometheus.yml` | **Manual**: recreate `prometheus-config`, then restart Prometheus (config is not hot-reloaded) |
| `observability/vector/vector.yaml` | ⚠️ **Unused by k8s** — the cluster runs the inline copy in `k8s/infrastructure/vector.yaml`; compose uses the file |
| `observability/clickhouse/init/*.sql`, `db-init/*` | ConfigMap only — executed **once, on first init of an empty data dir**; existing databases need the DDL applied by hand |
| `docker-compose.yml` | Local dev only — never touches the cluster |

Use [`apply-observability.sh`](apply-observability.sh) — it recreates the
ConfigMap *and* restarts the pod that reads it, which is the part that is easy
to forget:

```bash
./k8s/apply-observability.sh                  # dashboards, datasources, prometheus, vector
./k8s/apply-observability.sh dashboards       # one target
./k8s/apply-observability.sh vector --no-restart
./k8s/apply-observability.sh infra            # k8s/infrastructure/*.yaml
```

Grafana's file provider polls every 10 s, so dashboard JSON can appear on its
own; datasources are read only at startup, and Vector/Prometheus configs are
subPath mounts, so those always need the restart the script performs.
`./k8s/apply.sh` is the sledgehammer — full re-render, waits on every
StatefulSet, and restarts only Prometheus.

Caveats:

- **Argo `selfHeal` reverts manual image edits.** Hot-patching an image without
  committing the tag in `k8s/apps/` is undone on the next sync.
- **Argo does not prune** (`prune: false`): deleting a file under `k8s/apps/`
  leaves the live resource running.
- **CI ignores `**.md` and `k8s/ci/**`** in `paths-ignore`, so doc-only pushes
  do not trigger a run.
- **Init SQL is first-boot only.** `postgres-init` and `clickhouse-init` are
  read by the entrypoint when the data directory is empty; editing them later
  changes nothing on a running database.

### Which pods take part

**Delivery pods** — namespace `pfa`, plus Argo CD in `argocd`:

| Pod | Image | Job in the flow | Talks to |
|---|---|---|---|
| `ci-runner` | `myoung34/github-runner` | Runs the workflow steps: detect, `buildctl`, commit the tag bump | GitHub (HTTPS), `buildkitd:1234`, git |
| `buildkitd` | `moby/buildkit` (rootless) | Executes the Dockerfile layers and pushes OCI blobs | `registry:5000`, cache PVC |
| `registry` | `registry:2.8.3` | Stores image blobs, serves them to containerd | PVC `registry-data` |
| `argocd-repo-server` | `argocd` | Clones `master` with the PAT, renders `k8s/apps/` | GitHub |
| `argocd-application-controller` | `argocd` | Diffs rendered manifests against the live cluster, applies them | k3s API |
| `argocd-server` | `argocd` | UI / REST API | you |
| `argocd-redis` | `redis` | Cache for the two above | argocd pods |
| `argocd-dex-server`, `argocd-applicationset-controller`, `argocd-notifications-controller` | `argocd` | Installed, unused (no SSO, one hand-written app, no notifications) | — |

**Application pods** — namespace `pfa`: the six services (`frontend`,
`api-gateway`, `auth-service`, `command-service`, `query-service`, `ocr-service`) and their
dependencies (`postgres-primary/replica`, `redis`, `redis-replica`,
`rabbitmq`, `minio`, `clickhouse`, `vector`, `prometheus`, `grafana`) plus
`cloudflared` for the public URL. These are *consumers* of the flow, not
participants: they notice it only when their Deployment's `image:` changes.

**Cluster plumbing** — `kube-system`: `traefik` (ingress, binds :80),
`coredns` (Service DNS, e.g. `buildkitd.pfa.svc`), `metrics-server`,
`local-path-provisioner` (creates the PVC directories), `nvidia-device-plugin`
(advertises the GPU), `svclb-traefik`.

```
you  git push
        │
        ▼
   GitHub (cloud) ──── job ────▶ ci-runner  ──gRPC :1234──▶ buildkitd
                                     │                        │
                                     │           push layers  ▼
                                     │                    registry (30500)
                                     │                        │
                                     └── git push tag bump ───┘
                                              │
                                              ▼
                                          GitHub master
                                              │ clone / poll
                                              ▼
                                   argocd-repo-server
                                              │
                                              ▼
                          argocd-application-controller ──▶ k3s API
                                                              │
                                              Deployment changed
                                                              ▼
                                            kubelet (host process, not a pod)
                                                     pulls from registry
                                                              ▼
                                                    new app pod starts
```

Two structural facts: the runner **never touches the k3s API** (its only
outputs are an image and a commit, so it needs no RBAC), and **kubelet and
containerd are host processes inside the `k3s` service**, not pods — which is
why they cannot resolve `*.svc` names and the registry must be a NodePort.

### What actually executes the YAML

The manifests are only desired state; k3s controllers and upstream images do
the work:

| Declared in | Executed by | Upstream source |
|---|---|---|
| any `kubectl apply` | k3s binaries (API server, controllers, scheduler, kubelet, containerd) | Kubernetes / k3s |
| `Deployment`, `Service`, `PVC` | k3s controllers (continuous reconciliation) | Kubernetes |
| `registry.yaml` | `registry:2.8.3` image | distribution/distribution |
| `buildkit.yaml` | `moby/buildkit` image | moby/buildkit |
| `runner.yaml` | `myoung34/github-runner` image | actions/runner |
| `argocd/apply.sh` | installs the `argocd` image (controller + repo-server) | argoproj/argo-cd |
| `build.yml` steps | shell executed inside `ci-runner` | **this repo** |

The only build logic we authored is the shell in `.github/workflows/build.yml`
and the helper scripts in `k8s/ci/`; the six `Dockerfile`s describe each app
image.

### Where the images are stored

The registry keeps blobs in the `registry-data` PVC, which on this single node
is a host directory:

```
/var/lib/rancher/k3s/storage/pvc-<uuid>_pfa_registry-data/
  └── docker/registry/v2/
        ├── blobs/                       content-addressed layers (compressed)
        └── repositories/                names and tags
```

Layers are deduplicated by SHA-256, so shared bases are stored once — six
images fit in ~6.5 GiB even though the OCR image is ~10 GiB unpacked.
Deleting a tag does **not** free space until `./k8s/ci/registry-gc.sh` runs.

Each deployed image is also copied into containerd's store
(`/var/lib/rancher/k3s/agent/containerd/`) — compressed content plus unpacked
snapshots, so an image costs disk twice. Backing up the registry PVC is enough
to preserve every image.

### How a rollout replaces a pod

| Service | Strategy | Behaviour |
|---|---|---|
| `ocr-service` | `Recreate` | old pod terminated **first**, then the new one starts (brief downtime) — the `paddle-model-cache` PVC is ReadWriteOnce and the pod claims the only GPU |
| everything else | `RollingUpdate` 25%/25% | new pod started, waits until Ready, then the old one is terminated — no downtime |

Old ReplicaSets are kept at 0 replicas for `kubectl rollout undo`; old image
tags stay in the registry and containerd until pruned. Termination sends
SIGTERM with the grace period, and the pod leaves the Service endpoints before
it dies.

### Why builds run one at a time

`concurrency: group: build-and-deploy` allows one workflow run at a time, and
the matrix uses `max-parallel: 1`. Two Maven builds plus the running stack would
exceed the node's memory, and parallel jobs would race on the tag-bump commit
(each job pushes a commit to the same branch). Jobs check out the branch tip,
not the triggering SHA, so each build includes the previous tag bump; a
rebase-retry loop covers pushes made by hand during a build. Only services whose
directories changed are built (`HEAD^ HEAD` diff), so a frontend fix never
rebuilds the 10 GiB OCR image.

## Start and access the cluster

k3s is enabled at boot, so it is already running after a normal reboot. The two
scripts wrap the day-to-day operations:

```bash
./k8s/start.sh                      # start, wait for readiness, forward UIs, hide passwords
./k8s/start.sh --secrets            # print only the passwords (incl. Argo CD), then exit
./k8s/start.sh --no-port-forward    # do not start the :3000/:8080/... forwards
./k8s/start.sh --stop-port-forwards # stop the forwards and exit
./k8s/start.sh --full               # also run ./k8s/apply.sh

./k8s/stop.sh                       # stop k3s cleanly, delete nothing
./k8s/stop.sh --cancel-runs         # also cancel in-flight GitHub Actions builds
./k8s/stop.sh --gc                  # also prune old registry tags first
```

`start.sh` forwards frontend `:3001`, Grafana `:3000`, Prometheus `:9090`,
MinIO `:9001` (console) and `:9000` (API), RabbitMQ `:15672` and Argo CD
`:8080` in the background and reuses them if they are already up. `stop.sh`
kills them.

Underneath it is just:

```bash
sudo systemctl start k3s
export KUBECONFIG="$HOME/.kube/k3s.yaml"   # kubeconfig lives here, not ~/.kube/config
kubectl get nodes               # jose-fedora Ready after ~30 s
kubectl -n pfa get pods         # 22 workloads
./k8s/ci/debug.sh               # full health report (cluster, CI, Argo)
```

Pods come back in place on the next start because all state is on PVCs under
`/var/lib/rancher/k3s/storage`. To verify that before shutting the laptop down
(baseline → stop → start → compare), follow
[`START-STOP.md`](START-STOP.md).

| What | How | URL |
|---|---|---|
| App (public, Cloudflare tunnel) | `kubectl -n pfa get pods -l app=cloudflared` must be Running | **https://verginjose.cloud** |
| App (Traefik ingress) | `echo "127.0.0.1 finance.local" \| sudo tee -a /etc/hosts` | http://finance.local |
| Frontend (port-forward) | `kubectl -n pfa port-forward svc/frontend 3001:80` | http://localhost:3001 |
| Grafana | `kubectl -n pfa port-forward svc/grafana 3000:3000` | http://localhost:3000 |
| Prometheus | `kubectl -n pfa port-forward svc/prometheus 9090:9090` | http://localhost:9090 |
| Argo CD UI | `kubectl -n argocd port-forward svc/argocd-server 8080:443` | https://localhost:8080 |
| Argo CD password | `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' \| base64 -d` | user `admin` |
| Image registry | `curl http://127.0.0.1:30500/v2/_catalog` | http://127.0.0.1:30500 |
| CI runs | — | github.com/verginjose/Personal-Finance-Assistant/actions |

Every `port-forward` blocks its terminal; add `&` to run others alongside it.
The Grafana admin password is `GRAFANA_ADMIN_PASSWORD` in `.env`.

## Why this maps cleanly

The services already resolve each other by Compose service name, so plain
Kubernetes `Service` objects with the same names keep the configuration intact:

| Config entry | Compose DNS | Kubernetes Service |
|---|---|---|
| `spring.cloud.gateway.routes[].uri` | `auth-service:8082`, `command-service:8081`, `query-service:8084` | same |
| gateway `minio.endpoint` | `minio:9000` | same |
| `frontend/nginx.conf` `proxy_pass` | `api-gateway:8080` | same |
| `REDIS_HOST` / `SPRING_DATA_REDIS_HOST` | `redis` | same |
| `SPRING_DATASOURCE_URL` | `postgres-primary` / `postgres-replica` | same |
| OCR `RABBITMQ_URL`, `UPSERT_SERVICE_URL` | `rabbitmq`, `command-service` | same |

No application code or `application.yaml` needed changing.

## 1. Install a local cluster (k3s)

Sections 1–4 are one-time setup (and re-run after major changes). Day-to-day
operation is just `git push`; starting and reaching the cluster is covered
above.

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

> **Automated path (recommended):** `k8s/ci/` runs a self-hosted GitHub Actions
> runner inside the cluster that builds changed services, pushes them to an
> in-cluster registry, and updates the Deployment tag. See
> [`ci/README.md`](ci/README.md). The manual flow below remains the offline
> fallback.

k3s does not use Docker's image store, so `build-images.sh` builds with Docker
and imports the tarballs into containerd.

```bash
./k8s/build-images.sh              # all six services
SKIP_OCR=1 ./k8s/build-images.sh   # skip the multi-GB CUDA image
```

The Spring Dockerfiles compile with Maven inside the image, so the first run is
slow (5–10 min per service). Rerun this script after any code change, then
restart the relevant Deployment with `kubectl -n pfa rollout restart deploy/<name>`.

If you prefer a registry, set `REGISTRY_PREFIX=ghcr.io/you`, push the images,
update the `image:` fields, and create an `imagePullSecret`.

## 4. Deploy

The script installs the controllers, applies the committed SealedSecrets (from
`k8s/sealed/`) and renders the two ConfigMaps that embed a password from your
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

- **Secrets:** `.env` stays the local source of truth, but the cluster never
  reads it directly. Secrets are committed **encrypted** in `k8s/sealed/` and
  decrypted in-cluster by the Sealed Secrets controller running in
  `kube-system`. `apply.sh` applies `k8s/sealed/` and waits for the Secrets to
  appear. There is one Secret per application service
  (`auth-service-secrets`, `api-gateway-secrets`, …) plus `pfa-secrets` for
  infrastructure and `pfa-ci-secrets` for the GitHub runner. See
  `env/README.md`.
- **Changing secrets:** edit `.env`, run `./k8s/seal-secrets.sh --apply`, commit
  the regenerated `k8s/sealed/*.yaml`, push. Reloader restarts pods annotated
  with `reloader.stakater.com/auto: "true"`. Workloads without that annotation
  still need a manual `kubectl -n pfa rollout restart` (or use `sync-env.sh`).
  Sealing is randomized: re-running the script always produces a diff, even
  when no value changed.
- **Per-service config:** non-secret settings (hostnames, ports, JVM flags,
  feature toggles) live in a `<service>-config` ConfigMap inside each
  `k8s/apps/<service>.yaml`, so changing them goes through git + Argo like any
  other manifest.
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
