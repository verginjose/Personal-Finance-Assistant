#!/usr/bin/env python3
"""Benchmark one installed OCR profile without calling APIs or writing ledger data.

Run the same command in each CPU/GPU image; compare JSON summaries. Labels are
{filename: {amount, currency, date, vendor, ...}}. Files absent from --images are
reported, never replaced with cached OCR text. Latencies include IPC; the first
request also includes model initialization/downloads. Warm runs bypass API cache.
"""
import argparse
from collections import Counter
from dataclasses import replace
import json
from pathlib import Path
import statistics
import sys
import time

APP = Path(__file__).resolve().parents[1] / 'app'
sys.path.insert(0, str(APP))
from ocr_runtime import OCRConfig, OCRRuntime
from document_parser import parse_file_text
from accuracy import compare_fields


def rss_mib():
    for line in Path('/proc/self/status').read_text().splitlines():
        if line.startswith('VmRSS:'):
            return round(int(line.split()[1]) / 1024, 2)
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, required=True)
    parser.add_argument('--labels', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--limit', type=int, default=10)
    parser.add_argument('--idle-seconds', type=float, default=2)
    args = parser.parse_args()
    if args.limit <= 0 or args.idle_seconds <= 0:
        parser.error('limit and idle-seconds must be positive')
    import cv2
    labels = json.loads(args.labels.read_text())
    found, missing = [], []
    for name, expected in sorted(labels.items()):
        if Path(name).suffix.lower() not in ('.jpg', '.jpeg', '.png', '.webp', '.bmp'):
            continue
        path = args.images / name
        if path.is_file():
            found.append((path, expected))
        else:
            missing.append(name)
    if not found:
        parser.error('No labelled receipt images found')
    config = replace(OCRConfig.from_env(), idle_seconds=args.idle_seconds)
    runtime = OCRRuntime(config)
    report = dict(profile=vars(config), sample_count=min(len(found), args.limit),
                  missing_images=missing, parent_idle_before_mib=rss_mib(), results=[])
    counts = Counter()
    errors = 0
    try:
        for path, expected in found[:args.limit]:
            sample = dict(image=path.name)
            try:
                image = cv2.imread(str(path))
                if image is None:
                    raise ValueError('Cannot decode image')
                cold = not runtime.stats()['loaded']
                result = runtime.predict(image)
                doc = parse_file_text(result['full_text'])
                matched, abstentions, failures = compare_fields(doc, expected)
                counts.update(dict(matched=len(matched), review=len(abstentions), wrong=len(failures)))
                sample.update(cold=cold, runtime_ms=result['runtime_ms'], inference_ms=result['inference_ms'],
                              matched=matched, review=abstentions, wrong=failures, **runtime.stats())
                # Repeat exactly the same image without caching for a warm latency.
                sample['warm_runtime_ms'] = runtime.predict(image)['runtime_ms']
                sample.update(runtime.stats())
            except Exception as error:
                sample['error'] = f'{type(error).__name__}: {error}'
                errors += 1
            report['results'].append(sample)
            print(path.name, sample.get('error', sample.get('runtime_ms')), flush=True)
        loaded = runtime.stats()['loaded']
        deadline = time.monotonic() + args.idle_seconds + 10
        while runtime.stats()['loaded'] and time.monotonic() < deadline:
            time.sleep(0.1)
        report.update(field_counts=dict(counts), processing_errors=errors,
                      worker_unloaded_after_idle=loaded and not runtime.stats()['loaded'],
                      parent_idle_after_mib=rss_mib())
        warm = [s['warm_runtime_ms'] for s in report['results'] if 'warm_runtime_ms' in s]
        if warm:
            report['warm_median_ms'] = round(statistics.median(warm), 2)
            report['warm_p95_ms'] = sorted(warm)[min(len(warm)-1, int(0.95*len(warm)))]
        report['worker_peak_mib'] = max((s.get('worker_peak_mib', 0) for s in report['results']), default=0)
        report['memory_scope'] = 'Linux parent and worker RSS separately; excludes GPU VRAM and Tesseract subprocess peak'
    finally:
        runtime.close()
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, indent=2) + '\n')
    return 1 if errors or counts['wrong'] or not report.get('worker_unloaded_after_idle') else 0


if __name__ == '__main__':
    raise SystemExit(main())
