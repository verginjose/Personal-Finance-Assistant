#!/usr/bin/env python3
"""OCR-only replay: live text recognition plus candidate production adapter.

Run inside the OCR image (dependencies + INTERNAL_API_TOKEN), with the candidate
source in an isolated directory. Never publishes jobs or writes transactions.
Reviewed fields are scored separately from unlabelled processing checks.
"""
import argparse
import asyncio
import csv
import datetime
import hashlib
import json
import os
import re
from pathlib import Path
import sys
import time
from accuracy import compare_fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--labels', type=Path, default=Path(__file__).parent / 'tests/fixtures/account_labels.json')
    parser.add_argument('--invoice-metadata', type=Path,
                        default=Path(__file__).parent / 'tests/fixtures/invoice_labels.json')
    parser.add_argument('--reviewed-flags', type=Path, default=Path(__file__).parent / 'tests/fixtures/reviewed_flags.json')
    parser.add_argument('--ocr-url', default='http://127.0.0.1:8100/ocr')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--labelled-only', action='store_true')
    args = parser.parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app'))
    import httpx
    import main as service
    token = os.environ.get('INTERNAL_API_TOKEN')
    if not token:
        parser.error('INTERNAL_API_TOKEN is required')
    labels = json.loads(args.labels.read_text())
    if args.invoice_metadata.is_file():
        with args.invoice_metadata.open() as stream:
            sources = ([(row['filename'], json.loads(row['json_data'])) for row in csv.DictReader(stream)]
                       if args.invoice_metadata.suffix == '.csv' else list(json.load(stream).items()))
            for filename, source in sources:
                labels.setdefault(filename, {}).update(
                    vendor=source['seller']['name'], currency='INR',
                    date=datetime.datetime.strptime(source['date_of_issue'], '%d/%m/%Y').date().isoformat(),
                    amount=float(re.sub(r'[^\d.]', '', source['summary']['total_gross_worth'])),
                    items=[item['description'] for item in source['items']][:10])
    reviewed = json.loads(args.reviewed_flags.read_text())
    for row in reviewed:
        labels.setdefault(row['image'], {}).update(row['expected'])
    cache = {}
    with httpx.Client(timeout=180) as client:
        def read_image(data, filename):
            key = hashlib.sha256(data).hexdigest()
            if key not in cache:
                response = client.post(args.ocr_url, headers={'X-Internal-Token': token},
                                       files={'file': (filename, data, 'image/jpeg')})
                response.raise_for_status()
                cache[key] = response.json()['text']
            return cache[key]
        service._extract_text_from_image_bytes = read_image
        # Fixed rates make adapter conversion checks reproducible; no FX requests.
        service.rate_cache = {'rates': {'USD': 1, 'INR': 83, 'EUR': .92, 'GBP': .79,
                                        'CAD': 1.36, 'AUD': 1.52, 'JPY': 150}}
        callbacks = []
        class CallbackCapture:
            async def post(self, url, **kwargs):
                callbacks.append(kwargs['json'])
                return httpx.Response(200)
        service.http_client = CallbackCapture()
        files = sorted(p for p in args.samples.iterdir() if p.suffix.lower() in ('.jpg', '.jpeg', '.png', '.pdf'))
        if args.labelled_only:
            files = [p for p in files if p.name in labels]
        results, failures, abstentions, field_checks, matched_fields = [], [], [], 0, 0
        labelled_files = set()
        start = time.monotonic()

        async def run():
            nonlocal field_checks, matched_fields
            for index, path in enumerate(files, 1):
                try:
                    data = path.read_bytes()
                    content_type = 'application/pdf' if path.suffix.lower() == '.pdf' else 'image/jpeg'
                    doc = await service._parse_bill('ocr-test', data, path.name, content_type)
                    expected = labels.get(path.name)
                    if expected:
                        labelled_files.add(path.name)
                        field_checks += len(expected)
                        matches, review, errors = compare_fields(doc, expected)
                        matched_fields += len(matches)
                        abstentions.extend({'file': path.name, **field} for field in review)
                        failures.extend({'file': path.name, **field} for field in errors)
                        await service._process_and_notify('ocr-test', data, path.name, content_type,
                                                          receipt_id=path.name)
                        callback = callbacks[-1]
                        if callback['status'] != 'SUCCESS' or callback['data'] != doc or callback['receiptId'] != path.name:
                            failures.append(f'{path.name}: callback contract mismatch')
                    results.append({'file': path.name, 'labelled': bool(expected), 'data': doc})
                except Exception as error:
                    failures.append(f'{path.name}: {type(error).__name__}: {error}')
                if index % 25 == 0 or index == len(files):
                    print(f'{index}/{len(files)} processed; {len(failures)} failures', flush=True)
        asyncio.run(run())
    if not files:
        failures.append('No input documents')
    if args.labelled_only:
        failures.extend(f'Missing labelled fixture: {name}' for name in sorted(set(labels) - labelled_files))
    report = {'documents': len(files), 'processed': len(results), 'labelled_documents': len(labelled_files),
              'field_checks': field_checks, 'captured_callbacks': len(callbacks), 'failures': failures,
              'matched_fields': matched_fields, 'review_abstentions': abstentions,
              'reviewed_flagged_documents': len(reviewed),
              'duration_s': round(time.monotonic() - start, 2),
              'scope': 'Candidate production adapter + live OCR; callbacks captured; fixed FX rates; no ledger writes',
              'results': results}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'results'}), flush=True)
    return bool(failures)


if __name__ == '__main__':
    raise SystemExit(main())
