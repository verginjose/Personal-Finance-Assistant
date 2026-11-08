#!/usr/bin/env python3
"""Pick N receipts from a folder and run them against the OCR service's /ocr endpoint.

    python3 run_ocr_batch.py 20
    python3 run_ocr_batch.py 50 --concurrency 4
    python3 run_ocr_batch.py 10 --first            # first 10 by name, not random
    python3 run_ocr_batch.py 30 --seed 7 --out results/batch.json

Port-forward the service first:

    kubectl -n pfa port-forward svc/ocr-service 8100:8100

Output per image is one line; the full OCR text of every image goes to the
JSON file. Exit code is non-zero if any upload failed.
"""
import argparse
import concurrent.futures as cf
import json
import mimetypes
import os
import random
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


def list_images(folder):
    try:
        names = sorted(os.listdir(folder))
    except OSError as e:
        sys.exit(f"Cannot read folder {folder}: {e}")
    return [os.path.join(folder, n) for n in names if n.lower().endswith(EXT)]


def post_image(url, path, boxes=False):
    boundary = "----ocrbatch" + uuid.uuid4().hex
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
    target = url + ("?boxes=true" if boxes else "")
    req = urllib.request.Request(
        target, data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return {"image": filename, "http": r.status,
                    "roundtrip_ms": round((time.perf_counter() - t) * 1000, 1),
                    "body": json.load(r)}
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        return {"image": filename, "http": e.code,
                "roundtrip_ms": round((time.perf_counter() - t) * 1000, 1),
                "error": raw[:300]}
    except Exception as e:
        return {"image": filename, "http": 0,
                "roundtrip_ms": round((time.perf_counter() - t) * 1000, 1),
                "error": repr(e)[:300]}


def p(values, q):
    s = sorted(values)
    return s[min(len(s) - 1, max(0, int(round(q / 100 * (len(s) - 1)))))]


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("count", type=int, help="how many receipts to pick")
    ap.add_argument("--folder", default=DEFAULT_FOLDER, help="receipt image folder")
    ap.add_argument("--url", default="http://localhost:8100", help="OCR service base URL")
    ap.add_argument("--first", action="store_true",
                    help="take the first N by filename instead of a random sample")
    ap.add_argument("--seed", type=int, default=None, help="random seed for reproducibility")
    ap.add_argument("--concurrency", type=int, default=1,
                    help="parallel uploads (the service serializes OCR internally)")
    ap.add_argument("--boxes", action="store_true", help="ask for line boxes too")
    ap.add_argument("--out", default=None, help="write full results JSON here")
    args = ap.parse_args()

    if args.count <= 0:
        sys.exit("count must be > 0")

    images = list_images(args.folder)
    if not images:
        sys.exit(f"No images found in {args.folder}")
    if args.count > len(images):
        print(f"Only {len(images)} images available; using all of them")
        args.count = len(images)

    if args.first:
        chosen = images[:args.count]
    else:
        rng = random.Random(args.seed)
        chosen = rng.sample(images, args.count)

    endpoint = args.url.rstrip("/") + "/ocr"
    print(f"Folder:     {args.folder}")
    print(f"Picked:     {len(chosen)} of {len(images)}"
          f"{' (random, seed=' + str(args.seed) + ')' if not args.first and args.seed is not None else ''}")
    print(f"Endpoint:   {endpoint}")
    print(f"Concurrency:{args.concurrency}")
    print()

    started = time.perf_counter()
    results = []
    with cf.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as ex:
        futures = [ex.submit(post_image, endpoint, pth, args.boxes) for pth in chosen]
        for i, fut in enumerate(cf.as_completed(futures), 1):
            r = fut.result()
            results.append(r)
            if r["http"] == 200:
                b = r["body"]
                print(f"[{i:>4}/{len(chosen)}] {r['image']:<22} 200 "
                      f"ocr={b['inference_ms']:>6.0f} ms  rt={r['roundtrip_ms']:>7.1f} ms  "
                      f"{b['line_count']:>3} lines  {b['chars']:>4} chars")
            else:
                print(f"[{i:>4}/{len(chosen)}] {r['image']:<22} {r['http']} "
                      f"{r.get('error', '')[:120]}")
    wall = time.perf_counter() - started

    ok = [r for r in results if r["http"] == 200]
    failed = [r for r in results if r["http"] != 200]
    rt = [r["roundtrip_ms"] for r in ok]
    ocr = [r["body"]["inference_ms"] for r in ok]
    lines = [r["body"]["line_count"] for r in ok]

    summary = {
        "picked": len(chosen),
        "ok": len(ok),
        "failed": len(failed),
        "wall_s": round(wall, 2),
        "throughput_per_min": round(len(ok) / wall * 60, 1) if wall else 0,
        "roundtrip_ms": {"p50": p(rt, 50), "p95": p(rt, 95), "mean": round(statistics.mean(rt), 1),
                         "max": max(rt)} if rt else None,
        "ocr_ms": {"p50": p(ocr, 50), "p95": p(ocr, 95), "mean": round(statistics.mean(ocr), 1),
                   "max": max(ocr)} if ocr else None,
        "lines": {"min": min(lines), "p50": p(lines, 50), "max": max(lines)} if lines else None,
    }
    print()
    print(f"Done: {len(ok)}/{len(chosen)} ok, {len(failed)} failed, "
          f"wall {wall:.1f}s, {summary['throughput_per_min']} receipts/min")
    if ocr:
        print(f"OCR: p50 {summary['ocr_ms']['p50']} ms  p95 {summary['ocr_ms']['p95']} ms  "
              f"mean {summary['ocr_ms']['mean']} ms")
        print(f"Roundtrip: p50 {summary['roundtrip_ms']['p50']} ms  "
              f"p95 {summary['roundtrip_ms']['p95']} ms")
    if failed:
        print("Failed:")
        for r in failed:
            print(f"  {r['image']}: HTTP {r['http']} {r.get('error', '')[:200]}")

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w") as f:
            json.dump({"started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                       "config": vars(args), "summary": summary,
                       "results": results}, f, indent=2)
        print(f"Saved: {args.out}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
