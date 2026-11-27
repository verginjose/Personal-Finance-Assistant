#!/usr/bin/env python3
"""Small production journeys with bounded concurrency and globally paced traffic."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
import random
import secrets
import threading
import time
import urllib.error
import urllib.request
import uuid


class Scenario:
    def __init__(self, args):
        self.args = args
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.next_request = 0
        self.statuses, self.actions = Counter(), Counter()
        self.latencies = []
        self.failures = []
        self.members = []
        self.ocr = dict(attempted=0, completed=0, busy=0, failed=0)

    def request(self, action, path, member=None, method='GET', body=None, expected=(200,), raw=None):
        with self.lock:
            slot = max(time.monotonic(), self.next_request)
            self.next_request = slot + 1 / self.args.rps
        if self.stop.wait(max(0, slot - time.monotonic())):
            raise RuntimeError('test stopped')
        headers = {'User-Agent': 'pfa-realistic-test/1.0'}
        if member:
            headers['Authorization'] = 'Bearer ' + member['token']
        if raw:
            data, headers['Content-Type'] = raw
        else:
            data = None if body is None else json.dumps(body).encode()
            if data is not None:
                headers['Content-Type'] = 'application/json'
        if method == 'POST' and '/upsert/create' in path:
            headers['X-Idempotency-Key'] = str(uuid.uuid4())
        req = urllib.request.Request(self.args.base_url.rstrip('/') + '/api' + path,
                                     data=data, headers=headers, method=method)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                status, payload = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, payload = error.code, error.read()
            error.close()
        except Exception:
            status, payload = 0, b'{}'
        with self.lock:
            self.statuses[status] += 1
            self.actions[action] += 1
            self.latencies.append((time.monotonic() - started) * 1000)
        if status not in expected:
            self.stop.set()
            # Record neither URLs with user IDs nor response bodies/tokens.
            self.failures.append(f'{action}: unexpected HTTP {status}; stopped without retry')
            raise RuntimeError(self.failures[-1])
        try:
            return status, json.loads(payload)
        except (ValueError, TypeError):
            return status, {}

    def register(self, number):
        password = secrets.token_urlsafe(24)
        tag = 'realistic-' + uuid.uuid4().hex[:16]
        email = tag + '@example.invalid'
        self.request('register', '/auth/register', method='POST', expected=(200, 201),
                     body=dict(email=email, username=tag, password=password, role='USER'))
        _, user = self.request('login', '/auth/login', method='POST', body=dict(email=email, password=password))
        if not user.get('token') or not user.get('userId'):
            raise RuntimeError('login returned no session')
        return {'token': user['token'], 'user_id': user['userId'], 'writes': 0}

    def transaction(self, member):
        self.request('add_expense', '/upsert/create', member, 'POST', expected=(201,), body={
            'userId': member['user_id'], 'name': 'Realistic test groceries', 'amount': '12.50',
            'type': 'EXPENSE', 'category': 'GROCERIES', 'currency': 'INR',
            'description': 'synthetic realistic test', 'createdAt': datetime.now(timezone.utc).isoformat(),
        })
        member['writes'] += 1

    def browse(self, index, deadline):
        rng = random.Random(1701 + index)
        while not self.stop.is_set() and time.monotonic() < deadline:
            member = self.members[rng.randrange(len(self.members))]
            uid = member['user_id']
            # Browsing dominates. Writes are bounded to three per account.
            if member['writes'] < 3 and rng.random() < 0.12:
                self.transaction(member)
            else:
                action, path = rng.choice([
                    ('dashboard', f'/upsert/summary?userId={uid}'),
                    ('transactions', f'/upsert/entries?userId={uid}&page=0&size=20'),
                    ('analytics', f'/analytics/category-pie-chart?userId={uid}'),
                    ('groups', f'/upsert/groups?userId={uid}'),
                    ('subscriptions', f'/upsert/subscriptions?userId={uid}'),
                    ('inbox', '/upsert/notifications?page=0&size=25'),
                ])
                self.request(action, path, member)
            self.stop.wait(rng.uniform(4, 12))

    def bills(self, deadline):
        # One uploader, one outstanding parse, at least 60s between uploads.
        for index in range(self.args.bills_total):
            if self.stop.wait(60) or time.monotonic() >= deadline:
                return
            member = self.members[index % len(self.members)]
            payload = Path(self.args.receipt).read_bytes()
            boundary = 'pfa-' + uuid.uuid4().hex
            kind = 'image/png' if payload.startswith(b'\x89PNG') else 'image/jpeg'
            raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="receipt.jpg"\r\nContent-Type: {kind}\r\n\r\n'.encode()
                   + payload + f'\r\n--{boundary}--\r\n'.encode())
            self.ocr['attempted'] += 1
            status, uploaded = self.request('receipt_upload', f'/bill/process/{member["user_id"]}', member,
                                            'POST', raw=(raw, 'multipart/form-data; boundary=' + boundary),
                                            expected=(202, 429))
            if status == 429:
                self.ocr['busy'] += 1
                continue  # no upload retry or duplicate job
            receipt = uploaded.get('receiptId')
            if not receipt:
                raise RuntimeError('upload returned no receipt ID')
            until = min(deadline, time.monotonic() + 180)
            while not self.stop.wait(5) and time.monotonic() < until:
                _, inbox = self.request('receipt_result', '/upsert/notifications?page=0&size=25', member)
                for item in inbox.get('items', []):
                    try:
                        result = json.loads(item['payload']) if isinstance(item.get('payload'), str) else item.get('payload') or {}
                    except ValueError:
                        continue
                    if result.get('receiptId') == receipt:
                        if result.get('status') != 'SUCCESS' or not result.get('data'):
                            self.ocr['failed'] += 1
                            raise RuntimeError('receipt processing failed')
                        self.ocr['completed'] += 1
                        break
                else:
                    continue
                break
            else:
                self.ocr['failed'] += 1
                raise RuntimeError('receipt processing exceeded its bounded wait')

    def run(self):
        started = time.monotonic()
        verdict = 'FAIL'
        try:
            for index in range(self.args.users):
                self.members.append(self.register(index))
            # A small shared-expense journey exercises group writes, without
            # the golden test's large fan-out or deliberate refresh races.
            leader = self.members[0]
            _, group = self.request('create_group', '/upsert/groups', leader, 'POST', expected=(201,),
                                    body=dict(name='Realistic test household', createdBy=leader['user_id']))
            for member in self.members[1:3]:
                self.request('invite_member', f'/upsert/groups/{group["id"]}/members', leader, 'POST', expected=(201,),
                             body=dict(userId=member['user_id'], name='Test member'))
                self.request('accept_invite', f'/upsert/groups/{group["id"]}/members/{member["user_id"]}/accept', member, 'POST')
            self.request('shared_expense', f'/upsert/groups/{group["id"]}/expenses', leader, 'POST', expected=(201,),
                         body=dict(amount='30.00', description='Test household groceries', paidBy=leader['user_id'],
                                   splitType='EQUAL', expenseCategory='GROCERIES', currency='INR'))
            deadline = time.monotonic() + self.args.duration
            with ThreadPoolExecutor(max_workers=4) as pool:
                futures = [pool.submit(self.browse, i, deadline) for i in range(3)]
                if self.args.bills_total:
                    futures.append(pool.submit(self.bills, deadline))
                try:
                    for future in as_completed(futures):
                        future.result()
                except Exception:
                    self.stop.set()
                    raise
            verdict = 'PASS'
        except Exception as error:
            self.stop.set()
            if not self.failures:
                self.failures.append(str(error))
        finally:
            samples = sorted(self.latencies)
            report = dict(verdict=verdict, users=len(self.members), duration_seconds=round(time.monotonic() - started, 1),
                          global_rps_limit=self.args.rps, http_attempts=dict(self.statuses), actions=dict(self.actions),
                          p95_ms=round(samples[min(len(samples) - 1, int(len(samples) * .95))], 1) if samples else None,
                          ocr=self.ocr, failures=self.failures, transport_retries=0,
                          synthetic_data_persists=True)
            Path(self.args.out).write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps(report))
        return 0 if verdict == 'PASS' else 1


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--users', type=int, choices=(5, 10, 25, 50), default=10)
    p.add_argument('--duration', type=int, choices=(120, 180, 300), default=180)
    p.add_argument('--rps', type=float, choices=(1.0, 2.0), default=1.0)
    p.add_argument('--bills-total', type=int, choices=(0, 1, 2, 3), default=1)
    p.add_argument('--receipt', default=str(Path(__file__).parent / 'large-receipt-image-dataset-SRD/1131-receipt.jpg'))
    p.add_argument('--base-url', default='https://verginjose.cloud')
    p.add_argument('--out', required=True)
    return p.parse_args(argv)


if __name__ == '__main__':
    raise SystemExit(Scenario(parse_args()).run())
