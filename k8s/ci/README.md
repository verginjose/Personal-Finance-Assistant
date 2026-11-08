# CI: build → registry → deploy

Replaces the manual `./k8s/build-images.sh` + `docker save` + `sudo k3s ctr
images import` loop with a self-hosted GitHub Actions runner inside the cluster.

```
git push
  └─ GitHub Actions (ci-runner pod)
       ├─ detect which service directories changed
       ├─ buildctl → buildkitd pod → push registry.pfa.svc:5000/finance/<svc>:<sha>
       └─ commit the new tag into k8s/apps/<svc>.yaml   [skip ci]
            └─ Argo CD (argocd namespace)
                 └─ polls master, syncs k8s/apps → Deployment rolls out
```

The runner holds **no cluster permissions**: it builds, pushes and commits.
Argo CD is the only thing that mutates the cluster.

Both hops are **mutual TLS**:

```
runner --mTLS 1234--> buildkitd --mTLS 5000--> registry <--mTLS 30500-- containerd
```

Every client presents a certificate signed by the CI CA. NetworkPolicies in
`network-policies.yaml` restrict which pods may open each connection at all.
There is no plaintext listener left in the chain.

The build itself is unchanged: the same Dockerfiles run, so the Spring services
still compile with Maven inside the image.

## One-time setup

### 1. Generate the mTLS material

```bash
./k8s/ci/gen-tls-certs.sh
```

Writes the plaintext CA and certificates to `.certs/` (gitignored — **keep
this directory**) and the encrypted Secrets to
`k8s/sealed/ci-tls-secrets.yaml` (commit it). The three Secrets are:

| Secret | Keys | Used by |
|---|---|---|
| `ci-tls-registry` | `tls.crt`, `tls.key`, `ca.crt` | registry server |
| `ci-tls-buildkitd` | `server.crt`, `server.key`, `client.crt`, `client.key`, `ca.crt` | buildkitd server + its registry client |
| `ci-tls-client` | `ca.crt`, `client.cert`, `client.key` | runner, CronJobs, mirror pods, host containerd |

Certificate lifetime is 10 years and nothing renews it automatically.
`gen-tls-certs.sh --force` rotates everything; that invalidates all mounts and
restarts the three workloads.

### 2. Tell containerd the registry is mTLS (needs root)

kubelet/containerd run on the host, so they cannot resolve `registry.pfa.svc`.
Use the NodePort on loopback instead — it never changes with DHCP, and the
registry certificate carries a `127.0.0.1` SAN. The script copies the CA and
the client certificate from `.certs/` into `/etc/rancher/k3s/registry-certs/`
and rewrites `/etc/rancher/k3s/registries.yaml`:

```
! sudo ./k8s/ci/setup-registry.sh
```

k3s takes ~30 s to come back; give it a minute, then check
`kubectl get pods -n pfa`. The manifests pull from `127.0.0.1:30500` and
BuildKit pushes to `registry.pfa.svc:5000`, so neither depends on the node's
LAN IP.

**Ordering matters once.** Run this in the same window as the registry
roll-out (`apply.sh` / an Argo sync of `k8s/ci`). If one side has switched to
HTTPS and the other has not, image *pulls* fail until both agree. Running pods
are unaffected and cached images keep working.

### 3. Create a GitHub PAT

Settings → Developer settings → Personal access tokens.

- **Fine-grained**: repository access = this repo; permissions
  **Administration: Read and write** (needed to register the runner) and
  **Contents: Read and write** (needed to push the tag-bump commit).
- **Classic**: `repo` scope is enough.

### 4. Put it in `.env`

```bash
echo 'GITHUB_PAT=ghp_...' >> .env
```

`.env` is gitignored and `apply.sh` copies it into the `pfa-ci-secrets` Secret.

### 5. Deploy

```bash
./k8s/ci/apply.sh
```

### 6. Backfill all images once

The Deployments still point at `finance/<svc>:latest` (the containerd-imported
images). A dispatch run with `service=all` builds and pushes every image, then
rewrites the manifests:

```bash
gh workflow run build-and-deploy -f service=all
gh run watch
```

After that, normal pushes only rebuild the services whose directories changed —
in particular the ~10 GB `ocr-service` image is only rebuilt when
`ocr-service/**` changes.

## Day to day

```bash
git push            # done
```

Deploys can also be triggered from the Actions tab (`workflow_dispatch`).

### Base images

The service Dockerfiles take their base images from the registry mirror
(`base/maven`, `base/eclipse-temurin`, `base/nginx`) rather than Docker Hub, so
a cold BuildKit cache costs a loopback pull instead of an internet download.
The CI build passes `BASE_IMAGE_REGISTRY=$PUSH_REGISTRY/base`; outside the
cluster the Dockerfiles default to `docker.io/library`, so `docker compose
build` is unchanged.

After changing a base image tag in any Dockerfile, re-seed the mirror:

```bash
./k8s/ci/mirror-base-images.sh             # copies what is missing
./k8s/ci/mirror-base-images.sh --force     # re-copies existing tags
./k8s/ci/mirror-base-images.sh --dry-run
```

The script runs `skopeo` as a one-shot pod and copies linux/amd64 only (the
cluster is single-arch). Both GC paths are scoped so the mirror survives:
`registry-gc.sh` trims tags only under `finance/`, and the BuildKit ocr-base
rule matches `finance/ocr-base` specifically.

## Deployment: Argo CD

Argo CD watches `k8s/apps` on `master` and syncs automatically (no prune,
`selfHeal: true`). Set it up with:

```bash
./argocd/apply.sh     # installs Argo CD if absent, registers the repo
```

It reuses `GITHUB_PAT` as the HTTPS credential for the private repo, stored in
the `argocd/pfa-repo` Secret.

```bash
# UI
kubectl -n argocd port-forward svc/argocd-server 8080:443   # https://localhost:8080
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d

# state
kubectl -n argocd get applications
```

Sync latency is the poll interval (3 min by default). To make it instant, add
a GitHub webhook pointing at the Argo CD API server — that requires exposing
it publicly, which this setup does not do.

**Which branch deploys.** `targetRevision: master`. CI commits the tag bump to
the branch that triggered the build, so a push to `k8s/local-deployment` builds
an image but does not deploy it until merged into `master`. Change the field in
`argocd/apps/*.yaml` if you want feature-branch deploys.

**Infrastructure is now managed by Argo** through `argocd/apps/10-infra.yaml`
(`k8s/infrastructure` + `k8s/config/infra`). Secrets come from the committed
SealedSecrets in `k8s/sealed/`. The two ConfigMaps that embed a password
(`clickhouse-init`, `grafana-datasources`) are still rendered by `k8s/apply.sh`.

## Design notes

| Decision | Why |
|---|---|
| Registry is a **NodePort** | containerd runs outside the pod network and cannot resolve ClusterIPs. Whatever `image:` says must be resolvable from the host. |
| **mTLS on every hop** | buildkitd used to accept builds from anything in the cluster (it ran a plain, unauthenticated TCP listener) and the registry accepted unauthenticated pushes. A client certificate is now required on 1234 and 5000; NetworkPolicies narrow it further. |
| Registry probes are **tcpSocket** | kubelet HTTP probes cannot present a client certificate, so an HTTP(S) probe against an mTLS listener always fails. |
| Client cert is **shared** by runner, buildkitd, CronJobs and containerd | Single-tenant homelab; one identity keeps the Secret count down. Split it per workload if that stops being true. |
| Tags are **commit SHAs**, not `latest` | Immutable: a rebuild is visible, rollback is `git revert`, and kubelet never reuses a stale cache entry. |
| Runner is **not** privileged, no docker socket | Builds happen in the rootless BuildKit pod. Under SELinux Enforcing on Fedora this is safer than docker-in-docker. |
| BuildKit cache lives on a **40 Gi PVC** | Maven dependency downloads dominate build time; the cache makes rebuilds minutes instead of tens of minutes. |
| `max-parallel: 1` | Node has ~8 GiB free; two Maven builds plus the running stack can OOM. |
| Path-based service detection | Avoids rebuilding the 10 GB CUDA OCR image on every frontend tweak. |

## Operations

**Registry is filling up.** `registry:2` has no retention policy. Inspect and
collect garbage:

```bash
./k8s/ci/registry-gc.sh --dry-run
./k8s/ci/registry-gc.sh
```

Each image variant keeps every layer until GC; with 50 Gi and a 10 GB OCR image
you will eventually need it.

**A build fails on push with `unexpected status`.** The registry is not
reachable over HTTPS with the client certificate. Recheck step 2, then:

```bash
curl --cacert .certs/ca.crt --cert .certs/client.crt --key .certs/client.key \
     https://127.0.0.1:30500/v2/          # must return {}
```

A `certificate required` / handshake error means the client material is wrong;
`x509: certificate is valid for ...` means a SAN does not match the host you
dialled. The certs cover `registry`, `registry.pfa*` and `127.0.0.1`.

**Certificates expired.** Everything is valid for 10 years from
`gen-tls-certs.sh`. Rotating before then: `./k8s/ci/gen-tls-certs.sh --force`,
commit the sealed file, then re-run `sudo ./k8s/ci/setup-registry.sh` and let
Argo roll the registry/buildkitd/runner.

**BuildKit pod crash-loops.** Check `kubectl -n pfa logs deploy/buildkitd`.
The usual causes are SELinux (the pod sets `seLinuxOptions.type: spc_t` and
`seccompProfile: Unconfined` for this reason), the PVC being owned by root —
the `fix-perms` initContainer handles the latter — and a missing/unreadable
`ci-tls-buildkitd` Secret.

**Runner is offline in GitHub's UI.** The PAT probably expired or lacks
Administration permission; check `kubectl -n pfa logs deploy/ci-runner`.

**Runner has no cluster permissions.** The interim `Deploy to the cluster` step
and its `ci-deployer` Role/RoleBinding were removed once Argo CD took over
reconciliation. `runner.yaml` is only a ServiceAccount plus the runner
Deployment — if the runner ever needs `kubectl`, something is wrong with Argo.

## Health and teardown

```bash
./k8s/ci/debug.sh          # cluster, workloads, registry, BuildKit, runner, Argo, CI runs
./k8s/cleanup.sh --ci      # remove Argo CD, pfa-ci-secrets, host registry certs + registries.yaml
./k8s/cleanup.sh --app     # remove namespace pfa (including registry + cache PVCs)
```

`--ci` leaves the registry volume data (it lives in the `pfa` namespace).

## Files

| File | Contents |
|---|---|
| `registry.yaml` | 50 Gi PVC + `registry:2` Deployment + ConfigMap + NodePort 30500 |
| `buildkit.yaml` | rootless BuildKit Deployment (mTLS 1234), 40 Gi cache PVC |
| `runner.yaml` | ServiceAccount + `myoung34/github-runner` Deployment + client cert mount + buildctl PVC |
| `network-policies.yaml` | Ingress/egress allow-lists for every CI pod |
| `gen-tls-certs.sh` | Generates the CA/certs into `.certs/` and seals them into `k8s/sealed/ci-tls-secrets.yaml` |
| `apply.sh` | Deploys the TLS Secrets, policies and three workloads |
| `setup-registry.sh` | (root) installs host certs, writes `/etc/rancher/k3s/registries.yaml`, restarts k3s |
| `registry-gc.sh` | Tag retention (finance/* only) + garbage-collects untagged manifests |
| `mirror-base-images.sh` | Seeds `base/*` in the registry with the Docker Hub base images |
| `debug.sh` | Read-only health report for the whole chain |
| `argocd/` | Argo CD install + the `pfa-apps` Application |
| `.github/workflows/build.yml` | The pipeline |
