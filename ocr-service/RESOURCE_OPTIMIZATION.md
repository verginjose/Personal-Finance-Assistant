# OCR resource profiles

The API and RabbitMQ consumer remain running, but Paddle is imported and models
are loaded only in a spawned worker when an image needs OCR. After five minutes
without inference, that worker exits and releases its Python/native allocations
and GPU context. CSV imports and text PDFs do not load it. The existing text
cache, parser/review rules, angle setting, detection size, and single-inference
serialization are preserved. RabbitMQ prefetch is one to avoid buffering a
backlog of image jobs during cold startup.

The existing Dockerfile and CI remain the GPU compatibility path. Nothing in
this change automatically switches the live deployment to a different engine.
The CPU image is a separate build because removing a GPU request from a CUDA
image would not remove its large runtime and dependencies.

## 1. CPU PaddleOCR

Build from the repository root:

```bash
docker build -f ocr-service/Dockerfile.cpu --target legacy \
  -t 127.0.0.1:30500/finance/ocr-service:local-cpu-legacy-v1 ocr-service
```

This uses PaddleOCR 2.8.1 with the CPU Paddle 3.2.1 runtime, English detector and
recognizer, two CPU threads, recognition batches of six, and detection side
length 736. The current GPU image retains its original dependencies. The CPU
profile does not bundle CUDA or paddlepaddle-gpu.

Push the image using the registry's existing mTLS setup before selecting it.
The optional Kubernetes profile removes the GPU limit and GPU toleration,
requests 100m CPU/256Mi memory, limits CPU to two cores and memory to 3Gi, and
retains the existing model PVC. Render it with:

```bash
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/optional/ocr-local
```

The relaxed loader is needed only because this overlay references the sibling
`apps` directory. Apply/promote the rendered configuration through the existing
GitOps workflow with a unique image tag. A direct live apply can be reverted by
Argo; do not release the existing pfa-apps reconciliation hold until the auth
hotfix and all desired deployment changes have been promoted.

## 2. PP-OCRv5 mobile comparison

```bash
docker build -f ocr-service/Dockerfile.cpu --target mobile \
  -t finance/ocr-service:cpu-mobile ocr-service
```

This separate image uses PaddleOCR 3.3.2/Paddle 3.2.1 with
`PP-OCRv5_mobile_det` and `en_PP-OCRv5_mobile_rec`. Document orientation and
unwarping are disabled. The adapter normalizes Paddle 3 predictions into the
same text/confidence/bounding-box contract as Paddle 2. To use this image with
the local Kubernetes profile, change the overlay's image tag and the explicit
Deployment `OCR_MODEL_PROFILE` override to `mobile-v5` together. An image ENV
alone cannot override that Deployment setting.

Paddle 2 and 3 are kept in different images; requesting an incompatible profile
produces an explicit error. Do not make mobile models the default solely from
published model-file sizes or synthetic benchmarks. Compare receipt fields and
review decisions using the same actual images first.

Official API/model reference:
https://www.paddleocr.ai/v3.3.0/en/version3.x/pipeline_usage/OCR.html

## 3. Lazy load and idle release

| Variable | Default | Purpose |
|---|---|---|
| `OCR_DEVICE` | `gpu` in the compatibility image, `cpu` in CPU images | Inference device |
| `OCR_MODEL_PROFILE` | `legacy` (`mobile-v5` in mobile image) | Choose adapter/model family |
| `OCR_CPU_THREADS` | `2` | Bound native inference threads |
| `OCR_REC_BATCH_NUM` | `6` | Recognition batch size |
| `OCR_IDLE_SECONDS` | `300` | Stop worker after inactivity; `0` keeps it loaded |
| `OCR_INFERENCE_TIMEOUT_SECONDS` | `180` | Bound each worker startup/inference wait |
| `OCR_PRELOAD` | `false` | Warm the worker during service startup |
| `OCR_ENABLE_MKLDNN` | `false` (`true` in mobile image) | Opt-in CPU acceleration; benchmark compatibility/memory first |
| `OCR_DET_MODEL_DIR`, `OCR_REC_MODEL_DIR` | unset | Use pre-provisioned local models |
| `OCR_BACKEND` | `paddle` | `tesseract` is an optional comparison baseline |

API health and queue readiness do not load models. A cold request includes
initialization time. Provision model files before an offline deployment: Paddle
2 uses `/root/.paddleocr`, Paddle 3 uses `/root/.paddlex` (both on the local
profile's existing PVC). Use `OCR_PRELOAD=true` in a preparation run to populate
and validate the cache, or set explicit model directories. A missing model in an
offline environment fails the OCR job; it does not fabricate a result. The
existing parking/callback handling remains in place. With a permanently offline
mobile deployment, `DISABLE_MODEL_SOURCE_CHECK=True` avoids host connectivity
checks; it does not supply missing model files.

The 180-second bound applies separately to initialization and inference, not to
the total HTTP request. Cache/preload models so download time is not part of
interactive processing. On a timeout/crash, the worker and its helper process
group are cleaned up; a subsequent request may start a fresh worker. Idle
unloading never interrupts an active inference. Kubernetes GPU allocation stays
reserved by a GPU pod even while its model process is unloaded; the CPU profile
removes that allocation entirely.

## 4. Optional Tesseract baseline

```bash
docker build -f ocr-service/Dockerfile.cpu --target tesseract \
  -t finance/ocr-service:tesseract ocr-service
```

This target contains Tesseract English and OpenCV, without Paddle. It groups TSV
words into lines and preserves bounding boxes. PSM 6 is a baseline rather than a
general receipt-layout solution. It must pass receipt accuracy tests before
being selected for normal processing; it is not an automatic fallback.

## Local comparison (2026-11-25)

Six available labelled receipt images supplied 30 field checks. The current GPU
service and CPU legacy profile each matched 26 fields and requested review for
four, with no silent wrong fields. This is a smoke sample, not a general accuracy
claim. The same parser and labels were used for every engine.

| Profile | Median warm latency | Matched / review / wrong | Worker peak RSS |
|---|---:|---:|---:|
| Current GPU service | 135 ms | 26 / 4 / 0 | Not measured separately |
| CPU legacy | 946 ms | 26 / 4 / 0 | 2320 MiB |
| CPU mobile v5, MKL-DNN enabled | 471 ms | 24 / 4 / 2 | 746 MiB |
| Tesseract baseline | 407 ms | 19 / 7 / 4 | 72 MiB, excludes Tesseract helper |

All managed profiles released their worker after the test idle timeout. Parent
benchmark RSS after unloading was about 76 MiB for CPU legacy; this is not a
measurement of the complete API pod. The CPU profile's 3Gi limit gives room for
its measured native allocation peak; a low request alone does not reduce usage.

Mobile misread one receipt total (15.03 as 20.00); Tesseract also produced wrong
totals. Both remain experimental and are not automatically selected. CPU legacy
preserved this sample's results but used more active RAM than the original
idle GPU pod snapshot. Prefer idle release on the GPU path if warm speed matters,
or CPU legacy when freeing the GPU is more useful. No live deployment was
changed for this comparison. Aggregate results are in
`bench/runtime-comparison-20261125.json`; the local source images were not added
to Git by this change.

## Repeatable accuracy and memory comparison

Run the same labelled images in each image. This calls no application write
endpoints and creates no transactions. Replace the model-cache mount with
`/root/.paddlex` for mobile; omit it for Tesseract. Use an absolute checkout path
for `REPO` and an output directory writable by the container:

```bash
REPO="$PWD"
mkdir -p /tmp/ocr-results /tmp/ocr-models
docker run --rm --cpus=2 --memory=2g \
  -v "$REPO:/workspace:ro" -v /tmp/ocr-results:/results \
  -v /tmp/ocr-models:/root/.paddleocr \
  127.0.0.1:30500/finance/ocr-service:local-cpu-legacy-v1 \
  python /workspace/ocr-service/bench/compare_runtime.py \
  --images /workspace/requests/large-receipt-image-dataset-SRD \
  --labels /workspace/ocr-service/bench/tests/fixtures/account_labels.json \
  --out /results/cpu-legacy.json
```

On an SELinux host, use the host's normal bind-mount labelling setup. The harness records cold
and warm latency, exact field matches, explicit review decisions, silent wrong
fields, processing errors, parent RSS, worker RSS/high-water mark, and confirmed
idle unloading. It exits nonzero on processing errors, silent wrong fields, or
a failed idle unload. Warm repeats bypass the API text cache. Missing source images
are listed; cached fixture OCR text never substitutes for an absent image.

RSS is process memory, not Kubernetes working set, image size, or GPU VRAM.
Tesseract's helper process peak is excluded from the worker RSS, so do not
compare that number directly with Paddle's process peak. A small sample is a
smoke comparison rather than proof of general accuracy. Review the wrong and
abstained fields individually before switching profiles.

Run all parser/PDF/lifecycle tests in an OCR image with the repository mounted:

```bash
python -m unittest discover -s /workspace/ocr-service/tests -v
python -m unittest discover -s /workspace/ocr-service/bench/tests -v
```
