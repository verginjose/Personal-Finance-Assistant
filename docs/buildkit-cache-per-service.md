# BuildKit cache cost per service

Measured 2026-11-07 against the live cluster, during a full
`build-and-deploy` run (`workflow_dispatch`, `service=all`, run `42338297848`,
commit `55badf5`). Companion to
[`requests/BUILDKIT-CACHE-INFLATION.md`](../requests/BUILDKIT-CACHE-INFLATION.md),
which explains *why* the numbers look like this.

Use this as the baseline for "roughly how much disk does a build of service X
add to buildkitd" when sizing the cache or investigating a spike.

## How to read these numbers

- Cache was at a clean **5.89 GB / 16 records** (base images only) before the
  run, thanks to the manual `buildkit-prune` job.
- Samples are **30 s apart** from `buildctl du`; the build matrix runs
  **max-parallel: 1**, so job windows do not overlap.
- "Peak" is the highest sample inside the job window. "After GC" is the value
  once the automatic post-build GC has run (~1 s after the build finishes).
- **Base images are counted in the window that first pulled them.** maven 3.9.9
  was already cached at baseline; maven 3.9.6 is charged to `command-service`;
  `eclipse-temurin:21-jdk-jammy` is charged to `query-service`; `nginx:alpine`
  to `frontend`; the 16-layer ocr-base to `ocr-service`.
- `native` snapshotter stores **a full copy of the rootfs per layer**, so a
  step that changes 620 KB still costs ~550 MB on the maven builder, and ~11 GB
  on the ocr image. Layer count therefore matters more than delta size.

## Per-service summary

| service | build job | records | cache at peak | cache after GC | notable cost |
|---|---|---|---|---|---|
| auth-service | 2m06s | +22 (16→38) | 12.31 GB (+6.4) | 12.31 GB | maven 3.9.9 builder, 5 builder steps + 2 runner steps |
| api-gateway | 47s | +6 (38→44) | 16.08 GB (+3.8) | 16.08 GB | same shape as auth, but `mvn clean package` runs the tests |
| command-service | 67s | +5 (44→49) | 17.96 GB (+1.9) | 17.96 GB | first pull of maven **3.9.6** (9 layers) |
| query-service | 69s | +4 (49→59) | 21.75 GB (+3.8) | 24.36 GB (+6.4) | runtime base is **jdk-jammy**, not jre-alpine — the expensive one |
| frontend | 25s | +13 (59→74) | 24.95 GB (+0.6) | 24.95 GB | nginx:alpine + 6 tiny layers; cheap, but many records |
| ocr-service | 1m40s | +17 (74→91) | **107.10 GB (+82.2)** | **54.43 GB** | 16-layer ocr-base pull (~49 GB) + ~11 GB build snapshot |

Totals for the full run: **peak 107.10 GB / 91 records**, settling to
**54.43 GB / 33 records** after the automatic post-build GC. The remaining
54 GB is ocr-base and build-result records inside their `keepDuration`; only an
explicit idle prune removes them.

## Raw samples

```
04:27:19  38 rec  12.31 GB   (auth ends 04:27:36)
04:27:49  41 rec  13.76 GB   (api-gateway 04:27:39-04:28:26)
04:28:19  44 rec  16.08 GB
04:28:49  47 rec  16.90 GB   (command 04:28:29-04:29:36)
04:29:19  49 rec  17.96 GB
04:29:49  57 rec  19.46 GB   (query 04:29:39-04:30:48)
04:30:20  59 rec  21.75 GB
04:30:50  61 rec  24.36 GB   (frontend 04:30:52-04:31:17)
04:31:20  74 rec  24.95 GB   (ocr 04:31:21-04:33:01)
04:31:50  90 rec  42.03 GB
04:32:20  90 rec  52.42 GB
04:32:51  91 rec 107.10 GB   <- peak
04:33:21  33 rec  54.43 GB   <- post-build GC, build ended 04:33:02
```

## What each Dockerfile contributes

### auth-service — `maven:3.9.9` → `eclipse-temurin:21-jre-alpine`

```
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests
WORKDIR /app                       (runner)
COPY --from=builder .../jar .      (runner)
```

5 builder snapshots + 2 runner snapshots. The `~/.m2` cache mount is **not** a
layer (it lives in the cache volume as an `exec.cachemount` record, ~325 MB
shared across all services).

### api-gateway — identical shape to auth-service

Same 7 instructions, same bases. Difference: `mvn clean package` without
`-DskipTests`, so the `RUN` layer hash differs from auth's even though the
rootfs it stores is the same size.

### command-service — `maven:3.9.6` → `eclipse-temurin:21-jre-alpine`

Same 7 instructions. Its cache delta is small **only because it had already
been built in the baseline run**? No — measured here it is the first pull of
maven 3.9.6, which costs 9 layers; the build steps themselves cost ~1.5 GB.

### query-service — `maven:3.9.6` → `eclipse-temurin:21-jdk-jammy`

The only service whose runtime base is the **full JDK on jammy** rather than
JRE-on-alpine. That pull is why +4 records account for +4.9 GB. If cache size
matters, moving this to `21-jre-alpine` (as auth/command/api-gateway use) is
the single cheapest saving.

### frontend — `nginx:alpine`

```
COPY nginx.conf
COPY index.html
COPY css/
COPY js/
COPY assets/
RUN find ... -exec sed -i ...
```

6 records, ~0.6 GB total. The `RUN sed` bakes `$(date +%s)` into the HTML/JS,
so that layer is **never reusable** — every frontend build creates a fresh
~small layer.

### ocr-service — `finance/ocr-base:cu126-py311-v1`

```
COPY app/ /srv/app
```

One instruction by design (commit `781417e`). Everything expensive is the
**pull**: ocr-base is 6.52 GB compressed / 16 layers, and the native
snapshotter stores each layer as a full unpacked rootfs — ~49 GB observed,
~71 GB when the layers are counted cumulatively during the build. Add the
build snapshot and the peak is ~82 GB above the pre-build cache.

Rebuilding `Dockerfile.full` itself is a separate, much larger operation (see
the file header): paddlepaddle-gpu alone is ~12 GB on disk under `native`.

## Reproducing a measurement

```sh
# baseline
kubectl -n pfa exec deploy/buildkitd -- \
  buildctl --addr tcp://127.0.0.1:1234 du | tail -2

# one service (workflow_dispatch, builds only that image)
gh workflow run build.yml -f service=auth-service

# sample while it runs, every 30s
while true; do
  kubectl -n pfa exec deploy/buildkitd -- \
    buildctl --addr tcp://127.0.0.1:1234 du | grep -E '^(Reclaimable|Total):'
  sleep 30
done

# afterwards, reclaim what the automatic GC left behind
kubectl -n pfa create job --from=cronjob/buildkit-prune buildkit-prune-manual-$(date +%s)
```

To attribute time windows to services, get the matrix job boundaries:

```sh
gh run view <run-id> --json jobs \
  -q '.jobs[] | "\(.name)\t\(.startedAt)\t\(.completedAt)"'
```

## Rules of thumb

| build | cache added (steady state) | peak during build |
|---|---|---|
| Spring service (maven, jre-alpine) | ~1–3 GB | ~4–7 GB |
| query-service (jdk-jammy runtime) | ~5–6 GB | ~7 GB |
| frontend | <1 GB | <1 GB |
| ocr-service | ~50 GB until the next prune | ~80–110 GB |
| full `service=all` run | ~50 GB | ~110 GB |

The steady-state number is what an **idle prune** leaves behind immediately
after the build; the peak is what the disk has to survive while the build is
in flight. The 60 GB backstop is above the steady state but well below the
peak — that gap is why the CI reclaim step and the 15-minute CronJob exist.
