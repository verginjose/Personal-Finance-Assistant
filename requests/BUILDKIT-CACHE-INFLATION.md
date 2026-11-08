# Worked example: why the BuildKit cache hit 129 GB, and why the GC would not clean it

Found 2026-11-06 on the live cluster, fixed 2026-11-07. `buildctl du`
reported 42.86 GB and climbing; after a rebuild of all six services it read
**129.06 GB** (100.6 GB on disk) against a `40Gi` PVC claim.

This follows the format of [CACHE-CONSISTENCY-RACE.md](CACHE-CONSISTENCY-RACE.md):
each cause below is something that had to be observed rather than reasoned
about, and the fixes are `487f1e3`, `781417e` and `55badf5`.

## The pieces

| component | detail |
|---|---|
| `buildkitd` | rootless `moby/buildkit:v0.15.2`, OCI worker, `--oci-worker-snapshotter=native` |
| `buildkit-cache` PVC | `40Gi` claim, `local-path` (hostPath, **no quota**) |
| `registry` | `registry:2.8.3`, `registry-data` PVC `50Gi`, also `local-path` |
| CI | one immutable 12-char SHA tag per build; nothing expired them |

Both stores sit on the same 474 GiB root filesystem. The registry holds
`finance/*` images only; Docker Hub base images (maven, eclipse-temurin,
nginx, nvidia/cuda) exist solely in the BuildKit cache.

## Cause 1 — no gcpolicy means a cap of 10 % of the visible disk

`cmd/buildkitd/config/gcpolicy.go` → `DetectDefaultGCCap()` →
`gcpolicy_unix.go` → `DiskSpacePercentage = 10` → `AsBytes()` calls `statfs`
on the state directory. Because `local-path` is a hostPath, the daemon sees
the whole 474 GiB root, not the `40Gi` request:

```
509,315,383,296 bytes × 10 % ≈ 48 GB
```

So the PVC size on line 37 of `k8s/ci/buildkit.yaml` never enters the
calculation, and the cache drifts to ~48 GB on its own.

## Cause 2 — description filters cannot express "everything else"

The first fix pinned Docker Hub images and capped OCR pulls with
`description~=docker\.io` / `description~=registry\.pfa…`. Three properties of
the filter model broke it:

- **Build-result records match no description filter.** They read
  `mount / from exec /bin/sh -c mvn clean package` and
  `[4/4] COPY app/ .`; at the peak that was 46 GB with no rule covering it.
- **Filters within one rule are ANDed** (`containerd/filters.ParseAll`), so
  several categories need several rules.
- **There is no negation for regex.** Only `==`, `!=` (exact) and `~=` (regex)
  exist, so a filtered cap can never mean "everything except the pinned ones".

Worse, `keepBytes` is compared against the **whole cache**, not the matched
records (`pruneOnce`), so a filtered cap behaves like a global one.

## Cause 3 — a keepBytes cap deletes one record per GC run

```go
// cache/manager.go
if gcMode && len(toDelete) > 0 { batchSize = 1 }
else if batchSize > maxPruneBatch { batchSize = maxPruneBatch }   // maxPruneBatch = 10
```

A build that pulls a 16-layer image **adds 16 records**; a pass removes 1.
The old OCR rule was structurally guaranteed to lose that race by 15 layers
every time it ran.

## Cause 4 — the `native` snapshotter stores a full rootfs per layer

`containerd/snapshots/native`: *"NewSnapshotter returns a Snapshotter which
copies layers on the underlying file system."* No overlay, no stacking, no
shared blobs — every snapshot is a complete copy of the merged root.

Evidence that the sizes are whole trees, not diffs:

| Step | Real change | Cache record |
|---|---|---|
| `COPY src ./src` | 620 KB of source | 549.99 MB |
| `mvn dependency:go-offline` | writes to a cache mount, ~0 in the layer | 549.98 MB |

Both equal the maven builder rootfs. For `finance/ocr-base` (6.52 GB
compressed) the 16 layers cost 71.15 GB on disk:

| layer | instruction | cumulative compressed | on disk |
|---|---|---|---|
| 1 | Ubuntu rootfs | 29 MB | 0.05 GB |
| 2–3 | CUDA keyring, runtime | 90 MB | 0.16 GB |
| 4–5 | ld.so.conf, license | 90 MB | 0.16 GB each |
| 6 | cuBLAS + NCCL apt | 1528 MB | 2.80 GB |
| 7–9 | apt-mark, entrypoints | 1528 MB | 2.80 GB each |
| 10 | cuDNN apt | 2103 MB | 3.85 GB |
| 11 | python3.11 apt | 2197 MB | 4.02 GB |
| 12 | `COPY requirements.txt` | 2197 MB | 4.02 GB |
| 13 | `pip install paddlepaddle-gpu` | 6409 MB | 11.73 GB |
| 14 | `pip install cachetools…` | 6512 MB | 11.92 GB |
| 15 | `WORKDIR /app` | 6512 MB | 11.92 GB |
| 16 | `COPY app/ .` | 6512 MB | 11.92 GB |
| | **Σ** | | **≈ 71.2 GB** |

Ten of those layers come from `nvidia/cuda:12.6.3`; six from
`ocr-service/Dockerfile.full`. The same mechanism made the ocr-service build
itself cost 3 × 10.98 GB, for a Dockerfile that copies a 56 KB app directory.

## Cause 5 — the automatic GC never releases pull leases

Two code paths, both in `control/control.go`:

```go
func (c *Controller) Solve(...) {
    defer func() { time.AfterFunc(time.Second, c.throttledGC) }()   // after every build
}
func (c *Controller) gc() { ... w.Prune(ctx, ch, policy...) }        // conservative
func (c *Controller) Prune(...) {                                    // the RPC behind buildctl prune
    if atomic.LoadInt64(&c.buildCount) == 0 {
        imageutil.CancelCacheLeases()
    }
```

and in `util/imageutil/config.go`:

```go
var leasesF []func(context.Context) error
func CancelCacheLeases() { for _, f := range leasesF { f(...) }; leasesF = nil }
func AddLease(f func(context.Context) error) { leasesF = append(leasesF, f) }
```

A pull registers a release function in that process-global slice so the next
build reuses the resolved image without going back to the registry. **Nothing
expires it.** `cache/manager.go` skips any record with references before it
even evaluates the rules:

```go
if len(cr.refs) == 0 { ... evaluate filter / keepDuration here ... }
```

Observed consequence: after an ocr-service build created 16 pull records, the
post-build pass deleted **2** (the build's own lease had ended) and left 14
(49 GB) held by resolve leases. A manual `buildctl prune` two minutes later —
which takes the `CancelCacheLeases()` path — removed all 14. A daemon restart
does the same thing by dropping the in-memory slice, which is why restarts
appeared to "fix" the cache.

## What changed

| commit | change |
|---|---|
| `487f1e3` | pin base images **by omission**: name the cheap-to-recreate categories, leave maven/temurin/nginx/dockerfile matching no rule; final 60 GB backstop |
| `781417e` | `ocr-service/Dockerfile`: one `COPY app/ /srv/app` + `--app-dir` instead of `WORKDIR` + `rm -rf` + `COPY` (3 snapshots → 1) |
| `55badf5` | hourly `buildkit-prune` CronJob — the only thing that calls the lease-releasing prune path on a schedule |
| `fa0982d` | CI trims registry tags to the 2 newest per repo after each push |
| `f49b65e` | `registry-gc.sh` drops dangling tags and defaults to `--keep=2` |
| `e18530a` | hourly `registry-gc` CronJob using read-only mode |
| `0528b62`, `c265ef1`, `dabdeab` | read-only env format, build-in-progress guard, dry-run gate fix |

The live rules (`k8s/ci/buildkit.yaml`):

| rule | category | limit |
|---|---|---|
| `description~=registry\.pfa\.svc\.cluster\.local` | ocr-base pulls | `keepDuration = "0s"` |
| `description~=^mount` | `mount / from exec …` | 48 h |
| `description~=^\['` | `[n/m]` stage layers | 48 h |
| `description~=docker\.io/nvidia` | cuda | 336 h |
| *(no filter)* | backstop | `keepBytes = "60GB"` |

## Result

```
buildkit cache   129.06 GB logical / 100.6 GB disk   →   5.89 GB
registry data     23.7 GB                             →   6.8 GB
root disk         163 GB free (66 %)                  →  ~190 GB free (60 %)
```

What remains is base images only: maven 3.9.6 (4.48 GB), maven 3.9.9
(1.38 GB), dockerfile frontend (0.04 GB).

## Diagnosing it next time

```sh
# what is in the cache, grouped by source
kubectl -n pfa exec deploy/buildkitd -- buildctl --addr tcp://127.0.0.1:1234 du -v

# which policy the daemon actually loaded
kubectl -n pfa exec deploy/buildkitd -- \
  buildctl --addr tcp://127.0.0.1:1234 debug workers --format '{{json .}}' | jq '.[0].gcPolicy'

# what a prune *would* touch (read-only)
kubectl -n pfa exec deploy/buildkitd -- \
  buildctl --addr tcp://127.0.0.1:1234 du -f 'description~=registry\.pfa'

# registry side
./k8s/ci/registry-gc.sh --dry-run
```

## Gotchas worth remembering

- **`buildctl prune` with no flags deletes every record.** The client sends
  `KeepDuration=0, KeepBytes=0, no filter`. There is no RPC for "apply the
  configured gcpolicy", which is why the CronJob spells the categories out.
- **Filters containing spaces must be quoted inside the expression**, e.g.
  `description~="^mount / from exec"`, or the parser fails.
- **A record type that matches no rule is never deleted**, which is why the
  inverted "name the junk" policy exists.
- **`local-path` enforces nothing.** Both PVCs can exceed their claims.
- **`registry:2.8.3` read-only mode needs the whole map**:
  `REGISTRY_STORAGE_MAINTENANCE_READONLY='{enabled: true}'`. `=true` and
  `..._ENABLED=true` both panic with *"readonly config key must contain
  additional keys"*.

## Base images now live in the registry (2026-11-07)

The base images were still being re-pulled on most builds: the only rule that
can remove a `pulled from docker.io/...` record is the 60 GB LRU backstop, and
with the native snapshotter (a full rootfs per layer) the cache crosses it
during a large build. The cached records for `maven:3.9.9` and
`eclipse-temurin:21-jre-alpine` were gone minutes after each build, so every
build re-downloaded them.

Rather than tune the cache further, the base images are copied into the
in-cluster registry and the Dockerfiles pull from there:

- `k8s/ci/mirror-base-images.sh` copies maven 3.9.6/3.9.9, the two temurin 21
  images and nginx:alpine to `base/*` with `crane` (linux/amd64 only).
- The Dockerfiles take `ARG BASE_IMAGE_REGISTRY=docker.io/library` and CI
  passes `$PUSH_REGISTRY/base`, so a cold cache costs a loopback pull.
- The ocr-base GC rule now matches `registry.pfa.svc.cluster.local:5000/finance/ocr-base`
  instead of the bare host, and `registry-gc.sh` trims tags only under
  `finance/` — otherwise the mirror would be deleted by the same machinery.

That makes a cold cache cheap; it does not stop the cache from being
reclaimed, so the snapshotter item below still matters for build speed.

## Still open

- An `ocr-service` build still materialises ~71 GB of unpacked layers plus a
  ~11 GB build snapshot **during** the build; the cron removes them afterwards.
  Ending that needs `--oci-worker-snapshotter=overlayfs` or `fuse-overlayfs`
  (buildkit's selection order is overlayfs → fuse-overlayfs → native).
- The GC policy is duplicated: `buildkit.yaml` for the daemon's own pass, the
  `buildkit-prune` CronJob for what actually takes effect. They must be edited
  together.
