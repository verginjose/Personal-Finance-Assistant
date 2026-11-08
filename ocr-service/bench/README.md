# OCR pod benchmark

Measures the OCR service's real pipeline inside the running pod: PaddleOCR
inference, thread-safety of the shared engine, the Groq call on real OCR text,
and the resulting per-receipt throughput.

## OCR text endpoint

`POST /ocr` (in `app/main.py`) takes a multipart image upload and returns the
text PaddleOCR produced — no Groq, no MinIO, no queue:

```bash
kubectl -n pfa port-forward svc/ocr-service 8100:8100     # terminal 1
python3 ocr_client.py ../../requests/large-receipt-image-dataset-SRD/1001-receipt.jpg   # terminal 2
```

Answer:

```json
{"filename":"1001-receipt.jpg","content_type":"image/jpeg","width":645,"height":1000,
 "text":"GRAND LUXCAFE\nRoosevelt Field\n...","line_count":32,"chars":383,
 "inference_ms":118.2}
```

`--boxes` also returns per-line bounding boxes and confidences; `--json` prints
the raw response. Concurrency is safe: every inference (endpoint and RabbitMQ
alike) goes through a `threading.Lock`, because PaddleOCR predictors fail when
called from two threads at once.

## Batch: run N receipts against the endpoint

```bash
python3 run_ocr_batch.py 20                      # random 20 from the dataset
python3 run_ocr_batch.py 50 --seed 7 --concurrency 4
python3 run_ocr_batch.py 10 --first --boxes --out results/batch.json
python3 run_ocr_batch.py 100 --folder /path/to/receipts
```

Picks N images from `--folder` (default the repo's 200-receipt dataset), POSTs
each to `/ocr`, prints one line per receipt, and finishes with p50/p95 OCR and
roundtrip latency plus receipts/minute. `--out` saves every response (including
the full OCR text) to JSON. Exit code is 1 if any upload failed.


## Run

```bash
cd ocr-service/bench
./run-bench.sh
```

Options (environment variables):

| Var | Default | Meaning |
|-----|---------|---------|
| `COUNT` | `6` | number of receipts to test |
| `REPEATS` | `2` | OCR passes per image |
| `GROQ_RUNS` | `3` | Groq calls to time |
| `POD` | auto | target pod (otherwise the `app=ocr-service` pod) |
| `NS` | `pfa` | namespace |
| `IMAGES_DIR` | `../../requests/large-receipt-image-dataset-SRD` | receipt images |
| `OUT_DIR` | `bench/results` | where the result JSON is saved |

Example: `COUNT=20 POD=ocr-service-595c44b85b-rvltp ./run-bench.sh`

The script copies the images and `bench.py` into `/tmp/bench` in the pod, runs
the benchmark there (so it uses the pod's GPU, models and env), and copies
`result.json` back.

## What it reports

- `engine_init_s` — PaddleOCR startup cost (relevant to pod cold start).
- `ocr_sequential.stats` — min / p50 / p95 / mean / max OCR time, and
  `receipts_per_min_serial` for OCR alone.
- `ocr_concurrent` — what happens when two/four OCR calls run on the shared
  engine from threads. PaddleOCR predictors are not thread-safe, so expect
  failures; this documents the ceiling.
- `groq` / `groq_stats` — Groq latency on OCR'd text, and whether the model
  returned valid JSON.
- `pipeline_estimate` — `ocr + groq` per receipt and receipts/minute for one
  replica. The RabbitMQ consumer processes jobs one at a time, so this is
  also the per-replica throughput ceiling.

## Notes

- Run this against an idle pod. It loads a second PaddleOCR instance in the
  pod's GPU; on a 6 GB card this co-exists fine, but don't run it under load.
- Groq must be called with `httpx` (as the service does). Python `urllib` is
  refused by Cloudflare with `403 error code: 1010`.
- Images are copied to `/tmp/bench` and left there; `kubectl exec ... rm -rf
  /tmp/bench` cleans up.
