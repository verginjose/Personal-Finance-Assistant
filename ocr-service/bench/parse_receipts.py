#!/usr/bin/env python3
"""Parse receipt OCR text without an LLM.

This uses the production deterministic extraction rules in
``ocr-service/app/receipt_parser.py``. It
uploads images to the service's ``POST /ocr`` endpoint, then turns the returned
text into the production receipt document:

    {"vendor", "amount", "transactionType", "expenseCategory",
     "incomeCategory", "currency", "description", "date", "recurring"}

and, like ``_parse_bill``, the derived ``amountInr`` / ``amountUsd`` /
``amountDefault`` / ``defaultCurrency`` fields.

Examples
--------
Port-forward the OCR service first, then:

    kubectl -n pfa port-forward svc/ocr-service 8100:8100

    # 20 random receipts from the bundled dataset
    INTERNAL_API_TOKEN=... python3 parse_receipts.py --count 20

    # specific images, with the token on the command line
    python3 parse_receipts.py a.jpg b.jpg --token "$INTERNAL_API_TOKEN"

    # first 10 by filename, save the parsed documents
    python3 parse_receipts.py --first 10 --out results/parsed.json

    # re-parse responses already saved by run_ocr_batch.py (no service needed)
    python3 parse_receipts.py --results results/batch.json

    # score against a labelled set: {"1001-receipt.jpg": {"vendor": ...}}
    python3 parse_receipts.py --count 50 --labels labels.json

Only the Python standard library is used.
"""
import argparse
import concurrent.futures as cf
import csv
import datetime as dt
import json
import mimetypes
import os
import random
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_FOLDER = os.path.normpath(os.path.join(HERE, "..", "..",
                                               "requests", "large-receipt-image-dataset-SRD"))
EXT = (".jpg", ".jpeg", ".png", ".webp", ".bmp")
RATES_URL = "https://api.frankfurter.dev/v1/latest?from=USD"
FALLBACK_RATES = {"USD": 1.0, "INR": 83.0, "EUR": 0.92, "GBP": 0.79,
                  "CAD": 1.36, "AUD": 1.52, "SGD": 1.34, "AED": 3.67}

# Use the exact same extraction rules as the production service.
APP_DIR = os.path.normpath(os.path.join(HERE, "..", "app"))
sys.path.insert(0, APP_DIR)
from receipt_parser import *

def fetch_rates(timeout=8):
    try:
        with urllib.request.urlopen(RATES_URL, timeout=timeout) as r:
            body = json.load(r)
        rates = dict(body.get("rates", {}))
        rates["USD"] = 1.0
        return rates, body.get("date", ""), "frankfurter"
    except Exception:
        return dict(FALLBACK_RATES), "", "fallback"


# ── OCR endpoint / input handling ────────────────────────────────────────────

def post_image(url, path, token=None, timeout=180):
    boundary = "----parse" + uuid.uuid4().hex
    filename = os.path.basename(path)
    ctype = mimetypes.guess_type(filename)[0] or "image/jpeg"
    with open(path, "rb") as f:
        data = f.read()
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
        f"Content-Type: {ctype}\r\n\r\n".encode(),
        data,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    headers = {"Content-Type": f"multipart/form-data; boundary={boundary}"}
    if token:
        headers["X-Internal-Token"] = token
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return {"image": filename, "http": r.status,
                    "roundtrip_ms": round((time.perf_counter() - t) * 1000, 1),
                    "body": json.load(r)}
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"raw": raw[:500]}
        return {"image": filename, "http": e.code,
                "roundtrip_ms": round((time.perf_counter() - t) * 1000, 1),
                "error": payload}
    except Exception as e:
        return {"image": filename, "http": 0,
                "roundtrip_ms": round((time.perf_counter() - t) * 1000, 1),
                "error": repr(e)[:300]}


def list_images(folder):
    try:
        names = sorted(os.listdir(folder))
    except OSError as e:
        sys.exit(f"Cannot read folder {folder}: {e}")
    return [os.path.join(folder, n) for n in names if n.lower().endswith(EXT)]


def load_results(path):
    """Load saved /ocr responses in the shapes the bench scripts emit."""
    with open(path) as f:
        data = json.load(f)
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and isinstance(data.get("results"), list):
        return data["results"]
    if isinstance(data, dict) and "text" in data:
        return [data]
    if isinstance(data, dict):
        # filename -> response mapping
        out = []
        for name, value in data.items():
            if isinstance(value, dict):
                value = dict(value)
                value.setdefault("image", name)
                out.append(value)
        return out
    sys.exit(f"Unrecognised results file: {path}")


def load_labels(path):
    if not path:
        return {}
    if path.lower().endswith(".json"):
        with open(path) as f:
            data = json.load(f)
        if isinstance(data, list):
            return {str(d.get("image") or d.get("filename")): d for d in data}
        return {str(k): v for k, v in data.items()}
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    return {str(r.get("image") or r.get("filename")): r for r in rows}


def _norm_vendor(value):
    return re.sub(r"[^a-z0-9]+", " ", (value or "").lower()).strip()


def _same(field, got, want):
    if field == "amount":
        try:
            return abs(float(got) - float(want)) <= 0.01
        except (TypeError, ValueError):
            return False
    if field == "recurring":
        return str(got).lower() == str(want).lower()
    if field == "vendor":
        a, b = _norm_vendor(got), _norm_vendor(want)
        if a == b:
            return True
        return bool(a and b and (a in b or b in a))
    return str(got or "").strip().lower() == str(want or "").strip().lower()


EVAL_FIELDS = ("vendor", "amount", "transactionType", "expenseCategory",
               "currency", "date", "recurring")


def evaluate(results, labels):
    stats = {f: {"correct": 0, "total": 0} for f in EVAL_FIELDS}
    mismatches = []
    for entry in results:
        key = entry.get("image")
        want = labels.get(key)
        if not want:
            continue
        got_doc = entry.get("parsed") or {}
        row = {"image": key}
        for field in EVAL_FIELDS:
            if field not in want or want[field] in ("", None):
                continue
            stats[field]["total"] += 1
            if _same(field, got_doc.get(field), want[field]):
                stats[field]["correct"] += 1
            else:
                row[field] = {"want": want[field], "got": got_doc.get(field)}
        if len(row) > 1:
            mismatches.append(row)
    out = {}
    for field, s in stats.items():
        if s["total"]:
            out[field] = {"correct": s["correct"], "total": s["total"],
                          "accuracy": round(s["correct"] / s["total"], 3)}
    return {"fields": out, "mismatches": mismatches}


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("images", nargs="*", help="receipt image paths")
    ap.add_argument("--folder", default=DEFAULT_FOLDER,
                    help="dataset folder (default: repo's 200-receipt dataset)")
    ap.add_argument("--count", type=int, default=10,
                    help="random sample size when no images are named (default 10)")
    ap.add_argument("--first", action="store_true",
                    help="take the first N by filename instead of a random sample")
    ap.add_argument("--seed", type=int, default=None, help="random seed")
    ap.add_argument("--url", default="http://localhost:8100",
                    help="OCR service base URL (default http://localhost:8100)")
    ap.add_argument("--token", default=os.getenv("INTERNAL_API_TOKEN"),
                    help="X-Internal-Token value (default: $INTERNAL_API_TOKEN)")
    ap.add_argument("--results", default=None,
                    help="parse saved /ocr responses instead of uploading")
    ap.add_argument("--labels", default=None,
                    help="JSON/CSV ground truth keyed by image filename")
    ap.add_argument("--concurrency", type=int, default=1,
                    help="parallel uploads (the service serializes OCR internally)")
    ap.add_argument("--date-order", choices=("auto", "mdy", "dmy"), default="auto",
                    help="order for ambiguous numeric dates (default auto -> mdy)")
    ap.add_argument("--default-currency", default="INR",
                    help="currency assumed when none is printed (default INR; "
                         "this US receipt dataset usually wants USD)")
    ap.add_argument("--target-currency", default="INR",
                    help="default currency for derived amounts (default INR)")
    ap.add_argument("--no-convert", action="store_true",
                    help="skip amountInr/amountUsd derived fields (no network)")
    ap.add_argument("--include-text", action="store_true",
                    help="include the OCR text in the saved JSON")
    ap.add_argument("--strict", action="store_true",
                    help="exit 1 if any receipt has a parse warning")
    ap.add_argument("--json", action="store_true", help="print the full JSON result")
    ap.add_argument("--out", default=None, help="write full results JSON here")
    ap.add_argument("--category-examples", help="reviewed category example JSON; enables vector fallback")
    ap.add_argument("--category-model", help="optional local SentenceTransformer model directory")
    ap.add_argument("--category-threshold", type=float, default=0.45)
    ap.add_argument("--category-margin", type=float, default=0.12)
    args = ap.parse_args()
    category_search = None
    if args.category_model and not args.category_examples:
        ap.error("--category-model requires --category-examples")
    if args.category_examples:
        # Also works under python -I, which omits the script directory.
        import importlib.util
        spec = importlib.util.spec_from_file_location("category_search", os.path.join(APP_DIR, "category_search.py"))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        try:
            category_search = module.CategorySearch(
                args.category_examples, {name for name, _ in CATEGORY_RULES},
                args.category_threshold, args.category_margin, args.category_model)
        except (ValueError, OSError, ImportError) as exc:
            ap.error(str(exc))

    # Like run_ocr_batch.py, a bare number means "sample this many".
    if not args.results and len(args.images) == 1 and args.images[0].isdigit():
        args.count = int(args.images[0])
        args.images = []

    if args.results:
        raw = load_results(args.results)
    else:
        if args.images:
            chosen = args.images
        else:
            images = list_images(args.folder)
            if not images:
                sys.exit(f"No images found in {args.folder}")
            count = min(max(1, args.count), len(images))
            if args.first:
                chosen = images[:count]
            else:
                chosen = random.Random(args.seed).sample(images, count)
        endpoint = args.url.rstrip("/") + "/ocr"
        raw = []
        with cf.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as ex:
            futures = [ex.submit(post_image, endpoint, p, args.token) for p in chosen]
            for fut in cf.as_completed(futures):
                raw.append(fut.result())
        raw.sort(key=lambda r: r.get("image", ""))

    labels = load_labels(args.labels)
    rates, rates_date, rates_source = ({}, "", "disabled")
    if not args.no_convert:
        rates, rates_date, rates_source = fetch_rates()

    results, failed = [], 0
    for entry in raw:
        image = entry.get("image") or entry.get("filename") or "?"
        body = entry.get("body") or entry
        http = entry.get("http", 200 if body.get("text") is not None else 0)
        text = body.get("text")
        if http != 200 or text is None:
            failed += 1
            results.append({"image": image, "http": http,
                            "error": entry.get("error") or body.get("detail"),
                            "parsed": None})
            continue
        doc, evidence, warnings = parse_document(
            text, args.date_order, rates, args.target_currency,
            convert=not args.no_convert, default_currency=args.default_currency,
            category_search=category_search, example_id=image)
        item = {
            "image": image,
            "http": http,
            "ocr_ms": body.get("inference_ms"),
            "roundtrip_ms": entry.get("roundtrip_ms"),
            "lines": body.get("line_count"),
            "chars": body.get("chars", len(text)),
            "parsed": doc,
            "evidence": evidence,
            "warnings": warnings,
        }
        if args.include_text:
            item["text"] = text
        results.append(item)

    parsed = [r for r in results if r.get("parsed")]
    summary = {
        "picked": len(results),
        "ok": len(parsed),
        "failed": failed,
        "vendor_found": sum(1 for r in parsed if r["parsed"]["vendor"]),
        "amount_found": sum(1 for r in parsed if "no_amount" not in r["warnings"]),
        "date_found": sum(1 for r in parsed if r["parsed"]["date"]),
        "currency_detected": sum(
            1 for r in parsed if r["evidence"]["currency_source"] != "default"),
        "clean": sum(1 for r in parsed if not r["warnings"]),
        "warnings": sum(len(r["warnings"]) for r in parsed),
        "categories": {},
        "rates_source": rates_source,
        "rates_date": rates_date,
    }
    for r in parsed:
        cat = r["parsed"]["expenseCategory"] or r["parsed"]["incomeCategory"] or "?"
        summary["categories"][cat] = summary["categories"].get(cat, 0) + 1
    if parsed:
        amounts = [r["parsed"]["amount"] for r in parsed if r["parsed"]["amount"]]
        if amounts:
            summary["amount"] = {
                "min": min(amounts),
                "median": round(statistics.median(amounts), 2),
                "max": max(amounts),
            }

    evaluation = evaluate(results, labels) if labels else None
    output = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "config": {
            "url": args.url,
            "date_order": args.date_order,
            "default_currency": args.default_currency,
            "target_currency": args.target_currency,
            "convert": not args.no_convert,
            "category_search": ({"backend": category_search.backend,
                                 "index_sha256": category_search.fingerprint,
                                 "model": args.category_model,
                                 "threshold": args.category_threshold,
                                 "margin": args.category_margin} if category_search else None),
        },
        "summary": summary,
        "results": results,
    }
    if evaluation:
        output["evaluation"] = evaluation

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w") as f:
            json.dump(output, f, indent=2)

    if args.json:
        print(json.dumps(output, indent=2))
        return 1 if failed or (args.strict and summary["warnings"] > 0) else 0

    print(f"Source:    {'saved results ' + args.results if args.results else args.url}")
    print(f"Parsed:    {summary['ok']}/{summary['picked']} receipts"
          f"{', ' + str(failed) + ' failed' if failed else ''}")
    print()
    for r in results:
        if not r.get("parsed"):
            print(f"  {r['image']:<24} FAILED  {r.get('error')}")
            continue
        doc, ev = r["parsed"], r["evidence"]
        warn = ("  ! " + ",".join(r["warnings"])) if r["warnings"] else ""
        print(f"  {r['image']:<24} {str(doc['vendor'])[:24]:<24} "
              f"{doc['amount']:>9.2f} {doc['currency']}  "
              f"{str(doc['date']):<10} {str(doc['expenseCategory'] or doc['incomeCategory']):<18}"
              f"{warn}")
    print()
    n = max(1, summary["ok"])
    print(f"Found:     vendor {summary['vendor_found']}/{n}  "
          f"amount {summary['amount_found']}/{n}  "
          f"date {summary['date_found']}/{n}  "
          f"currency {summary['currency_detected']}/{n}")
    if summary.get("amount"):
        a = summary["amount"]
        print(f"Amounts:   min {a['min']:.2f}  median {a['median']:.2f}  max {a['max']:.2f}")
    if summary["categories"]:
        cats = ", ".join(f"{k}={v}" for k, v in
                         sorted(summary["categories"].items(), key=lambda x: -x[1]))
        print(f"Categories:{cats}")
    print(f"Warnings:  {summary['warnings']} across {summary['ok']} receipts "
          f"({summary['clean']} clean)")
    if not args.no_convert:
        print(f"Rates:     {summary['rates_source']}"
              f"{' (' + summary['rates_date'] + ')' if summary['rates_date'] else ''}")
    if evaluation and evaluation["fields"]:
        print()
        print("Accuracy vs labels:")
        for field, s in evaluation["fields"].items():
            print(f"  {field:<18} {s['correct']}/{s['total']}  {s['accuracy']:.1%}")
        if evaluation["mismatches"]:
            print(f"Mismatches: {len(evaluation['mismatches'])}")
            for row in evaluation["mismatches"][:10]:
                diffs = ", ".join(f"{k}: want={v['want']!r} got={v['got']!r}"
                                  for k, v in row.items() if k != "image")
                print(f"  {row['image']}: {diffs}")
    if args.out:
        print(f"Saved:     {args.out}")

    return 1 if failed or (args.strict and summary["warnings"] > 0) else 0


if __name__ == "__main__":
    sys.exit(main())
