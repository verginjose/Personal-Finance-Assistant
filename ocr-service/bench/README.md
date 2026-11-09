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


## Deterministic parse (no LLM)

`parse_receipts.py` replaces the Groq extraction step: it uploads images to
`/ocr`, then parses the returned text into the same document Groq was asked
for in `app/main.py` —

```json
{"vendor":"...","amount":0.0,"transactionType":"Expense",
 "expenseCategory":"...","incomeCategory":null,"currency":"USD",
 "description":"...","date":"YYYY-MM-DD","recurring":false}
```

It also computes the derived `amountInr` / `amountUsd` / `amountDefault` fields
the same way `_parse_bill` does (Frankfurter rates, with an offline fallback),
and reports per-receipt evidence (which line produced the amount/date) plus
sanity warnings (`no_amount`, `no_date`, `currency_defaulted`, …).

```bash
INTERNAL_API_TOKEN=... python3 parse_receipts.py --count 20        # random sample
python3 parse_receipts.py --first 10 --default-currency USD        # first 10
python3 parse_receipts.py ../../requests/large-receipt-image-dataset-SRD/1001-receipt.jpg
python3 parse_receipts.py --results results/batch.json             # re-parse saved OCR
python3 parse_receipts.py --count 50 --labels labels.json --out results/parsed.json
```

| Flag | Meaning |
|------|---------|
| `--token` | `X-Internal-Token` (default `$INTERNAL_API_TOKEN`); `/ocr` requires it |
| `--default-currency` | currency assumed when none is printed (default `INR`; use `USD` for this dataset) |
| `--date-order auto\|mdy\|dmy` | ambiguous numeric dates; `auto` prefers month-first unless day > 12 |
| `--labels` | JSON/CSV ground truth keyed by filename; reports per-field accuracy and mismatches |
| `--no-convert` | skip the currency-rate fetch and derived amount fields |
| `--strict` | exit 1 if any receipt has a parse warning |
| `--include-text` | keep the OCR text in the saved JSON |

`--results` accepts the JSON written by `run_ocr_batch.py` (or a single `/ocr`
response), so parsing can be tuned offline against a saved batch.

This is heuristic by design: it reads a total-priority keyword list, a
date-format list, and a vendor/category keyword table. It is fast, free and
reproducible, and its mistakes are visible in `evidence` — unlike a Groq call,
which returns no trace of where a value came from.
Negative totals are treated as Income with positive document/conversion amounts.
Final labelled totals supersede earlier totals within the same priority, clock
values and percentages are excluded, and nearby cash/tax labels limit lookahead.


## Hybrid category search

Enable the vector fallback with reviewed examples:

```bash
python3 -I ocr-service/bench/parse_receipts.py \
  --results /tmp/parser-baseline-200.json --no-convert --default-currency USD \
  --category-examples ocr-service/bench/category_examples.json \
  --include-text --out /tmp/parser-hybrid-200.json
```

Run from the repository root, or adjust the paths. The default backend is
stdlib TF-IDF vectors with cosine similarity: **lexical similarity, not semantic
embeddings**. It makes no network calls. The bundled corpus contains starter
category descriptions; replace or extend it with reviewed vendor/item examples:

```json
[{"id":"reviewed-example-001","category":"RESTAURANTS","text":"Acme crepe croissant nutella"}]
```

Categories currently supported by the parser's category-rule table are accepted.
Use the receipt filename as `id` when indexing benchmark receipts: the current
receipt's matching ID is excluded from search. Keep evaluation receipts/vendors
out of the index when measuring generalization; do not auto-label the index with
parser predictions.

Rules take precedence; only Expense/OTHERS results reach vector search. The top
example must meet both `--category-threshold` (default 0.45) and the margin over
the best *different category* (`--category-margin`, default 0.12). Otherwise the
result stays OTHERS. Scores are not probabilities. Evidence includes the backend,
index SHA-256, neighbors, scores, margin and acceptance decision. These starter
thresholds require validation on a separate labelled, mixed-category dataset.

For local semantic embeddings, install `sentence-transformers` separately and
pass `--category-model /absolute/path/to/model-directory`. This uses CPU,
normalized embeddings and cosine similarity; the model must already exist
locally. It disables remote model code and network model downloads. Pin the model
files as well as the example corpus for repeatable runs. No OCR-service dependency
or deployed service path is changed. This optional backend has not been executed
in the current environment (the dependency/model are absent). See the
[SentenceTransformer API](https://sbert.net/docs/package_reference/sentence_transformer/model.html)
for model loading and normalization options.

Verification:

```bash
python3 -I -m unittest discover -s ocr-service/bench/tests -v
```

The tests cover 48 selected real OCR snapshots with manually reviewed expected
fields, signed refunds, payment/clock/percentage confusion, vector acceptance,
abstention, rule precedence and self-match exclusion. This is a targeted
regression suite, not an unbiased accuracy estimate.

The 2026-11-09 live 200-receipt OCR replay is summarized in
`tests/fixtures/tuning_report.json`: date coverage 172 → 184, detected currency
135 → 141, warning-free receipts 113 → 123; 28 amounts changed after reviewing
OCR evidence. All 200 still have an amount. The sparse fallback abstained on the
five remaining OTHERS; no category accuracy improvement is claimed from this
restaurant-only benchmark. Amounts and categories need image-labelled ground
truth before reporting overall accuracy.

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
