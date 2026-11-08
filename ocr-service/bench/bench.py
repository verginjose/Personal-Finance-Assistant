"""Benchmark the OCR service's real pipeline inside its pod.

Run it via ./run-bench.sh, or copy it into a pod and run:

    python3 -I bench.py --images /tmp/bench/*.jpg --out /tmp/bench/result.json

It mirrors the exact settings in ocr-service/app/main.py:
  PaddleOCR(use_angle_cls=OCR_USE_ANGLE_CLS, lang="en", use_gpu=True,
            det_db_thresh=0.3, det_db_box_thresh=0.5, rec_batch_num=6,
            det_limit_side_len=OCR_DET_LIMIT_SIDE_LEN)
  Groq payload: model=GROQ_MODEL, temperature=0.2,
               response_format={"type": "json_object"}

Groq is called with httpx, same as the service. Do NOT use urllib here:
Cloudflare answers urllib requests with "403 error code: 1010".
"""
import argparse
import json
import os
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import cv2
import httpx
import numpy as np
from paddleocr import PaddleOCR

GROQ_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL = os.getenv("GROQ_MODEL", "openai/gpt-oss-120b")
GROQ_URL = os.getenv("GROQ_URL", "https://api.groq.com/openai/v1/chat/completions")
ANGLE = os.getenv("OCR_USE_ANGLE_CLS", "false").lower() == "true"
DET_LEN = int(os.getenv("OCR_DET_LIMIT_SIDE_LEN", "736"))

# GROQ_PROMPT_TEMPLATE from ocr-service/app/main.py
GROQ_PROMPT_TEMPLATE = """Extract financial data from this receipt/document. Return ONLY valid JSON.

Rules:
- transactionType: "Expense" or "Income"
- expenseCategory: classify from the vendor and the line items actually on the receipt. RENT is only for a rent bill; a food or drink purchase is never RENT. Use "OTHERS" when the receipt is ambiguous rather than guessing.
- incomeCategory: Guess the closest (e.g., SALARY, FREELANCE, BUSINESS, REFUND) or use "OTHERS"
- Set the unused category to null
- currency: 3-letter ISO code (INR for ₹)
- date: Date of the bill in "YYYY-MM-DD" format, or null if not found
- recurring: true only for a named subscription or recurring bill (e.g. Netflix, rent, internet). A one-off purchase, even a repeated everyday item, is false.

JSON schema:
{{"vendor":"string","amount":0.0,"transactionType":"string","expenseCategory":"string|null","incomeCategory":"string|null","currency":"string","description":"string","date":"string|null","recurring":false}}

Receipt:
{text}
"""


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, max(0, int(round(p / 100 * (len(s) - 1)))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="+", help="receipt image paths")
    ap.add_argument("--out", default="/tmp/bench/result.json")
    ap.add_argument("--repeats", type=int, default=2, help="OCR passes per image")
    ap.add_argument("--groq-runs", type=int, default=3)
    args = ap.parse_args()

    out = {
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "host": os.uname().nodename,
        "settings": {"angle_cls": ANGLE, "det_limit_side_len": DET_LEN,
                     "groq_model": GROQ_MODEL},
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

    # ---- Groq on real OCR text (same client the service uses) ----
    _, sample_text = ocr_bytes(blobs[0][1])
    clean = " ".join(sample_text.split()).strip()
    prompt = GROQ_PROMPT_TEMPLATE.format(text=clean)
    groq_runs = []
    with httpx.Client(timeout=60.0) as client:
        for _ in range(args.groq_runs):
            t = time.perf_counter()
            try:
                r = client.post(GROQ_URL, json={
                    "model": GROQ_MODEL,
                    "temperature": 0.2,
                    "response_format": {"type": "json_object"},
                    "messages": [{"role": "user", "content": prompt}],
                }, headers={"Authorization": f"Bearer {GROQ_KEY}",
                            "Content-Type": "application/json"})
                dt = time.perf_counter() - t
                ok = False
                preview = r.text[:200]
                if r.status_code == 200:
                    content = r.json()["choices"][0]["message"]["content"]
                    preview = content.replace("\n", "")[:200]
                    try:
                        json.loads(content)
                        ok = True
                    except Exception:
                        ok = False
                groq_runs.append({"http": r.status_code, "latency_s": round(dt, 2),
                                  "valid_json": ok, "prompt_chars": len(clean),
                                  "preview": preview})
            except Exception as e:
                groq_runs.append({"error": repr(e)[:300]})
    out["groq"] = groq_runs
    lat = [g["latency_s"] for g in groq_runs if "latency_s" in g and g.get("http") == 200]
    if lat:
        out["groq_stats"] = {"n": len(lat), "p50_s": round(pct(lat, 50), 2),
                             "mean_s": round(statistics.mean(lat), 2)}
        # The MQ consumer awaits each job, so one replica is serial: OCR + Groq.
        e2e = out["ocr_sequential"]["stats"]["mean_ms"] / 1000 + statistics.mean(lat)
        out["pipeline_estimate"] = {
            "per_receipt_s": round(e2e, 2),
            "receipts_per_min_1_replica": round(60 / e2e, 1),
            "note": "MQ consumer processes jobs one at a time; PaddleOCR is not thread-safe",
        }

    with open(args.out, "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    sys.exit(main())
