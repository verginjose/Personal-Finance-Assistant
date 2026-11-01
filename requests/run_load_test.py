#!/usr/bin/env python3
"""Concurrent functional load test for the Personal Finance Assistant.

Every simulated user registers, logs in and out repeatedly, seeds months of
transactions, creates goals and budgets, seeds recurring subscriptions, uploads
receipts to the OCR pipeline, forms a split-bill group with other simulated
users, settles a debt, and finally reads every analytics endpoint and verifies
that the CQRS read model agrees with what was written.

The feature map and the reasoning behind each assertion live in
requests/LOAD_TEST_PLAN.md. Stdlib only: no pip install required.

    python3 requests/run_load_test.py --users 20 --txns 100 --months 6
    GATEWAY_URL=https://verginjose.cloud python3 requests/run_load_test.py
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

API_PREFIX = "/api"

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
DIM = "\033[2m"
BOLD = "\033[1m"
RESET = "\033[0m"

RETRYABLE = {0, 429, 502, 503, 504}

INCOME_CATEGORIES = ["SALARY", "FREELANCE", "INVESTMENTS", "INTEREST", "BONUS", "OTHER_INCOME"]
EXPENSE_CATEGORIES = [
    "GROCERIES", "RESTAURANTS", "FOOD_DELIVERY", "FUEL", "PUBLIC_TRANSPORT",
    "CAB_AND_AUTO", "RENT", "ELECTRICITY", "INTERNET", "OTT_SUBSCRIPTIONS",
    "MEDICINES", "CLOTHING", "ELECTRONICS", "MOVIES_AND_EVENTS", "PETS",
    "TRAVEL_VACATION", "COFFEE_AND_SNACKS", "GYM_AND_FITNESS",
]
SUBSCRIPTION_VENDORS = ["Netflix", "Spotify", "Amazon Prime", "Disney Hotstar", "YouTube Premium"]


# ────────────────────────────────────────────────────────────────────────────
# Metrics (thread-safe)
# ────────────────────────────────────────────────────────────────────────────
class Metrics:
    def __init__(self):
        self._lock = threading.Lock()
        self.status = defaultdict(Counter)      # phase -> Counter(status)
        self.latency = defaultdict(list)        # phase -> [ms]
        self.retries = Counter()                # phase -> n
        self.throttled = Counter()              # phase -> 429 count
        self.breaker = Counter()                # phase -> 503 count
        self.failures = []                      # [(owner, check, detail)]
        self.passed = Counter()                 # phase -> n
        self.checks = []                        # full result records

    def record(self, phase, status, ms):
        with self._lock:
            self.status[phase][status] += 1
            self.latency[phase].append(ms)

    def retry(self, phase, status):
        with self._lock:
            self.retries[phase] += 1
            if status == 429:
                self.throttled[phase] += 1
            elif status == 503:
                self.breaker[phase] += 1

    def check(self, phase, owner, name, ok, detail=""):
        with self._lock:
            self.checks.append({"phase": phase, "owner": owner, "check": name,
                                "ok": ok, "detail": detail})
            if ok:
                self.passed[phase] += 1
            else:
                self.failures.append((owner, name, detail))

    def percentile(self, phase, p):
        values = sorted(self.latency.get(phase, []))
        if not values:
            return 0.0
        idx = min(len(values) - 1, int(round((p / 100.0) * (len(values) - 1))))
        return values[idx]


# ────────────────────────────────────────────────────────────────────────────
# HTTP client
# ────────────────────────────────────────────────────────────────────────────
class Cooldown:
    """Shared client-side back-off.

    The gateway opens `upsert-cb` after a burst of slow or failed writes and
    answers 503 for 30 s. Retrying individually is useless when every thread is
    pushing at once, so a 503 (or a 429) puts *every* simulated user to sleep
    for a short, growing interval. That is what a real client would do, and it
    lets the breaker's half-open probes succeed.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._until = 0.0
        self._strikes = 0

    def wait(self):
        with self._lock:
            delay = self._until - time.time()
        if delay > 0:
            time.sleep(min(delay, 3.0))

    def trigger(self, base):
        with self._lock:
            self._strikes = min(self._strikes + 1, 6)
            self._until = max(self._until, time.time() + base * self._strikes)

    def calm(self):
        with self._lock:
            self._strikes = 0


class Client:
    # A 30 s open breaker is the longest thing worth waiting out.
    RETRY_BUDGET = (0.35, 0.7, 1.4, 2.8, 5.6, 8.0, 8.0, 8.0)

    def __init__(self, base_url, metrics, timeout=60, retries=None, pace=0.0):
        self.base = base_url.rstrip("/")
        self.metrics = metrics
        self.timeout = timeout
        self.retries = len(self.RETRY_BUDGET) if retries is None else retries
        self.pace = pace
        self.cooldown = Cooldown()
        self._rng = random.Random()

    def _build_multipart(self, files):
        boundary = f"----pfa{uuid.uuid4().hex}"
        chunks = []
        for field, path in files.items():
            filename = os.path.basename(path)
            ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
            with open(path, "rb") as fh:
                payload = fh.read()
            chunks.append(f"--{boundary}\r\n".encode())
            chunks.append(
                f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode()
            )
            chunks.append(f"Content-Type: {ctype}\r\n\r\n".encode())
            chunks.append(payload)
            chunks.append(b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode())
        return b"".join(chunks), f"multipart/form-data; boundary={boundary}"

    def _once(self, path, method, headers, body, files, timeout):
        url = f"{self.base}{API_PREFIX}{path}"
        hdrs = dict(headers or {})
        data = None
        if files:
            data, ctype = self._build_multipart(files)
            hdrs["Content-Type"] = ctype
        elif body is not None:
            data = json.dumps(body).encode()
            hdrs["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                return resp.status, _parse_body(raw), dict(resp.headers)
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", "replace")
            return exc.code, _parse_body(raw), dict(exc.headers)
        except Exception as exc:  # connection refused, timeout, reset
            return 0, {"error": str(exc)}, {}

    def request(self, phase, path, method="GET", headers=None, body=None, files=None,
                timeout=None):
        # self.timeout is shared by every user thread, so a per-call timeout is
        # passed down instead of mutating it.
        effective_timeout = timeout or self.timeout
        started = time.time()
        status = 0
        try:
            for attempt in range(self.retries + 1):
                self.cooldown.wait()
                if self.pace and method != "GET":
                    time.sleep(self.pace)
                status, payload, hdrs = self._once(path, method, headers, body, files,
                                                   effective_timeout)
                if status in RETRYABLE and attempt < self.retries:
                    self.metrics.retry(phase, status)
                    if status == 503:
                        self.cooldown.trigger(1.0)
                    elif status == 429:
                        self.cooldown.trigger(0.4)
                    backoff = self.RETRY_BUDGET[min(attempt, len(self.RETRY_BUDGET) - 1)]
                    time.sleep(backoff * (0.5 + self._rng.random()))
                    continue
                if 200 <= status < 400:
                    self.cooldown.calm()
                return status, payload, hdrs
        finally:
            self.metrics.record(phase, status, (time.time() - started) * 1000.0)


def _parse_body(raw):
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except Exception:
        return {"raw": raw}
    return parsed if isinstance(parsed, (dict, list)) else {"raw": parsed}


# ────────────────────────────────────────────────────────────────────────────
# Assertions & shared state
# ────────────────────────────────────────────────────────────────────────────
class Checks:
    def __init__(self, metrics, phase, owner):
        self.metrics, self.phase, self.owner = metrics, phase, owner

    def ok(self, condition, name, detail=""):
        self.metrics.check(self.phase, self.owner, name, bool(condition), detail)
        return bool(condition)

    def status(self, expected, actual, name):
        if isinstance(expected, (tuple, set, list)):
            good = actual in expected
        else:
            good = actual == expected
        return self.ok(good, name, "" if good else f"expected {expected}, got {actual}")


class Cohort:
    """Shared state across simulated users (peer discovery, invitations)."""

    def __init__(self):
        self.users = []
        self.invites = defaultdict(list)   # peer index -> [(group_id, owner index)]
        self.lock = threading.Lock()

    def peers(self, idx, count, rng):
        others = [u for u in self.users if u.idx != idx]
        rng.shuffle(others)
        return others[:count]


class SimUser:
    def __init__(self, idx, password):
        self.idx = idx
        tag = uuid.uuid4().hex[:8]
        self.email = f"lt{idx}_{tag}@example.com"
        self.username = f"lt{idx}_{tag}"
        self.password = password
        self.user_id = None
        self.token = None
        self.refresh = None
        self.created_count = 0
        self.expected_income = 0.0
        self.expected_expense = 0.0
        self.seeded_names = []
        self.goal_id = None
        self.budget_id = None
        self.group_id = None
        self.peers_invited = 0

    def headers(self):
        return {"Authorization": f"Bearer {self.token}", "X-User-Id": str(self.user_id)}


def iso(dt):
    return dt.replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%S")


def poll(fn, predicate, timeout=10.0, interval=0.4):
    """Repeat fn() until predicate(result) holds. GETs hit the CQRS read model,
    which lags the write that just happened."""
    deadline = time.time() + timeout
    result = None
    while time.time() < deadline:
        result = fn()
        if predicate(result):
            return result, True
        time.sleep(interval)
    return result, False


def money(value):
    return round(float(value), 2)


# ────────────────────────────────────────────────────────────────────────────
# Stage 0 — register + login
# ────────────────────────────────────────────────────────────────────────────
def stage_register(client, user, checks, args):
    reg = {"email": user.email, "username": user.username,
           "password": user.password, "role": "USER"}
    status, body, _ = client.request("auth", "/auth/register", "POST", body=reg)
    # 409 means a previous run already registered this email; the login below
    # still validates the account.
    checks.status((200, 201, 409), status, "register")
    if status in (200, 201):
        checks.ok(body.get("userId") is not None, "register returns userId")
        user.user_id = body.get("userId")

    status, body, _ = client.request("auth", "/auth/login", "POST",
                                     body={"email": user.email, "password": user.password})
    checks.status(200, status, "login")
    checks.ok(body.get("token") is not None, "login returns access token")
    checks.ok(body.get("refreshToken") is not None, "login returns refresh token")
    user.token = body.get("token")
    user.refresh = body.get("refreshToken")
    user.user_id = body.get("userId") or user.user_id

    status, body, _ = client.request("auth", "/auth/me", "GET", headers=user.headers())
    checks.status(200, status, "auth/me")
    checks.ok(body.get("email") == user.email, "auth/me identity")


# ────────────────────────────────────────────────────────────────────────────
# Stage 1 — login/logout cycles, refresh rotation, password change
# ────────────────────────────────────────────────────────────────────────────
def stage_auth_cycles(client, user, checks, args):
    status, _, _ = client.request("auth", "/auth/health", "GET")
    checks.status(200, status, "auth health")

    for cycle in range(args.cycles):
        status, body, _ = client.request("auth", "/auth/login", "POST",
                                         body={"email": user.email, "password": user.password})
        checks.status(200, status, f"login cycle {cycle}")
        user.token = body.get("token") or user.token
        user.refresh = body.get("refreshToken") or user.refresh

        status, body, _ = client.request("auth", "/auth/me", "GET", headers=user.headers())
        checks.status(200, status, f"me cycle {cycle}")

        old_refresh = user.refresh
        status, body, _ = client.request("auth", "/auth/refresh", "POST",
                                         body={"refreshToken": old_refresh})
        checks.status(200, status, f"refresh cycle {cycle}")
        if body.get("token"):
            checks.ok(body.get("token") != user.token, f"refresh rotated token {cycle}")
            user.token = body.get("token")
            user.refresh = body.get("refreshToken") or user.refresh

        # Replaying the consumed refresh token must be rejected.
        status, _, _ = client.request("auth", "/auth/refresh", "POST",
                                      body={"refreshToken": old_refresh})
        checks.ok(status >= 400, f"old refresh token rejected {cycle}", f"got {status}")

        status, _, _ = client.request("auth", "/auth/logout", "POST", headers=user.headers(),
                                      body={"refreshToken": user.refresh})
        checks.status(200, status, f"logout cycle {cycle}")

        status, _, _ = client.request("auth", "/auth/me", "GET", headers=user.headers())
        checks.status(401, status, f"revoked token rejected {cycle}")

    # The loop above ends with the token blacklisted, so start a fresh session
    # before changing the password.
    status, body, _ = client.request("auth", "/auth/login", "POST",
                                     body={"email": user.email, "password": user.password})
    checks.status(200, status, "login before password change")
    user.token = body.get("token") or user.token
    user.refresh = body.get("refreshToken") or user.refresh

    new_password = args.password + "X"
    status, _, _ = client.request("auth", "/auth/change-password", "POST",
                                  headers=user.headers(),
                                  body={"currentPassword": user.password,
                                        "newPassword": new_password})
    checks.status(200, status, "change-password")
    if status == 200:
        user.password = new_password

    status, body, _ = client.request("auth", "/auth/login", "POST",
                                     body={"email": user.email, "password": user.password})
    checks.status(200, status, "re-login with current password")
    user.token = body.get("token") or user.token
    user.refresh = body.get("refreshToken") or user.refresh


# ────────────────────────────────────────────────────────────────────────────
# Stage 2 — transactions across months, goals, budgets, subscriptions
# ────────────────────────────────────────────────────────────────────────────
def stage_transactions(client, user, checks, args, rng):
    now = datetime.now()

    for i in range(args.txns):
        month = i % max(1, args.months)
        base = now - timedelta(days=30 * month)
        stamped = base.replace(day=rng.randint(1, 27), hour=rng.randint(7, 22),
                               minute=rng.randint(0, 59), second=0, microsecond=0)
        is_income = rng.random() < 0.15
        amount = money(rng.uniform(20000, 90000) if is_income else rng.uniform(50, 5000))
        category = rng.choice(INCOME_CATEGORIES if is_income else EXPENSE_CATEGORIES)
        recurring = (not is_income) and rng.random() < 0.15
        name = f"{category.lower().replace('_', ' ')} {month}-{i}"
        payload = {
            "userId": str(user.user_id),
            "name": name,
            "amount": amount,
            "type": "INCOME" if is_income else "EXPENSE",
            "category": category,
            "currency": "INR",
            "description": f"load-test seed m{month}",
            "recurring": recurring,
            "createdAt": iso(stamped),
        }
        if recurring:
            payload["recurringPeriod"] = "MONTHLY"
        status, body, _ = client.request("transactions", "/upsert/create", "POST",
                                         headers=user.headers(), body=payload)
        if status == 201:
            user.created_count += 1
            user.seeded_names.append(name)
            if is_income:
                user.expected_income += amount
            else:
                user.expected_expense += amount
        else:
            checks.ok(False, "create transaction", f"status {status}")
        # The gateway allows 20 req/s per user (burst 40). Backing off in small
        # waves keeps us under it instead of relying on 429 retries for pacing.
        if i % 15 == 14:
            time.sleep(0.3)

    checks.ok(user.created_count == args.txns, "all transactions created",
              f"{user.created_count}/{args.txns}")

    # Idempotency: the same header + payload must not create a second row.
    idem_payload = {
        "userId": str(user.user_id), "name": "idempotent probe", "amount": 111.0,
        "type": "EXPENSE", "category": "OTHERS", "currency": "INR",
        "description": "same key twice", "createdAt": iso(now),
    }
    h = user.headers()
    h["X-Idempotency-Key"] = str(uuid.uuid4())
    s1, b1, _ = client.request("transactions", "/upsert/create", "POST", headers=h, body=idem_payload)
    s2, b2, _ = client.request("transactions", "/upsert/create", "POST", headers=h, body=idem_payload)
    checks.status(201, s1, "idempotent create (first)")
    checks.ok(b1.get("id") == b2.get("id"), "idempotency returns same id")
    if s1 == 201:
        user.created_count += 1
        user.expected_expense += 111.0

    # Validation must reject an illegal currency.
    bad = dict(idem_payload)
    bad["currency"] = "XXXXXX"
    bad["name"] = "bad currency"
    h2 = user.headers()
    h2["X-Idempotency-Key"] = str(uuid.uuid4())
    status, _, _ = client.request("transactions", "/upsert/create", "POST", headers=h2, body=bad)
    checks.ok(400 <= status < 500, "validation rejects illegal currency", f"got {status}")

    # Bulk create.
    bulk = []
    for k in range(5):
        bulk.append({
            "userId": str(user.user_id), "name": f"bulk {k}", "amount": 10.0 + k,
            "type": "EXPENSE", "category": "OTHERS", "currency": "INR",
            "description": "bulk", "createdAt": iso(now),
        })
    status, body, _ = client.request("transactions", "/upsert/create/bulk", "POST",
                                     headers=user.headers(), body=bulk)
    checks.ok(status in (200, 201, 207), "bulk create", f"got {status}")
    if status in (200, 201) and isinstance(body, list):
        user.created_count += len(body)
        user.expected_expense += sum(float(x.get("amount", 0)) for x in body)

    # Update + patch one entry, keeping the expected totals in step.
    status, page, _ = client.request("transactions", f"/upsert/entries?userId={user.user_id}&size=5",
                                     "GET", headers=user.headers())
    checks.status(200, status, "list entries")
    if isinstance(page, dict) and page.get("content"):
        entry = page["content"][0]
        eid, old_amount = entry.get("id"), float(entry.get("amount", 0))
        kind = entry.get("type")
        update = {
            "id": eid, "userId": str(user.user_id), "name": "updated entry",
            "amount": old_amount, "type": kind, "currency": entry.get("currency", "INR"),
            "category": entry.get("category"), "description": "updated by load test",
            "createdAt": entry.get("createdAt"),
        }
        status, body, _ = client.request("transactions", "/upsert/update", "PUT",
                                         headers=user.headers(), body=update)
        checks.status(200, status, "update entry")
        checks.ok(body.get("description") == "updated by load test", "update reflected")

        new_amount = money(old_amount + 100)
        status, body, _ = client.request(
            "transactions", f"/upsert/entries/{eid}/amount?userId={user.user_id}",
            "PATCH", headers=user.headers(), body={"amount": new_amount})
        checks.status(200, status, "patch amount")
        if status == 200:
            delta = new_amount - old_amount
            if kind == "INCOME":
                user.expected_income += delta
            else:
                user.expected_expense += delta

        status, _, _ = client.request("transactions",
                                      f"/upsert/entries/{eid}?userId={user.user_id}",
                                      "GET", headers=user.headers())
        checks.status(200, status, "get single entry")

    # Search for a uniquely named probe entry. Searching a seeded name would be
    # racy: the update above may have renamed whichever row is newest. Search
    # reads the CQRS projection, so poll.
    probe_name = f"searchprobe-{uuid.uuid4().hex[:8]}"
    status, body, _ = client.request(
        "transactions", "/upsert/create", "POST", headers=user.headers(),
        body={"userId": str(user.user_id), "name": probe_name, "amount": 3.0,
              "type": "EXPENSE", "category": "OTHERS", "currency": "INR",
              "createdAt": iso(now)})
    checks.status(201, status, "search probe created")
    if status == 201:
        user.created_count += 1
        user.expected_expense += 3.0

    query = urllib.parse.quote(probe_name)
    (status, body), found = poll(
        lambda: client.request("transactions",
                               f"/upsert/search?userId={user.user_id}&q={query}",
                               "GET", headers=user.headers())[:2],
        lambda r: isinstance(r[1], dict) and any(
            row.get("name") == probe_name for row in r[1].get("content", [])),
        timeout=max(15.0, args.verify_timeout))
    checks.status(200, status, "search")
    checks.ok(found, "search returns the matching row",
              f"q={query} status={status} body={str(body)[:180]}")

    # Date-filtered listing: every row must fall inside the window.
    start = (now - timedelta(days=45)).strftime("%Y-%m-%d")
    end = now.strftime("%Y-%m-%d")
    status, body, _ = client.request(
        "transactions",
        f"/upsert/entries?userId={user.user_id}&startDate={start}&endDate={end}&size=200",
        "GET", headers=user.headers())
    checks.status(200, status, "list entries with date filter")
    content = body.get("content", []) if isinstance(body, dict) else []
    outside = [e for e in content if str(e.get("createdAt", ""))[:10] < start
               or str(e.get("createdAt", ""))[:10] > end]
    checks.ok(not outside, "date filter excludes out-of-range rows", f"{len(outside)} outside")

    # Recurring listing.
    status, body, _ = client.request("transactions", f"/upsert/recurring?userId={user.user_id}",
                                     "GET", headers=user.headers())
    checks.status(200, status, "recurring list")

    # Delete a throwaway entry created just for this check. The id comes from
    # the create response, so CQRS lag cannot skip it, and no seeded row (used
    # by the search assertion) disappears.
    status, body, _ = client.request(
        "transactions", "/upsert/create", "POST", headers=user.headers(),
        body={"userId": str(user.user_id), "name": "delete probe", "amount": 77.0,
              "type": "EXPENSE", "category": "OTHERS", "currency": "INR",
              "createdAt": iso(now)})
    if status == 201 and body.get("id") is not None:
        user.created_count += 1
        user.expected_expense += 77.0
        status, _, _ = client.request(
            "transactions", f"/upsert/delete/{body['id']}?userId={user.user_id}",
            "DELETE", headers=user.headers())
        checks.status((200, 204), status, "delete entry")
        if status in (200, 204):
            user.created_count -= 1
            user.expected_expense -= 77.0

    bulk_victims = []
    for k in range(2):
        status, body, _ = client.request(
            "transactions", "/upsert/create", "POST", headers=user.headers(),
            body={"userId": str(user.user_id), "name": f"to-delete {k}", "amount": 5.0,
                  "type": "EXPENSE", "category": "OTHERS", "currency": "INR",
                  "createdAt": iso(now)})
        if status == 201:
            bulk_victims.append(body.get("id"))
            user.created_count += 1
            user.expected_expense += 5.0
    if bulk_victims:
        status, _, _ = client.request(
            "transactions", f"/upsert/delete/bulk?userId={user.user_id}",
            "POST", headers=user.headers(), body=bulk_victims)
        checks.status(200, status, "bulk delete")
        if status == 200:
            user.created_count -= len(bulk_victims)
            user.expected_expense -= 5.0 * len(bulk_victims)


def stage_goals_budgets(client, user, checks, args, rng):
    # Two goals: one kept for the forecast endpoint, one deleted.
    status, body, _ = client.request("transactions", "/upsert/goals", "POST",
                                     headers=user.headers(),
                                     body={"userId": str(user.user_id),
                                           "name": "Emergency Fund",
                                           "targetAmount": 100000.0, "currency": "INR",
                                           "description": "load test goal",
                                           "deadline": "2027-12-31"})
    checks.status(201, status, "create goal")
    user.goal_id = body.get("id")

    if user.goal_id:
        status, body, _ = client.request(
            "transactions", f"/upsert/goals/{user.goal_id}/contribute?userId={user.user_id}",
            "PATCH", headers=user.headers(),
            body={"amount": 2500.0, "description": "load test contribution"})
        checks.status(200, status, "contribute to goal")
        saved = float(body.get("savedAmount") or -1)
        checks.ok(saved >= 2500.0, "goal savedAmount reflects contribution", str(saved))

        (status, body), seen = poll(
            lambda: client.request("transactions", f"/upsert/goals?userId={user.user_id}",
                                   "GET", headers=user.headers())[:2],
            lambda r: isinstance(r[1], list) and any(g.get("id") == user.goal_id for g in r[1]))
        checks.status(200, status, "list goals")
        checks.ok(seen, "created goal appears in listing")

    status, body, _ = client.request("transactions", "/upsert/goals", "POST",
                                     headers=user.headers(),
                                     body={"userId": str(user.user_id), "name": "Throwaway goal",
                                           "targetAmount": 5000.0, "currency": "INR"})
    if body.get("id"):
        status, _, _ = client.request(
            "transactions", f"/upsert/goals/{body['id']}?userId={user.user_id}",
            "DELETE", headers=user.headers())
        checks.status((200, 204), status, "delete goal")

    # Budget on a category that the seeded data uses.
    status, body, _ = client.request("transactions", "/upsert/budgets", "POST",
                                     headers=user.headers(),
                                     body={"userId": str(user.user_id),
                                           "expenseCategory": "RESTAURANTS",
                                           "budgetAmount": 50000.0, "period": "MONTHLY",
                                           "currency": "INR"})
    checks.status(201, status, "create budget")
    user.budget_id = body.get("budgetId") or body.get("id")

    (status, body), seen = poll(
        lambda: client.request("transactions", f"/upsert/budgets?userId={user.user_id}",
                               "GET", headers=user.headers())[:2],
        lambda r: isinstance(r[1], list) and len(r[1]) > 0)
    checks.status(200, status, "list budgets")
    checks.ok(seen, "budget appears in listing")


def stage_subscriptions(client, user, checks, args, rng):
    """Seed explicit recurring spends *and* a pattern-detectable sequence."""
    now = datetime.now()
    vendors = rng.sample(SUBSCRIPTION_VENDORS, 2)
    for vendor in vendors:
        status, _, _ = client.request(
            "transactions", "/upsert/create", "POST", headers=user.headers(),
            body={"userId": str(user.user_id), "name": vendor, "amount": 499.0,
                  "type": "EXPENSE", "category": "OTT_SUBSCRIPTIONS", "currency": "INR",
                  "description": "subscription seed", "recurring": True,
                  "recurringPeriod": "MONTHLY", "createdAt": iso(now)})
        checks.status(201, status, f"seed recurring subscription ({vendor})")
        if status == 201:
            user.created_count += 1
            user.expected_expense += 499.0

    # Pattern-based detection: same name + amount twice, ~30 days apart, both
    # inside the 90-day window.
    pattern_name = "Gym Membership"
    for months_ago in (2, 1):
        stamped = now - timedelta(days=30 * months_ago)
        status, _, _ = client.request(
            "transactions", "/upsert/create", "POST", headers=user.headers(),
            body={"userId": str(user.user_id), "name": pattern_name, "amount": 1200.0,
                  "type": "EXPENSE", "category": "GYM_AND_FITNESS", "currency": "INR",
                  "description": "pattern seed", "createdAt": iso(stamped)})
        if status == 201:
            user.created_count += 1
            user.expected_expense += 1200.0

    status, subs, _ = client.request("transactions",
                                     f"/upsert/subscriptions?userId={user.user_id}",
                                     "GET", headers=user.headers())
    checks.status(200, status, "list subscriptions")
    names = {s.get("name") for s in subs} if isinstance(subs, list) else set()
    checks.ok(any(v in names for v in vendors), "explicit recurring subscription detected",
              f"names={sorted(names)}")
    # Pattern-based detection (the "Gym Membership" pair) only runs in the daily
    # 02:00 UTC job in command-service, so it is observed, not asserted.
    print(f"{DIM}    user{user.idx}: pattern candidate detected={pattern_name in names}{RESET}")

    if isinstance(subs, list) and subs:
        sub_id = subs[0].get("id")
        status, _, _ = client.request(
            "transactions", f"/upsert/subscriptions/{sub_id}/deactivate?userId={user.user_id}",
            "DELETE", headers=user.headers())
        checks.status(200, status, "deactivate subscription")


# ────────────────────────────────────────────────────────────────────────────
# Stage 3 — receipt uploads
# ────────────────────────────────────────────────────────────────────────────
def stage_bills(client, user, checks, args, rng):
    if args.bills <= 0:
        return
    images = [os.path.join(args.samples, f) for f in sorted(os.listdir(args.samples))
              if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".pdf"))]
    if not images:
        checks.ok(False, "sample documents present", args.samples)
        return

    def upload(n):
        path = images[(user.idx + n) % len(images)]
        status, body, _ = client.request(
            "ocr", f"/bill/process/{user.user_id}", "POST",
            headers={"Authorization": f"Bearer {user.token}"},
            files={"file": path}, timeout=120)
        checks.status(202, status, f"bill upload {os.path.basename(path)}")
        checks.ok(body.get("receiptId") is not None, f"receipt id returned ({os.path.basename(path)})")
        return status

    with ThreadPoolExecutor(max_workers=min(3, args.bills)) as pool:
        list(pool.map(upload, range(args.bills)))


# ────────────────────────────────────────────────────────────────────────────
# Stages 4-6 — split groups across users
# ────────────────────────────────────────────────────────────────────────────
def stage_group_create(client, cohort, user, checks, args, rng):
    status, body, _ = client.request("groups", "/upsert/groups", "POST",
                                     headers=user.headers(),
                                     body={"name": f"Load Group {user.idx}",
                                           "description": "load test split",
                                           "createdBy": str(user.user_id)})
    checks.status(201, status, "create group")
    user.group_id = body.get("id")
    if not user.group_id:
        return

    (status, body), seen = poll(
        lambda: client.request("groups", f"/upsert/groups?userId={user.user_id}",
                               "GET", headers=user.headers())[:2],
        lambda r: isinstance(r[1], list) and any(g.get("id") == user.group_id for g in r[1]))
    checks.status(200, status, "list groups")
    checks.ok(seen, "group appears in listing")

    peers = cohort.peers(user.idx, max(1, args.group_size - 1), rng)
    for peer in peers:
        if peer.user_id is None:
            continue
        status, body, _ = client.request(
            "groups", f"/upsert/groups/{user.group_id}/members", "POST",
            headers=user.headers(),
            body={"userId": str(peer.user_id), "name": peer.username})
        checks.status(201, status, f"invite member {peer.idx}")
        if status == 201:
            user.peers_invited += 1
        with cohort.lock:
            cohort.invites[peer.idx].append((user.group_id, user.idx))


def stage_group_accept(client, cohort, user, checks):
    with cohort.lock:
        pending = list(cohort.invites.get(user.idx, []))
        cohort.invites[user.idx] = []
    for group_id, owner_idx in pending:
        status, _, _ = client.request(
            "groups", f"/upsert/groups/{group_id}/members/{user.user_id}/accept",
            "POST", headers=user.headers())
        checks.status(200, status, f"accept invite to group of user {owner_idx}")


def stage_group_expenses(client, user, checks, args, rng):
    if not user.group_id:
        return
    # Membership is read from the query-service projection; poll until the
    # invitations accepted in the previous stage show up.
    def _members():
        status, body, _ = client.request(
            "groups", f"/upsert/groups/{user.group_id}/members", "GET", headers=user.headers())
        accepted = [m for m in body if str(m.get("status", "")).upper() == "ACCEPTED"] \
            if isinstance(body, list) else []
        return status, body, accepted

    (status, members, accepted), all_accepted = poll(
        _members,
        lambda r: len(r[2]) >= max(1, user.peers_invited),
        timeout=20)
    checks.status(200, status, "list group members")
    checks.ok(all_accepted, "every invited member accepted",
              f"{len(accepted)} accepted, {user.peers_invited} invited")

    for n in range(2):
        status, _, _ = client.request(
            "groups", f"/upsert/groups/{user.group_id}/expenses", "POST",
            headers=user.headers(),
            body={"amount": 300.0 + n * 50, "description": f"shared expense {n}",
                  "paidBy": str(user.user_id), "currency": "INR"})
        checks.status(201, status, f"add shared expense {n}")

    status, body, _ = client.request(
        "groups", f"/upsert/groups/{user.group_id}/expenses", "GET", headers=user.headers())
    checks.status(200, status, "list shared expenses")

    status, body, _ = client.request(
        "groups", f"/upsert/groups/{user.group_id}/activity", "GET", headers=user.headers())
    checks.status(200, status, "group activity feed")

    # Balances are read from the query-service projection, so poll for the
    # shared expenses to arrive before asserting.
    debts, balances, status = [], {}, 0
    deadline = time.time() + 15
    while time.time() < deadline:
        status, balances, _ = client.request(
            "groups", f"/upsert/groups/{user.group_id}/balances", "GET", headers=user.headers())
        debts = balances.get("simplifiedDebts", []) if isinstance(balances, dict) else []
        if debts:
            break
        time.sleep(0.5)
    checks.status(200, status, "group balances")
    checks.ok(len(debts) > 0, "balances contain a debt relationship",
              f"accepted members={len(accepted)}")

    # Settle one debt, if there is one and a member to settle with.
    if debts:
        debt = debts[0]
        frm = debt.get("fromUserId") or debt.get("from") or debt.get("debtor")
        to = debt.get("toUserId") or debt.get("to") or debt.get("creditor")
        if frm and to:
            status, _, _ = client.request(
                "groups",
                f"/upsert/groups/{user.group_id}/settle?fromUserId={frm}&toUserId={to}",
                "POST", headers=user.headers())
            checks.status(200, status, "settle debt")


# ────────────────────────────────────────────────────────────────────────────
# Stage 7 — analytics + read-model verification
# ────────────────────────────────────────────────────────────────────────────
def stage_analytics(client, user, checks, args):
    uid = user.user_id
    h = user.headers()

    # The filtered listing endpoints take Category/TransactionType plus a
    # mandatory date window (see AnalyticsController), and return 500 rather
    # than 400 when a parameter is missing — so they are called correctly here.
    start = (datetime.now() - timedelta(days=400)).strftime("%Y-%m-%d")
    end = datetime.now().strftime("%Y-%m-%d")
    simple = [
        ("/analytics/category-pie-chart", "category pie chart"),
        ("/analytics/timeline-chart", "timeline chart"),
        ("/analytics/comprehensive", "comprehensive analytics"),
        (f"/analytics/transaction-entries?userId={uid}&page=0&size=20", "analytics entries page"),
        (f"/analytics/transactions/income-by-category?userId={uid}&categoryName=SALARY"
         f"&startDate={start}&endDate={end}", "income by category"),
        (f"/analytics/transactions/by-type?userId={uid}&transactionType=EXPENSE"
         f"&startDate={start}&endDate={end}", "transactions by type"),
        (f"/analytics/health-score?userId={uid}", "health score"),
        (f"/analytics/budgets/trends?userId={uid}", "budget trends"),
    ]
    for path, label in simple:
        sep = "&" if "?" in path else "?"
        url = path if "userId=" in path else f"{path}{sep}userId={uid}"
        status, body, _ = client.request("analytics", url, "GET", headers=h)
        checks.status(200, status, label)
        if label == "health score" and isinstance(body, dict):
            checks.ok(body.get("totalScore") is not None, "health score has totalScore")
            checks.ok(isinstance(body.get("breakdown"), dict), "health score has breakdown")

    if user.goal_id:
        status, _, _ = client.request(
            "analytics", f"/analytics/goals/{user.goal_id}/forecast?userId={uid}",
            "GET", headers=h)
        checks.status(200, status, "goal forecast")

    if args.with_ai:
        t0 = time.time()
        status, first, _ = client.request("analytics", f"/analytics/ai-insights?userId={uid}",
                                          "GET", headers=h, timeout=180)
        first_ms = (time.time() - t0) * 1000
        checks.status(200, status, "ai insights (generated)")
        t0 = time.time()
        status, second, _ = client.request("analytics", f"/analytics/ai-insights?userId={uid}",
                                           "GET", headers=h, timeout=180)
        cached_ms = (time.time() - t0) * 1000
        checks.status(200, status, "ai insights (cached)")
        checks.ok(cached_ms < first_ms, "cached ai insights faster than generation",
                  f"{cached_ms:.0f}ms vs {first_ms:.0f}ms")


def stage_verify(client, user, checks, args):
    """Compare the writer's intent with the CQRS read model, then check that the
    read model agrees with itself (summary vs listed entries)."""
    uid = user.user_id
    h = user.headers()
    deadline = time.time() + args.verify_timeout

    summary = {}
    while time.time() < deadline:
        status, summary, _ = client.request("transactions",
                                            f"/upsert/summary?userId={uid}", "GET", headers=h)
        if status == 200 and int(summary.get("totalCount", 0)) >= user.created_count:
            break
        time.sleep(0.5)

    checks.status(200, status, "summary reachable")
    total_count = int(summary.get("totalCount", 0) or 0)
    checks.ok(total_count >= user.created_count,
              "summary sees every seeded transaction",
              f"read model {total_count} >= written {user.created_count}")

    # Walk the read model and sum it independently of /summary.
    listed, page, income, expense = 0, 0, 0.0, 0.0
    while page < 50:
        status, body, _ = client.request(
            "transactions", f"/upsert/entries?userId={uid}&page={page}&size=200",
            "GET", headers=h)
        if status != 200 or not isinstance(body, dict):
            break
        rows = body.get("content", [])
        if not rows:
            break
        for row in rows:
            amount = float(row.get("amount", 0) or 0)
            if row.get("type") == "INCOME":
                income += amount
            else:
                expense += amount
            listed += 1
        total_elements = int(body.get("totalElements", listed) or listed)
        page += 1
        if listed >= total_elements:
            break

    checks.ok(listed >= user.created_count, "listing returns seeded rows",
              f"{listed} listed, {user.created_count} written")

    # Internal consistency: summary must equal the sum over the listing at the
    # same moment. Immune to OCR/CQRS timing because both sides are the read model.
    eps = 0.05
    checks.ok(abs(float(summary.get("totalIncome", 0) or 0) - income) < eps,
              "summary income equals listing sum",
              f"{summary.get('totalIncome')} vs {income:.2f}")
    checks.ok(abs(float(summary.get("totalExpense", 0) or 0) - expense) < eps,
              "summary expense equals listing sum",
              f"{summary.get('totalExpense')} vs {expense:.2f}")

    # What we wrote is a floor: OCR may have appended entries of its own.
    checks.ok(float(summary.get("totalIncome", 0) or 0) >= user.expected_income - eps,
              "read-model income is at least what was written",
              f"{summary.get('totalIncome')} >= {user.expected_income:.2f}")
    checks.ok(float(summary.get("totalExpense", 0) or 0) >= user.expected_expense - eps,
              "read-model expense is at least what was written",
              f"{summary.get('totalExpense')} >= {user.expected_expense:.2f}")

    # CSV export.
    url = f"{client.base}{API_PREFIX}/upsert/entries/export?userId={uid}"
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=client.timeout) as resp:
            csv_text = resp.read().decode("utf-8", "replace")
            ctype = resp.headers.get("Content-Type", "")
        checks.ok("text/csv" in ctype, "csv export content-type", ctype)
        rows = max(0, len(csv_text.strip().splitlines()) - 1)
        checks.ok(rows >= user.created_count, "csv export has every seeded row",
                  f"{rows} rows, {user.created_count} written")
    except Exception as exc:
        checks.ok(False, "csv export", str(exc))


def stage_cleanup(client, user, checks, args):
    if not args.leave_budgets and user.budget_id:
        status, _, _ = client.request(
            "transactions", f"/upsert/budgets/{user.budget_id}?userId={user.user_id}",
            "DELETE", headers=user.headers())
        checks.status((200, 204), status, "delete budget")


# ────────────────────────────────────────────────────────────────────────────
# Orchestration
# ────────────────────────────────────────────────────────────────────────────
def run_stage(ctx, args, metrics, name, fn):
    """Run fn(user) for every user concurrently and wait for all of them."""
    def wrapped(user):
        rng = random.Random(args.seed + user.idx)
        try:
            fn(user, rng)
        except Exception as exc:  # a bug in one user must not kill the run
            metrics.check(name, f"user{user.idx}", "stage completed", False, repr(exc))

    with ThreadPoolExecutor(max_workers=args.users) as pool:
        list(pool.map(wrapped, ctx.users))
    print(f"{DIM}  · {name} complete{RESET}")


def run(args):
    metrics = Metrics()
    client = Client(args.base_url, metrics, timeout=args.timeout, pace=args.pace)
    cohort = Cohort()

    print(f"{BOLD}{CYAN}Personal Finance Assistant — concurrent load test{RESET}")
    print(f"  gateway : {args.base_url}")
    print(f"  users   : {args.users}   txns/user: {args.txns}   months: {args.months}")
    print(f"  bills   : {args.bills}   cycles: {args.cycles}   group size: {args.group_size}")
    print(f"  ai      : {'on' if args.with_ai else 'off (Groq)'}")
    started = time.time()

    # Stage 0 — register everyone serially in small waves. /auth/** is rate
    # limited per (ip, path), and every simulated user shares one IP.
    print(f"\n{BOLD}Stage 0 — registering {args.users} users{RESET}")
    with ThreadPoolExecutor(max_workers=args.auth_concurrency) as pool:
        futures = []
        for idx in range(args.users):
            user = SimUser(idx, args.password)
            cohort.users.append(user)
            checks = Checks(metrics, "auth", f"user{idx}")
            futures.append(pool.submit(stage_register, client, user, checks, args))
        for fut in futures:
            fut.result()

    ready = [u for u in cohort.users if u.user_id and u.token]
    cohort.users = ready
    print(f"  {len(ready)}/{args.users} users ready")

    # Stage 1 — auth cycles.
    print(f"\n{BOLD}Stage 1 — login/logout cycles{RESET}")
    run_stage(cohort, args, metrics, "auth-cycles",
              lambda u, rng: stage_auth_cycles(client, u, Checks(metrics, "auth-cycles", f"user{u.idx}"), args))

    # Stage 2 — data seeding.
    print(f"\n{BOLD}Stage 2 — transactions, goals, budgets, subscriptions{RESET}")
    def stage2(user, rng):
        checks = Checks(metrics, "data", f"user{user.idx}")
        stage_transactions(client, user, checks, args, rng)
        stage_goals_budgets(client, user, checks, args, rng)
        stage_subscriptions(client, user, checks, args, rng)
    run_stage(cohort, args, metrics, "data", stage2)

    # Stage 3 — OCR uploads.
    print(f"\n{BOLD}Stage 3 — receipt uploads{RESET}")
    run_stage(cohort, args, metrics, "ocr",
              lambda u, rng: stage_bills(client, u, Checks(metrics, "ocr", f"user{u.idx}"), args, rng))

    # Stage 4 — groups + invitations.
    print(f"\n{BOLD}Stage 4 — creating split groups{RESET}")
    run_stage(cohort, args, metrics, "groups",
              lambda u, rng: stage_group_create(client, cohort, u,
                                                Checks(metrics, "groups", f"user{u.idx}"), args, rng))

    # Stage 5 — invitations accepted by the peers themselves.
    print(f"\n{BOLD}Stage 5 — accepting group invitations{RESET}")
    run_stage(cohort, args, metrics, "groups",
              lambda u, rng: stage_group_accept(client, cohort, u,
                                                Checks(metrics, "groups", f"user{u.idx}")))

    # Stage 6 — shared expenses, balances, settlement.
    print(f"\n{BOLD}Stage 6 — shared expenses and settlement{RESET}")
    run_stage(cohort, args, metrics, "split",
              lambda u, rng: stage_group_expenses(client, u,
                                                  Checks(metrics, "split", f"user{u.idx}"), args, rng))

    # Stage 7 — analytics + verification.
    print(f"\n{BOLD}Stage 7 — analytics and read-model verification{RESET}")
    def stage7(user, rng):
        checks = Checks(metrics, "analytics", f"user{user.idx}")
        stage_analytics(client, user, checks, args)
        stage_verify(client, user, checks, args)
        stage_cleanup(client, user, checks, args)
    run_stage(cohort, args, metrics, "analytics", stage7)

    elapsed = time.time() - started
    return report(args, metrics, cohort, elapsed)


def report(args, metrics, cohort, elapsed):
    print(f"\n{BOLD}{CYAN}=== Results ==={RESET}")

    header = (f"{'phase':<14}{'reqs':>7}{'2xx':>7}{'4xx':>7}{'5xx':>6}{'retry':>7}"
              f"{'429':>6}{'503':>6}{'p50':>7}{'p95':>7}{'p99':>7}")
    print(header)
    print("-" * len(header))
    totals = Counter()
    for phase in sorted(metrics.status):
        counts = metrics.status[phase]
        ok = sum(n for c, n in counts.items() if 200 <= c < 300)
        c4 = sum(n for c, n in counts.items() if 400 <= c < 500)
        c5 = sum(n for c, n in counts.items() if c >= 500)
        n = sum(counts.values())
        totals.update({"n": n, "2xx": ok, "4xx": c4, "5xx": c5})
        print(f"{phase:<14}{n:>7}{ok:>7}{c4:>7}{c5:>6}{metrics.retries[phase]:>7}"
              f"{metrics.throttled[phase]:>6}{metrics.breaker[phase]:>6}"
              f"{metrics.percentile(phase, 50):>6.0f}m{metrics.percentile(phase, 95):>6.0f}m"
              f"{metrics.percentile(phase, 99):>6.0f}m")
    print("-" * len(header))
    print(f"{'TOTAL':<14}{totals['n']:>7}{totals['2xx']:>7}{totals['4xx']:>7}{totals['5xx']:>6}"
          f"{sum(metrics.retries.values()):>7}{sum(metrics.throttled.values()):>6}"
          f"{sum(metrics.breaker.values()):>6}")

    if metrics.throttled or metrics.breaker:
        print(f"{DIM}  429 = per-IP auth limiter, 503 = upsert-cb opened; both retried "
              f"with a shared cooldown.{RESET}")

    checks_total = len(metrics.checks)
    checks_ok = sum(1 for c in metrics.checks if c["ok"])
    checks_failed = checks_total - checks_ok

    print(f"\n{'checks':<14}{checks_ok}/{checks_total} passed")
    print(f"{'duration':<14}{elapsed:.1f}s   throughput: {totals['n'] / max(elapsed, 0.001):.1f} req/s")
    print(f"{'users':<14}{len(cohort.users)}")

    if metrics.failures:
        print(f"\n{RED}{BOLD}Failures ({len(metrics.failures)}):{RESET}")
        for owner, name, detail in metrics.failures[:40]:
            print(f"  {RED}×{RESET} [{owner}] {name}" + (f" — {detail}" if detail else ""))
        if len(metrics.failures) > 40:
            print(f"  … and {len(metrics.failures) - 40} more")

    verdict_ok = checks_failed == 0 and totals["5xx"] == 0
    if verdict_ok:
        print(f"\n{GREEN}{BOLD}PASS — {checks_ok} checks, no 5xx.{RESET}")
    else:
        print(f"\n{RED}{BOLD}FAIL — {checks_failed} failed checks, {totals['5xx']} 5xx responses.{RESET}")

    if args.out:
        payload = {
            "gateway": args.base_url,
            "config": {k: v for k, v in vars(args).items() if k not in ("password",)},
            "duration_s": round(elapsed, 1),
            "totals": dict(totals),
            "retries": dict(metrics.retries),
            "throttled_429": dict(metrics.throttled),
            "breaker_503": dict(metrics.breaker),
            "percentiles": {
                phase: {
                    "p50": round(metrics.percentile(phase, 50), 1),
                    "p95": round(metrics.percentile(phase, 95), 1),
                    "p99": round(metrics.percentile(phase, 99), 1),
                    "count": len(metrics.latency[phase]),
                } for phase in metrics.latency
            },
            "status": {phase: {str(k): v for k, v in c.items()}
                       for phase, c in metrics.status.items()},
            "checks": metrics.checks,
            "verdict": "PASS" if verdict_ok else "FAIL",
        }
        with open(args.out, "w") as fh:
            json.dump(payload, fh, indent=2)
        print(f"\n{DIM}report written to {args.out}{RESET}")

    return 0 if verdict_ok else 1


def parse_args(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(
        description="Concurrent functional load test for the Personal Finance Assistant")
    p.add_argument("--users", type=int, default=20, help="concurrent simulated users")
    p.add_argument("--txns", type=int, default=100, help="transactions per user")
    p.add_argument("--months", type=int, default=6, help="months of history to spread txns over")
    p.add_argument("--bills", type=int, default=2, help="receipts uploaded per user (0 = skip OCR)")
    p.add_argument("--cycles", type=int, default=3, help="login/logout cycles per user")
    p.add_argument("--group-size", type=int, default=4, help="members per split group")
    p.add_argument("--with-ai", action="store_true", help="also call /analytics/ai-insights (Groq)")
    p.add_argument("--leave-budgets", action="store_true", help="do not delete budgets at the end")
    p.add_argument("--base-url", default=os.environ.get("GATEWAY_URL", "http://localhost:8080"))
    p.add_argument("--samples", default=os.path.join(here, "sample-documents"))
    p.add_argument("--password", default="LoadTest123!")
    p.add_argument("--seed", type=int, default=7, help="deterministic data per user index")
    p.add_argument("--pace", type=float, default=0.0,
                   help="seconds to wait between writes per user (0 = maximum pressure)")
    p.add_argument("--timeout", type=float, default=60.0, help="per-request timeout (seconds)")
    p.add_argument("--verify-timeout", type=float, default=20.0,
                   help="how long to wait for the CQRS read model to catch up")
    p.add_argument("--auth-concurrency", type=int, default=5,
                   help="parallel registrations (auth is rate limited per IP)")
    p.add_argument("--out", default=os.path.join(here, "loadtest-report.json"))
    return p.parse_args(argv)


if __name__ == "__main__":
    sys.exit(run(parse_args(sys.argv[1:])))
