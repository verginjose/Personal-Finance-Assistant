"""Benchmark the OCR service's real pipeline inside its pod.

Run it via ./run-bench.sh, or copy it into a pod and run:

    python3 -I bench.py --images /tmp/bench/*.jpg --out /tmp/bench/result.json

It mirrors the exact settings in ocr-service/app/main.py:
  PaddleOCR(use_angle_cls=OCR_USE_ANGLE_CLS, lang="en", use_gpu=True,
            det_db_thresh=0.3, det_db_box_thresh=0.5, rec_batch_num=6,
            det_limit_side_len=OCR_DET_LIMIT_SIDE_LEN)
  Extraction: shared local deterministic receipt parser.
"""
import argparse
import json
import os
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np
from paddleocr import PaddleOCR

import importlib.util
from pathlib import Path
parser_path = Path("/srv/app/receipt_parser.py")
if not parser_path.exists():
    parser_path = Path(__file__).resolve().parents[1] / "app/receipt_parser.py"
spec = importlib.util.spec_from_file_location("receipt_parser", parser_path)
parser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parser)

ANGLE = os.getenv("OCR_USE_ANGLE_CLS", "false").lower() == "true"
DET_LEN = int(os.getenv("OCR_DET_LIMIT_SIDE_LEN", "736"))

def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, max(0, int(round(p / 100 * (len(s) - 1)))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="+", help="receipt image paths")
    ap.add_argument("--out", default="/tmp/bench/result.json")
    ap.add_argument("--repeats", type=int, default=2, help="OCR passes per image")
    ap.add_argument("--parser-runs", type=int, default=3)
    args = ap.parse_args()

    out = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "host": os.uname().nodename,
        "settings": {"angle_cls": ANGLE, "det_limit_side_len": DET_LEN,
                     "parser": "deterministic"},
        "images": [os.path.basename(p) for p in args.images],
    }

    t0 = time.perf_counter()
    engine = PaddleOCR(use_angle_cls=ANGLE, lang="en", use_gpu=True,
                       det_db_thresh=0.3, det_db_box_thresh=0.5,
                       rec_batch_num=6, det_limit_side_len=DET_LEN,
                       show_log=False)
    out["engine_init_s"] = round(time.perf_counter() - t0, 2)

    def ocr_bytes(data):
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        t = time.perf_counter()
        res = engine.ocr(img, cls=ANGLE)
        ms = (time.perf_counter() - t) * 1000
        texts = [x[1][0] for x in (res[0] or [])] if res and res[0] else []
        return ms, "\n".join(texts)

    blobs = []
    for p in args.images:
        with open(p, "rb") as f:
            blobs.append((os.path.basename(p), f.read()))

    # Warmup: CUDA/cuDNN autotune happens on first calls and is not representative.
    ocr_bytes(blobs[0][1])
    ocr_bytes(blobs[0][1])

    # ---- OCR, sequential ----
    seq = []
    for name, data in blobs:
        for _ in range(args.repeats):
            ms, text = ocr_bytes(data)
            seq.append({"image": name, "ocr_ms": round(ms, 1), "chars": len(text)})
    vals = [r["ocr_ms"] for r in seq]
    out["ocr_sequential"] = {
        "runs": seq,
        "stats": {
            "n": len(vals),
            "min_ms": round(min(vals), 1),
            "p50_ms": round(pct(vals, 50), 1),
            "p95_ms": round(pct(vals, 95), 1),
            "mean_ms": round(statistics.mean(vals), 1),
            "max_ms": round(max(vals), 1),
            "receipts_per_min_serial": round(60000 / statistics.mean(vals), 1),
        },
    }

    # ---- OCR, concurrent on the shared engine ----
    # PaddleOCR predictors are not thread-safe; this records what actually
    # happens if jobs fan out inside one process.
    def timed(item):
        name, data = item
        try:
            ms, text = ocr_bytes(data)
            return {"image": name, "ok": True, "ocr_ms": round(ms, 1), "chars": len(text)}
        except Exception as e:
            return {"image": name, "ok": False, "error": repr(e)[:300]}

    conc = {}
    for k in (2, 4):
        batch = (blobs * 4)[:k]
        t = time.perf_counter()
        with ThreadPoolExecutor(max_workers=k) as ex:
            res = list(ex.map(timed, batch))
        wall = time.perf_counter() - t
        conc[f"k={k}"] = {
            "wall_s": round(wall, 2),
            "ok": sum(1 for r in res if r["ok"]),
            "failed": sum(1 for r in res if not r["ok"]),
            "results": res,
        }
    out["ocr_concurrent"] = conc

    # Benchmark the same local extraction function production calls.
    _, sample_text = ocr_bytes(blobs[0][1])
    runs = []
    for _ in range(args.parser_runs):
        started = time.perf_counter()
        doc, evidence, warnings = parser.parse_document(sample_text, convert=False)
        runs.append({"latency_s": time.perf_counter() - started,
                     "document": doc, "evidence": evidence, "warnings": warnings})
    out["parser"] = runs
    lat = [run["latency_s"] for run in runs]
    if lat:
        out["parser_stats"] = {"n": len(lat), "mean_s": statistics.mean(lat)}
        e2e = out["ocr_sequential"]["stats"]["mean_ms"] / 1000 + statistics.mean(lat)
        out["pipeline_estimate"] = {"per_receipt_s": round(e2e, 4),
                                     "receipts_per_min_1_replica": round(60 / e2e, 1)}

    with open(args.out, "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    sys.exit(main())
