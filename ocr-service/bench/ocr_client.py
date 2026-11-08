#!/usr/bin/env python3
"""Upload a receipt image to the OCR service's /ocr endpoint and print the text.

The endpoint lives inside the cluster, so port-forward it first:

    kubectl -n pfa port-forward svc/ocr-service 8100:8100
    python3 ocr_client.py ../../requests/large-receipt-image-dataset-SRD/1001-receipt.jpg

Then, on your machine in another terminal:

    python3 ocr_client.py path/to/receipt.jpg
    python3 ocr_client.py a.jpg b.jpg --url http://localhost:8100 --json
    python3 ocr_client.py a.jpg --boxes          # include line boxes + confidence

Only stdlib is used, so it runs without installing anything.
"""
import argparse
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid


def post_image(url, path, boxes):
    boundary = "----ocrbench" + uuid.uuid4().hex
    filename = os.path.basename(path)
    ctype = mimetypes.guess_type(filename)[0] or "image/jpeg"
    with open(path, "rb") as f:
        data = f.read()

    parts = [
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
        f"Content-Type: {ctype}\r\n\r\n".encode(),
        data,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    body = b"".join(parts)

    target = url + ("?boxes=true" if boxes else "")
    req = urllib.request.Request(
        target, data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 "Content-Length": str(len(body))},
    )
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            payload = json.load(r)
            return r.status, time.perf_counter() - t, payload
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"raw": raw}
        return e.code, time.perf_counter() - t, payload


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("images", nargs="+", help="receipt image files")
    ap.add_argument("--url", default="http://localhost:8100",
                    help="service base URL (default http://localhost:8100)")
    ap.add_argument("--boxes", action="store_true", help="include line boxes + confidence")
    ap.add_argument("--json", action="store_true", help="print the raw JSON response")
    args = ap.parse_args()

    endpoint = args.url.rstrip("/") + "/ocr"
    failures = 0
    for path in args.images:
        code, dt, body = post_image(endpoint, path, args.boxes)
        if args.json:
            print(json.dumps(body, indent=2))
            continue
        if code != 200:
            failures += 1
            print(f"[{code}] {path} — {dt:.2f}s")
            print(json.dumps(body, indent=2))
            continue
        print(f"== {os.path.basename(path)}  HTTP {code}  "
              f"ocr={body['inference_ms']:.0f} ms  roundtrip={dt * 1000:.0f} ms  "
              f"{body['width']}x{body['height']}  {body['line_count']} lines")
        print(body["text"])
        if args.boxes:
            for line in body.get("lines", []):
                print(f"  [{line['confidence']:.2f}] {line['text']}")
        print()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
