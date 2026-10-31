# CI: build → registry → deploy

Replaces the manual `./k8s/build-images.sh` + `docker save` + `sudo k3s ctr
images import` loop with a self-hosted GitHub Actions runner inside the cluster.

```
git push
  └─ GitHub Actions (ci-runner pod)
       ├─ detect which service directories changed
       ├─ buildctl → buildkitd pod → push 192.168.0.7:30500/finance/<svc>:<sha>
       ├─ commit the new tag into k8s/apps/<svc>.yaml   [skip ci]
       └─ kubectl apply + rollout status                (interim; Argo CD later)
```

The build itself is unchanged: the same Dockerfiles run, so the Spring services
still compile with Maven inside the image.

## One-time setup

### 1. Tell containerd the registry is plain HTTP (needs root)

kubelet/containerd run on the host, so they cannot resolve `registry.pfa.svc`.
The registry is exposed as a NodePort on the node IP instead. Run:

```
! sudo tee /etc/rancher/k3s/registries.yaml <<'EOF'
mirrors:
  "192.168.0.7:30500":
    endpoint:
      - "http://192.168.0.7:30500"
EOF
```

```
! sudo systemctl restart k3s
```

k3s takes ~30 s to come back; give it a minute, then check
`kubectl get pods -n pfa`. If the node IP is not static in your router's DHCP,
reserve it — the image names and this file both hardcode it.

### 2. Create a GitHub PAT

Settings → Developer settings → Personal access tokens.

- **Fine-grained**: repository access = this repo; permissions
  **Administration: Read and write** (needed to register the runner) and
  **Contents: Read and write** (needed to push the tag-bump commit).
- **Classic**: `repo` scope is enough.

### 3. Put it in `.env`

```bash
echo 'GITHUB_PAT=ghp_...' >> .env
```

`.env` is gitignored and `apply.sh` copies it into the `pfa-ci-secrets` Secret.

### 4. Deploy

```bash
./k8s/ci/apply.sh
```

### 5. Backfill all images once

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

## Design notes

| Decision | Why |
|---|---|
| Registry is a **NodePort** | containerd runs outside the pod network and cannot resolve ClusterIPs. Whatever `image:` says must be resolvable from the host. |
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
reachable over HTTP. Recheck step 1 — `curl http://192.168.0.7:30500/v2/` must
return `{}`.

**BuildKit pod crash-loops.** Check `kubectl -n pfa logs deploy/buildkitd`.
The usual causes are SELinux (the pod sets `seLinuxOptions.type: spc_t` and
`seccompProfile: Unconfined` for this reason) and the PVC being owned by root —
the `fix-perms` initContainer handles the latter.

**Runner is offline in GitHub's UI.** The PAT probably expired or lacks
Administration permission; check `kubectl -n pfa logs deploy/ci-runner`.

**Removing the interim deploy step.** Once Argo CD syncs `k8s/apps/`, delete
the `Deploy to the cluster` step from `.github/workflows/build.yml` and remove
the `ci-deployer` Role/RoleBinding from `runner.yaml`. The runner no longer
needs cluster permissions at all.

## Files

| File | Contents |
|---|---|
| `registry.yaml` | 50 Gi PVC + `registry:2` Deployment + NodePort 30500 |
| `buildkit.yaml` | rootless BuildKit Deployment, 40 Gi cache PVC, ClusterIP 1234 |
| `runner.yaml` | ServiceAccount, interim RBAC, `myoung34/github-runner` Deployment |
| `apply.sh` | Deploys all three and syncs `pfa-ci-secrets` from `.env` |
| `registry-gc.sh` | Garbage-collects untagged manifests from the registry |
| `.github/workflows/build.yml` | The pipeline |
