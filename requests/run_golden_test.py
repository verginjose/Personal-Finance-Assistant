#!/usr/bin/env python3
"""Golden end-to-end + load test for the Personal Finance Assistant.

This is the single test that exercises the whole product the way a real user
cohort does, and the only load test the repo keeps:

  stage 1  identity: realistic accounts register and log in
  stage 2  personal history: months of realistic transactions, daily/weekly/
           monthly recurring subscriptions (backdated so the hourly recurring
           scheduler must aggregate missed periods and emit outbox events),
           a savings goal and a budget, then parallel deletes
  stage 3  security: refresh-token rotation race, idempotent double submit,
           cross-user authorisation probes
  stage 4  richer flows: bulk create/delete, search, date filters, analytics
  stage 5  cache eviction + consistency per user: warm a cached summary,
           update an amount, prove the read model and its cache converge;
           delete the row and prove it does not reappear; fire parallel
           identical reads and require one answer
  stage 6  OCR: ~10 receipts per user drawn at random from the 200-image
           dataset; some become personal transactions, the rest are attached
           to group expenses
  stage 7  groups: leaders create groups, peers accept their own invitations
  stage 8  shared expenses split EQUAL / PERCENTAGE / EXACT with realistic
           descriptions, categories and receipt attachments
  stage 9  group cache eviction: a canary expense must appear in the cached
           balances immediately and disappear again when it is deleted
  stage 10 half of every group settles, the other half archives its membership
  stage 11 every group deletes five expenses
  stage 12 verification: read-model consistency, summary vs listing vs
           analytics, recurring/subscription visibility, balances arithmetic,
           cross-member balance agreement, archived flags

Stdlib only. Exit code is 0 only when every check passed and no 5xx was seen.

    python3 requests/run_golden_test.py --users 50
    GATEWAY_URL=https://verginjose.cloud python3 requests/run_golden_test.py --users 100
"""

from __future__ import annotations

import argparse
import csv
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
RETRYABLE = {0, 429, 502, 503, 504}

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
DIM = "\033[2m"
BOLD = "\033[1m"
RESET = "\033[0m"


# ────────────────────────────────────────────────────────────────────────────
# Realistic identity and spending catalogues
# ────────────────────────────────────────────────────────────────────────────
FIRST_NAMES = [
    "Aarav", "Aditi", "Aditya", "Aishwarya", "Akash", "Akshara", "Amit", "Ananya",
    "Aniket", "Anjali", "Ankit", "Anushka", "Arjun", "Arya", "Ashwin", "Avni",
    "Bhavna", "Chetan", "Deepak", "Deepika", "Dev", "Dhruv", "Divya", "Farhan",
    "Gaurav", "Gayatri", "Harini", "Harish", "Ishaan", "Ishita", "Jaya", "Kabir",
    "Karthik", "Kavya", "Kiran", "Kritika", "Lakshmi", "Madhav", "Meera", "Mihir",
    "Mohan", "Nandini", "Neha", "Nikhil", "Nisha", "Omkar", "Pallavi", "Pranav",
    "Prerna", "Priya", "Rahul", "Rajesh", "Rakesh", "Ramesh", "Riya", "Rohit",
    "Saanvi", "Sahil", "Sanjay", "Sara", "Shreya", "Siddharth", "Sneha", "Suresh",
    "Tanvi", "Tara", "Varun", "Vidya", "Vikram", "Vivek", "Yash", "Zoya",
]
LAST_NAMES = [
    "Agarwal", "Bhat", "Chandra", "Chatterjee", "Desai", "Deshmukh", "Gandhi",
    "Gupta", "Hegde", "Iyer", "Iyer", "Joshi", "Kapoor", "Khan", "Kulkarni",
    "Kumar", "Malhotra", "Menon", "Mehta", "Mishra", "Nair", "Naidu", "Patel",
    "Pillai", "Rao", "Reddy", "Roy", "Saxena", "Sen", "Shah", "Sharma", "Shetty",
    "Singh", "Sinha", "Srinivasan", "Sundaram", "Trivedi", "Varma", "Verma", "Yadav",
]

# (name, category, min, max) — a receipt a real person would have.
EXPENSE_MERCHANTS = [
    ("BigBasket weekly groceries", "GROCERIES", 900, 4200),
    ("Zepto groceries order", "GROCERIES", 250, 1600),
    ("DMart monthly stock-up", "GROCERIES", 1400, 5200),
    ("Reliance Fresh vegetables", "GROCERIES", 180, 900),
    ("Dinner at Truffles", "RESTAURANTS", 600, 1900),
    ("Lunch at Saravana Bhavan", "RESTAURANTS", 250, 800),
    ("Dinner at Toit", "RESTAURANTS", 1200, 3200),
    ("Meghana Foods biryani", "RESTAURANTS", 350, 1100),
    ("Swiggy dinner order", "FOOD_DELIVERY", 250, 900),
    ("Zomato lunch order", "FOOD_DELIVERY", 180, 700),
    ("HP petrol top-up", "FUEL", 800, 2800),
    ("Indian Oil diesel", "FUEL", 1000, 3200),
    ("Metro card recharge", "PUBLIC_TRANSPORT", 200, 700),
    ("BMTC monthly pass", "PUBLIC_TRANSPORT", 500, 1200),
    ("Uber ride to office", "CAB_AND_AUTO", 180, 650),
    ("Ola airport drop", "CAB_AND_AUTO", 550, 1400),
    ("Rapido ride home", "CAB_AND_AUTO", 80, 320),
    ("BESCOM electricity bill", "ELECTRICITY", 800, 3400),
    ("ACT fibre broadband", "INTERNET", 799, 1599),
    ("Airtel prepaid recharge", "MOBILE_PHONE", 239, 899),
    ("Apollo Pharmacy order", "MEDICINES", 150, 1400),
    ("PharmEasy refill", "MEDICINES", 250, 1800),
    ("Consultation at Manipal", "DOCTOR_AND_CLINIC", 600, 2200),
    ("Practo video consult", "DOCTOR_AND_CLINIC", 400, 1200),
    ("Myntra clothing order", "CLOTHING", 700, 4500),
    ("Zudio weekend shopping", "CLOTHING", 900, 3200),
    ("PVR movie tickets", "MOVIES_AND_EVENTS", 450, 1600),
    ("BookMyShow concert", "MOVIES_AND_EVENTS", 1200, 4500),
    ("Third Wave Coffee", "COFFEE_AND_SNACKS", 150, 500),
    ("Chai Point morning chai", "COFFEE_AND_SNACKS", 40, 180),
    ("Heads Up For Tails", "PETS", 400, 1800),
    ("Cult.fit membership", "GYM_AND_FITNESS", 999, 2499),
    ("MakeMyTrip weekend trip", "TRAVEL_VACATION", 3500, 14000),
    ("Croma electronics", "ELECTRONICS", 1200, 9000),
    ("Amazon household order", "OTHERS", 200, 1800),
]

INCOME_MERCHANTS = [
    ("Monthly salary - Acme Corp", "SALARY", 65000, 145000),
    ("Upwork freelance payout", "FREELANCE", 5000, 38000),
    ("Zerodha dividend payout", "INVESTMENTS", 800, 9000),
    ("HDFC savings interest", "INTEREST", 300, 4000),
    ("Quarterly performance bonus", "BONUS", 15000, 60000),
    ("CRED cashback", "CASHBACK_REWARDS", 50, 900),
    ("Tax refund", "TAX_REFUND", 2000, 25000),
]

DESCRIPTIONS = ["Paid via UPI", "Card payment", "Auto-debit", "Paid in cash", ""]

DAILY_SUBSCRIPTIONS = [
    ("Amul milk delivery", 64.0, "GROCERIES"),
    ("The Hindu newspaper", 12.0, "OTHERS"),
]
WEEKLY_SUBSCRIPTIONS = [
    ("UClean laundry pickup", 350.0, "OTHERS"),
    ("House cleaning service", 900.0, "HOME_MAINTENANCE"),
]
MONTHLY_SUBSCRIPTIONS = [
    ("Netflix", 649.0, "OTT_SUBSCRIPTIONS"),
    ("Spotify Premium", 119.0, "OTT_SUBSCRIPTIONS"),
    ("Amazon Prime", 299.0, "OTT_SUBSCRIPTIONS"),
    ("Disney+ Hotstar", 299.0, "OTT_SUBSCRIPTIONS"),
    ("YouTube Premium", 149.0, "OTT_SUBSCRIPTIONS"),
    ("Airtel Xstream broadband", 999.0, "INTERNET"),
    ("Cult.fit membership", 1299.0, "GYM_AND_FITNESS"),
]
PATTERN_SUBSCRIPTION = ("Gym Membership", 1200.0, "GYM_AND_FITNESS")

# (description, expenseCategory, min, max) for shared expenses.
GROUP_EXPENSES = [
    ("Dinner at Toit", "RESTAURANTS", 1400, 3600),
    ("Lunch at Meghana Foods", "RESTAURANTS", 800, 2200),
    ("Weekend groceries at More", "GROCERIES", 1200, 3400),
    ("Ola to the airport", "CAB_AND_AUTO", 550, 1500),
    ("Uber back from Indiranagar", "CAB_AND_AUTO", 220, 700),
    ("Road trip fuel", "FUEL", 1100, 2800),
    ("Movie night at PVR", "MOVIES_AND_EVENTS", 600, 1900),
    ("Coffee run at Third Wave", "COFFEE_AND_SNACKS", 240, 900),
    ("Swiggy team lunch", "FOOD_DELIVERY", 500, 1800),
    ("Parking at Phoenix Mall", "PARKING_AND_TOLLS", 80, 300),
]
GROUP_NAMES = [
    "Flat 402 · Koramangala", "Office lunch crew", "Goa trip 2027", "Weekend hikers",
    "Sunday football", "Family errands", "Hostel wing B", "Running club",
    "Book club snacks", "Neighbourhood potluck",
]


# ────────────────────────────────────────────────────────────────────────────
# Metrics (thread-safe)
# ────────────────────────────────────────────────────────────────────────────
class Metrics:
    def __init__(self):
        self._lock = threading.Lock()
        self.status = defaultdict(Counter)
        self.latency = defaultdict(list)
        self.retries = Counter()
        self.throttled = Counter()
        self.breaker = Counter()
        self.failures = []
        self.checks = []
        self.cache_latency = defaultdict(list)   # name -> [seconds]

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
            if not ok:
                self.failures.append((owner, name, detail))

    def note_cache_latency(self, name, seconds):
        with self._lock:
            self.cache_latency[name].append(seconds)

    def percentile(self, phase, p):
        values = sorted(self.latency.get(phase, []))
        if not values:
            return 0.0
        idx = min(len(values) - 1, int(round((p / 100.0) * (len(values) - 1))))
        return values[idx]


# ────────────────────────────────────────────────────────────────────────────
# HTTP client (retry, shared back-off, multipart)
# ────────────────────────────────────────────────────────────────────────────
class Cooldown:
    """Shared client-side back-off: a 429/503 parks *every* simulated user for
    a short, growing interval so the gateway breaker's half-open probes land."""

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
    RETRY_BUDGET = (0.35, 0.7, 1.4, 2.8, 5.6, 8.0, 8.0, 8.0)

    def __init__(self, base_url, metrics, timeout=90.0, pace=0.06):
        self.base = base_url.rstrip("/")
        self.metrics = metrics
        self.timeout = timeout
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
        hdrs.setdefault("User-Agent", "pfa-golden-test/1.0")
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
        except Exception as exc:
            return 0, {"error": str(exc)}, {}

    def request(self, phase, path, method="GET", headers=None, body=None, files=None,
                timeout=None):
        effective_timeout = timeout or self.timeout
        started = time.time()
        status = 0
        try:
            for attempt in range(len(self.RETRY_BUDGET) + 1):
                self.cooldown.wait()
                if self.pace and method != "GET":
                    time.sleep(self.pace)
                status, payload, hdrs = self._once(path, method, headers, body, files,
                                                   effective_timeout)
                if status in RETRYABLE and attempt < len(self.RETRY_BUDGET):
                    self.metrics.retry(phase, status)
                    if status == 503:
                        self.cooldown.trigger(1.0)
                    elif status == 429:
                        self.cooldown.trigger(0.4)
                    backoff = self.RETRY_BUDGET[attempt]
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
        return json.loads(raw)
    except Exception:
        return {"raw": raw}


# ────────────────────────────────────────────────────────────────────────────
# Assertions and shared helpers
# ────────────────────────────────────────────────────────────────────────────
class Checks:
    def __init__(self, metrics, phase, owner):
        self.metrics, self.phase, self.owner = metrics, phase, owner

    def ok(self, condition, name, detail=""):
        self.metrics.check(self.phase, self.owner, name, bool(condition), detail)
        return bool(condition)

    def status(self, expected, actual, name):
        good = actual in expected if isinstance(expected, (tuple, set, list)) else actual == expected
        return self.ok(good, name, "" if good else f"expected {expected}, got {actual}")


def iso(dt):
    return dt.replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%S")


def money(value):
    return round(float(value), 2)


def poll(fn, predicate, timeout=10.0, interval=0.4):
    deadline = time.time() + timeout
    result = None
    while time.time() < deadline:
        result = fn()
        if predicate(result):
            return result, True
        time.sleep(interval)
    return result, False


def _think(args, rng):
    if getattr(args, "think_time", 0) > 0:
        time.sleep(rng.uniform(0, 2 * args.think_time))


def split_cents(amount, count):
    """Divide amount into `count` shares whose cents sum exactly to the amount."""
    total = int(round(amount * 100))
    base = total // count
    remainder = total % count
    return [round((base + (1 if i < remainder else 0)) / 100.0, 2) for i in range(count)]


def percentages(count):
    """`count` percentages, as decimal numbers, summing exactly to 100."""
    base = int(round(10000 / count))
    values = [base] * count
    values[-1] += 10000 - sum(values)
    return [round(v / 100.0, 2) for v in values]


# ────────────────────────────────────────────────────────────────────────────
# Cohort model
# ────────────────────────────────────────────────────────────────────────────
class Member:
    def __init__(self, idx, full_name, password, group_index):
        self.idx = idx
        self.full_name = full_name
        self.group_index = group_index
        slug = full_name.lower().replace(" ", ".")
        tag = uuid.uuid4().hex[:5]
        self.username = f"{slug}.{tag}"
        self.email = f"{slug}.{tag}@example.com"
        self.password = password
        self.user_id = None
        self.token = None
        self.refresh = None
        self.created = []          # [(id, type, amount)] still present
        self.expected_income = 0.0
        self.expected_expense = 0.0
        self.goal_id = None
        self.budget_id = None
        self.recurring_ids = []
        self.subscription_names = []
        self.receipt_urls = []     # urls uploaded for group attachments
        self.uploads = 0

    def headers(self):
        return {"Authorization": f"Bearer {self.token}", "X-User-Id": str(self.user_id)}


class GroupState:
    def __init__(self, index, members):
        self.index = index
        self.members = members
        self.group_id = None
        self.accepted = []        # members whose invitation the server accepted
        self.expense_ids = []
        self.deleted = 0
        self.settled = 0
        self.archived = 0
        self.lock = threading.Lock()


# ────────────────────────────────────────────────────────────────────────────
# Stage 1 — identity
# ────────────────────────────────────────────────────────────────────────────
def stage_register(client, member, checks):
    status, body, _ = client.request(
        "auth", "/auth/register", "POST",
        body={"email": member.email, "username": member.username,
              "password": member.password, "role": "USER"})
    checks.status((200, 201, 409), status, "register")
    if status in (200, 201):
        member.user_id = body.get("userId")

    status, body, _ = client.request(
        "auth", "/auth/login", "POST",
        body={"email": member.email, "password": member.password})
    checks.status(200, status, "login")
    checks.ok(body.get("token") is not None, "login returns a token")
    member.token = body.get("token")
    member.refresh = body.get("refreshToken")
    member.user_id = body.get("userId") or member.user_id
    checks.ok(member.user_id is not None, "login returns userId")


# ────────────────────────────────────────────────────────────────────────────
# Stage 2 — personal history, recurring subscriptions, goal, budget, deletes
# ────────────────────────────────────────────────────────────────────────────
def _create_entry(client, member, checks, body, headers=None):
    h = headers or member.headers()
    h.setdefault("X-Idempotency-Key", str(uuid.uuid4()))
    status, resp, _ = client.request("personal", "/upsert/create", "POST",
                                     headers=h, body=body)
    return status, resp


def stage_personal(client, member, checks, args, rng):
    now = datetime.now()
    uid = str(member.user_id)

    for i in range(args.txns):
        # Weighted towards recent months, like a real ledger: this month is
        # where most activity sits, older months taper off.
        month = rng.choices([0, 0, 0, 1, 1, 2, 3, 4, 5][:max(1, args.months * 2)],
                            k=1)[0] if args.months > 0 else 0
        days_back = month * 30 + rng.randint(0, 27)
        stamped = now - timedelta(days=min(days_back, 30 * args.months + 27),
                                  hours=rng.randint(0, 12), minutes=rng.randint(0, 59))
        is_income = rng.random() < 0.15
        if is_income:
            name, category, low, high = rng.choice(INCOME_MERCHANTS)
        else:
            name, category, low, high = rng.choice(EXPENSE_MERCHANTS)
        amount = money(rng.uniform(low, high))
        body = {
            "userId": uid,
            "name": name,
            "amount": amount,
            "type": "INCOME" if is_income else "EXPENSE",
            "category": category,
            "currency": "INR",
            "description": rng.choice(DESCRIPTIONS),
            "createdAt": iso(stamped),
        }
        status, resp = _create_entry(client, member, checks, body)
        if status == 201:
            member.created.append((resp.get("id"), body["type"], amount))
            if is_income:
                member.expected_income += amount
            else:
                member.expected_expense += amount
        else:
            checks.ok(False, "create transaction", f"status {status} body={str(resp)[:120]}")
        if i % 5 == 4:
            _think(args, rng)

    checks.ok(len(member.created) >= args.txns, "all transactions created",
              f"{len(member.created)}/{args.txns}")

    # ── Recurring subscriptions: DAILY + WEEKLY + two MONTHLY ───────────────
    # Each is backdated so its nextRunDate is already in the past. The hourly
    # RecurringTransactionScheduler must aggregate the missed periods, write a
    # TransactionEntry and emit an outbox event for it — that is the outbox
    # path this stage exists to load.
    daily = rng.choice(DAILY_SUBSCRIPTIONS)
    weekly = rng.choice(WEEKLY_SUBSCRIPTIONS)
    monthly = rng.sample(MONTHLY_SUBSCRIPTIONS, 2)
    specs = [
        (daily, "DAILY", 4),
        (weekly, "WEEKLY", 21),
        (monthly[0], "MONTHLY", 65),
        (monthly[1], "MONTHLY", 35),
    ]
    for (label, amount, category), period, days_back in specs:
        stamped = now - timedelta(days=days_back, hours=rng.randint(0, 8))
        status, resp = _create_entry(
            client, member, checks,
            {"userId": uid, "name": label, "amount": amount, "type": "EXPENSE",
             "category": category, "currency": "INR",
             "description": f"{period.lower()} subscription", "recurring": True,
             "recurringPeriod": period, "createdAt": iso(stamped)})
        checks.status(201, status, f"create {period.lower()} subscription {label}")
        if status == 201:
            member.created.append((resp.get("id"), "EXPENSE", amount))
            member.expected_expense += amount
            member.recurring_ids.append(resp.get("id"))
            member.subscription_names.append(label)

    # The daily detector also looks for the same merchant twice inside 90 days.
    for months_ago in (2, 1):
        stamped = now - timedelta(days=30 * months_ago)
        _create_entry(client, member, checks,
                      {"userId": uid, "name": PATTERN_SUBSCRIPTION[0],
                       "amount": PATTERN_SUBSCRIPTION[1], "type": "EXPENSE",
                       "category": PATTERN_SUBSCRIPTION[2], "currency": "INR",
                       "description": "pattern seed", "createdAt": iso(stamped)})

    # ── Savings goal ────────────────────────────────────────────────────────
    status, resp, _ = client.request(
        "personal", "/upsert/goals", "POST", headers=member.headers(),
        body={"userId": uid, "name": rng.choice(
                  ["Emergency Fund", "Goa trip", "New laptop", "Home down payment"]),
              "targetAmount": 50000.0, "currency": "INR",
              "description": "golden scenario goal", "deadline": "2027-12-31"})
    checks.status(201, status, "create goal")
    member.goal_id = resp.get("id")
    if member.goal_id:
        status, resp, _ = client.request(
            "personal", f"/upsert/goals/{member.goal_id}/contribute?userId={uid}",
            "PATCH", headers=member.headers(),
            body={"amount": 1000.0, "description": "first contribution"})
        checks.status(200, status, "contribute to goal")

    # ── Budget for a category the seed actually uses ────────────────────────
    status, resp, _ = client.request(
        "personal", "/upsert/budgets", "POST", headers=member.headers(),
        body={"userId": uid, "expenseCategory": "RESTAURANTS",
              "budgetAmount": 50000.0, "period": "MONTHLY", "currency": "INR"})
    checks.status(201, status, "create budget")
    member.budget_id = resp.get("budgetId") or resp.get("id")

    # ── Delete a random subset in parallel across the cohort ────────────────
    # Recurring rows are excluded: they are the fixtures the subscription and
    # recurrence checks assert on, and deleting one would make the read-model
    # checks fail for a reason that has nothing to do with the application.
    recurring = set(member.recurring_ids)
    deletable = [rec for rec in member.created if rec[0] not in recurring]
    victims = rng.sample(deletable, k=min(args.deletes, len(deletable)))
    for tx_id, tx_type, amount in victims:
        status, _, _ = client.request(
            "personal", f"/upsert/delete/{tx_id}?userId={member.user_id}",
            "DELETE", headers=member.headers())
        checks.status((200, 204), status, "delete transaction")
        if status in (200, 204):
            member.created.remove((tx_id, tx_type, amount))
            if tx_type == "INCOME":
                member.expected_income -= amount
            else:
                member.expected_expense -= amount


# ────────────────────────────────────────────────────────────────────────────
# Stage 3 — security and concurrency races
# ────────────────────────────────────────────────────────────────────────────
def stage_refresh_race(client, member, checks, race=True):
    # Realistic mode exercises the rotation race for a small, seeded subset of
    # users only. A real client refreshes once per access-token lifetime
    # (15 min), not four times the instant it logs in, so firing the race for
    # every user in one burst measures a workload that does not exist.
    if not race:
        return
    replay = member.refresh
    if not replay:
        return

    def hit(_):
        return client.request("auth", "/auth/refresh", "POST",
                              body={"refreshToken": replay})[0]

    with ThreadPoolExecutor(max_workers=4) as pool:
        codes = list(pool.map(hit, range(4)))
    checks.ok(sum(1 for c in codes if c == 200) == 1,
              "concurrent refresh rotates exactly once", f"statuses={codes}")

    status, body, _ = client.request(
        "auth", "/auth/login", "POST",
        body={"email": member.email, "password": member.password})
    checks.status(200, status, "re-login after refresh race")
    if status == 200 and body.get("token"):
        member.token = body["token"]
        member.refresh = body.get("refreshToken") or member.refresh


def stage_idempotency(client, member, checks):
    key = str(uuid.uuid4())
    payload = {"userId": str(member.user_id), "name": "Idempotent double submit",
               "amount": 777.0, "type": "EXPENSE", "category": "OTHERS",
               "currency": "INR", "description": "double submit",
               "createdAt": iso(datetime.now())}
    headers = member.headers()
    headers["X-Idempotency-Key"] = key

    def hit(_):
        return client.request("personal", "/upsert/create", "POST",
                              headers=dict(headers), body=payload)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(hit, range(2)))
    statuses = [s for s, _, _ in results]
    ids = {b.get("id") for s, b, _ in results if s == 201 and isinstance(b, dict)}
    checks.ok(all(s == 201 for s in statuses),
              "both idempotent submits accepted", f"statuses={statuses}")
    checks.ok(len(ids) == 1, "idempotent submits share one row", f"ids={ids}")
    if len(ids) == 1:
        member.created.append((next(iter(ids)), "EXPENSE", 777.0))
        member.expected_expense += 777.0


def stage_authz(client, member, checks, group):
    if group is None or len(group.members) < 2:
        return
    victim = group.members[(group.members.index(member) + 1) % len(group.members)]
    if not victim.created:
        return
    victim_txn, _, _ = victim.created[0]
    uid = member.user_id

    status, _, _ = client.request(
        "personal", f"/upsert/entries/{victim_txn}?userId={uid}", "GET",
        headers=member.headers())
    checks.ok(status in (403, 404), "foreign entry id not readable", f"got {status}")

    status, _, _ = client.request(
        "personal", f"/upsert/summary?userId={victim.user_id}", "GET",
        headers=member.headers())
    checks.ok(status in (403, 404), "foreign summary not readable", f"got {status}")


# ────────────────────────────────────────────────────────────────────────────
# Stage 4 — richer flows: subscriptions, bulk, search, filters, analytics
# ────────────────────────────────────────────────────────────────────────────
def stage_complex(client, member, checks, args, rng):
    now = datetime.now()
    uid = member.user_id
    phase = "personal"

    # ── Subscriptions: every seeded merchant must surface ───────────────────
    (status, subs), seen = poll(
        lambda: client.request(phase, f"/upsert/subscriptions?userId={uid}", "GET",
                               headers=member.headers())[:2],
        lambda r: isinstance(r[1], list) and all(
            any(s.get("name") == name for s in r[1]) for name in member.subscription_names),
        timeout=max(10.0, args.verify_timeout))
    checks.status(200, status, "list subscriptions")
    checks.ok(seen, "every seeded subscription is detected",
              f"names={sorted(s.get('name') for s in subs)[:8] if isinstance(subs, list) else subs}")
    _think(args, rng)

    # ── Recurring listing (daily + monthly visible through the read model) ──
    (status, recurring), seen = poll(
        lambda: client.request(phase, f"/upsert/recurring?userId={uid}", "GET",
                               headers=member.headers())[:2],
        lambda r: isinstance(r[1], list) and {"DAILY", "MONTHLY"} <= {
            e.get("recurringPeriod") for e in r[1] if e.get("recurring")},
        timeout=max(10.0, args.verify_timeout))
    checks.status(200, status, "recurring list")
    checks.ok(seen, "daily and monthly recurrences are visible")
    _think(args, rng)

    # ── Every recurring row must reach the read model (outbox/CDC path) ─────
    if member.recurring_ids:
        rid = member.recurring_ids[0]
        (status, _), seen = poll(
            lambda: client.request(phase, f"/upsert/entries/{rid}?userId={uid}", "GET",
                                   headers=member.headers())[:2],
            lambda r: r[0] == 200, timeout=max(10.0, args.verify_timeout))
        checks.ok(seen, "recurring entry replicated to the read model", f"id={rid} got {status}")
    _think(args, rng)

    # ── Budget utilizations read model ──────────────────────────────────────
    (status, body), seen = poll(
        lambda: client.request(phase, f"/upsert/budgets?userId={uid}", "GET",
                               headers=member.headers())[:2],
        lambda r: isinstance(r[1], list) and len(r[1]) > 0,
        timeout=max(10.0, args.verify_timeout))
    checks.status(200, status, "list budgets")
    checks.ok(seen, "budget appears in listing")
    _think(args, rng)

    status, _, _ = client.request(
        "analytics", f"/analytics/budgets/trends?userId={uid}", "GET",
        headers=member.headers())
    checks.status(200, status, "budget trends")
    _think(args, rng)

    # ── Bulk create, then bulk-delete half of it ────────────────────────────
    bulk = [{"userId": str(uid), "name": f"Bulk import row {k}",
             "amount": money(12.5 + k), "type": "EXPENSE", "category": "OTHERS",
             "currency": "INR", "description": "bulk import", "createdAt": iso(now)}
            for k in range(4)]
    status, created, _ = client.request(
        phase, "/upsert/create/bulk", "POST", headers=member.headers(), body=bulk)
    checks.ok(status in (200, 201), "bulk create", f"got {status}")
    bulk_ids = []
    if isinstance(created, list):
        for row in created:
            amount = float(row.get("amount", 0) or 0)
            bulk_ids.append(row.get("id"))
            member.created.append((row.get("id"), "EXPENSE", amount))
            member.expected_expense += amount
    _think(args, rng)

    if bulk_ids:
        victims = bulk_ids[:2]
        status, _, _ = client.request(
            phase, f"/upsert/delete/bulk?userId={uid}", "POST",
            headers=member.headers(), body=victims)
        checks.status(200, status, "bulk delete")
        if status == 200:
            for vid in victims:
                for rec in list(member.created):
                    if rec[0] == vid:
                        member.created.remove(rec)
                        member.expected_expense -= rec[2]
                        break
    _think(args, rng)

    # ── Date-filtered listing: every row must fall inside the window ────────
    start_d = (now - timedelta(days=45)).strftime("%Y-%m-%d")
    end_d = now.strftime("%Y-%m-%d")
    status, body, _ = client.request(
        phase,
        f"/upsert/entries?userId={uid}&startDate={start_d}&endDate={end_d}&size=200",
        "GET", headers=member.headers())
    checks.status(200, status, "date-filtered listing")
    rows = body.get("content", []) if isinstance(body, dict) else []
    outside = [e for e in rows
               if str(e.get("createdAt", ""))[:10] < start_d
               or str(e.get("createdAt", ""))[:10] > end_d]
    checks.ok(not outside, "date filter excludes out-of-range rows", f"{len(outside)} outside")
    _think(args, rng)

    # ── Analytics read model ────────────────────────────────────────────────
    analytics = [
        ("category pie chart", f"/analytics/category-pie-chart?userId={uid}"),
        ("timeline chart", f"/analytics/timeline-chart?userId={uid}"),
        ("comprehensive analytics", f"/analytics/comprehensive?userId={uid}"),
        ("analytics entries page", f"/analytics/transaction-entries?userId={uid}&page=0&size=20"),
        ("health score", f"/analytics/health-score?userId={uid}"),
        ("income by category",
         f"/analytics/transactions/income-by-category?userId={uid}"
         f"&categoryName=SALARY&startDate={start_d}&endDate={end_d}"),
        ("transactions by type",
         f"/analytics/transactions/by-type?userId={uid}"
         f"&transactionType=EXPENSE&startDate={start_d}&endDate={end_d}"),
    ]
    if member.goal_id:
        analytics.append(("goal forecast",
                          f"/analytics/goals/{member.goal_id}/forecast?userId={uid}"))
    for label, path in analytics:
        status, _, _ = client.request("analytics", path, "GET", headers=member.headers())
        checks.status(200, status, label)
        _think(args, rng)


# ────────────────────────────────────────────────────────────────────────────
# Stage 5 — cache eviction and read consistency, per user
# ────────────────────────────────────────────────────────────────────────────
def _summary(client, member):
    status, body, _ = client.request(
        "personal", f"/upsert/summary?userId={member.user_id}", "GET",
        headers=member.headers())
    return status, body if isinstance(body, dict) else {}


def _total(summary, tx_type):
    key = "totalIncome" if tx_type == "INCOME" else "totalExpense"
    return float(summary.get(key, 0) or 0)


def stage_cache(client, member, checks, args, rng):
    """Prove that writes evict cached reads and that the read model converges.

    Reads are cached in Redis with a TTL, so a write that fails to evict would
    keep serving the old value for the whole TTL. Each check polls until the
    read model agrees, with a bounded budget: convergence inside the budget is
    a pass, and the time it took is recorded so slow evictions show up in the
    report rather than hiding behind an average.
    """
    uid = member.user_id
    eps = 0.05
    budget = max(10.0, args.cache_timeout)

    # Warm the summary cache with repeated reads.
    for _ in range(2):
        _summary(client, member)

    if not member.created:
        checks.ok(False, "cache stage has a transaction to mutate")
        return

    status, baseline_summary = _summary(client, member)
    checks.status(200, status, "summary readable before update")
    entry_id, tx_type, amount = member.created[0]
    delta = 100.0
    new_amount = money(amount + delta)
    baseline = _total(baseline_summary, tx_type)

    started = time.time()
    status, body, _ = client.request(
        "personal", f"/upsert/entries/{entry_id}/amount?userId={uid}",
        "PATCH", headers=member.headers(), body={"amount": new_amount})
    checks.status(200, status, "update amount")
    if status == 200:
        member.created[0] = (entry_id, tx_type, new_amount)
        if tx_type == "INCOME":
            member.expected_income += delta
        else:
            member.expected_expense += delta

        # The cached summary was warm at `baseline`. It must now include the
        # +delta. Anything else the user wrote only pushes the total further
        # up, so a floor comparison is race-free.
        (status, summary), converged = poll(
            lambda: _summary(client, member),
            lambda r: r[0] == 200 and _total(r[1], tx_type) >= baseline + delta - eps,
            timeout=budget)
        checks.ok(converged, "cached summary reflects an amount update",
                  f"{_total(summary, tx_type):.2f} >= {baseline + delta:.2f}")
        checks.metrics.note_cache_latency("summary after update", time.time() - started)

        # The entry itself must show the new amount.
        (status, entry), seen = poll(
            lambda: client.request("personal", f"/upsert/entries/{entry_id}?userId={uid}",
                                   "GET", headers=member.headers())[:2],
            lambda r: r[0] == 200 and isinstance(r[1], dict)
            and abs(float(r[1].get("amount", 0) or 0) - new_amount) < eps,
            timeout=budget)
        checks.ok(seen, "entry read reflects the patched amount",
                  f"status={status} body={str(entry)[:120]}")

        # Delete the row. It must leave the summary and must not reappear: a
        # stale refill would keep a deleted row visible for the cache TTL.
        _, fresh = _summary(client, member)
        before_delete = _total(fresh, tx_type)
        started = time.time()
        status, _, _ = client.request(
            "personal", f"/upsert/delete/{entry_id}?userId={uid}",
            "DELETE", headers=member.headers())
        checks.status((200, 204), status, "delete mutated transaction")
        if status in (200, 204):
            member.created.remove((entry_id, tx_type, new_amount))
            if tx_type == "INCOME":
                member.expected_income -= new_amount
            else:
                member.expected_expense -= new_amount

            (status, summary), converged = poll(
                lambda: _summary(client, member),
                lambda r: r[0] == 200 and _total(r[1], tx_type) <= before_delete - new_amount + eps,
                timeout=budget)
            checks.ok(converged, "cached summary drops a deleted row",
                      f"{_total(summary, tx_type):.2f} <= "
                      f"{before_delete - new_amount:.2f}")
            checks.metrics.note_cache_latency("summary after delete", time.time() - started)

            status, _, _ = client.request(
                "personal", f"/upsert/entries/{entry_id}?userId={uid}", "GET",
                headers=member.headers())
            checks.ok(status in (403, 404), "deleted entry is not readable", f"got {status}")

    # ── Parallel identical reads must agree (cache coherence) ───────────────
    probe = f"Golden cache probe {uuid.uuid4().hex[:8]}"
    status, resp = _create_entry(
        client, member, checks,
        {"userId": str(uid), "name": probe, "amount": 3.0, "type": "EXPENSE",
         "category": "OTHERS", "currency": "INR", "createdAt": iso(datetime.now())})
    if status == 201:
        member.created.append((resp.get("id"), "EXPENSE", 3.0))
        member.expected_expense += 3.0
        q = urllib.parse.quote(probe)
        (status, _), seen = poll(
            lambda: client.request("personal",
                                   f"/upsert/search?userId={uid}&q={q}", "GET",
                                   headers=member.headers())[:2],
            lambda r: isinstance(r[1], dict) and any(
                row.get("name") == probe for row in r[1].get("content", [])),
            timeout=budget)
        checks.ok(seen, "search finds the freshly created row")

        def read_search(_):
            status, body, _ = client.request(
                "personal", f"/upsert/search?userId={uid}&q={q}", "GET",
                headers=member.headers())
            rows = body.get("content", []) if isinstance(body, dict) else []
            return status, sorted((str(r.get("id")), r.get("name"),
                                   float(r.get("amount", 0) or 0)) for r in rows)

        with ThreadPoolExecutor(max_workers=6) as pool:
            answers = list(pool.map(read_search, range(6)))
        checks.ok(all(s == 200 for s, _ in answers), "parallel cached reads are served",
                  f"statuses={[s for s, _ in answers]}")
        checks.ok(len({tuple(a) for _, a in answers}) == 1,
                  "parallel cached reads agree",
                  f"{len({tuple(a) for _, a in answers})} distinct answers")


# ────────────────────────────────────────────────────────────────────────────
# Stage 6 — OCR uploads from the 200-image dataset
# ────────────────────────────────────────────────────────────────────────────
def _dataset_files(samples):
    files = [os.path.join(samples, f) for f in sorted(os.listdir(samples))
             if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".pdf"))]
    return [f for f in files if os.path.isfile(f)]


def stage_ocr(client, member, checks, args, rng):
    """Upload ~10 receipts at random. Some of them become personal transactions
    with a receiptUrl; the rest are kept for attachment to a group expense, so
    the same receipt flow feeds both transaction capture and split bills."""
    if args.bills <= 0:
        return
    files = _dataset_files(args.samples)
    if not files:
        checks.ok(False, "receipt dataset present", args.samples)
        return
    chosen = rng.sample(files, k=min(args.bills, len(files)))
    for path in chosen:
        status, body, _ = client.request(
            "ocr", f"/bill/process/{member.user_id}", "POST",
            headers={"Authorization": f"Bearer {member.token}"},
            files={"file": path}, timeout=180)
        checks.status(202, status, f"receipt upload {os.path.basename(path)}")
        receipt_url = body.get("url") if isinstance(body, dict) else None
        checks.ok(body.get("receiptId") is not None,
                  "receipt id returned", str(body)[:120])
        member.uploads += 1
        if not receipt_url:
            continue
        if rng.random() < 0.6:
            # Attach the receipt to a personal transaction.
            name, category, low, high = rng.choice(EXPENSE_MERCHANTS)
            amount = money(rng.uniform(low, high))
            status, resp = _create_entry(
                client, member, checks,
                {"userId": str(member.user_id), "name": name,
                 "amount": amount, "type": "EXPENSE",
                 "category": category, "currency": "INR",
                 "description": "Receipt captured from OCR upload",
                 "receiptUrl": receipt_url, "createdAt": iso(datetime.now())})
            checks.status(201, status, "transaction from OCR receipt")
            if status == 201:
                member.created.append((resp.get("id"), "EXPENSE", amount))
                member.expected_expense += amount
        else:
            member.receipt_urls.append(receipt_url)
        _think(args, rng)
    checks.ok(member.uploads == min(args.bills, len(files)),
              "every receipt upload accepted", f"{member.uploads} uploaded")


# ────────────────────────────────────────────────────────────────────────────
# Stages 7-11 — groups
# ────────────────────────────────────────────────────────────────────────────
def stage_group_create(client, group, checks, args):
    leader = group.members[0]
    status, body, _ = client.request(
        "groups", "/upsert/groups", "POST", headers=leader.headers(),
        body={"name": GROUP_NAMES[group.index % len(GROUP_NAMES)],
              "description": "golden scenario group",
              "createdBy": str(leader.user_id)})
    checks.status(201, status, "create group")
    group.group_id = body.get("id")
    if not group.group_id:
        return
    group.accepted = [leader]

    for peer in group.members[1:]:
        status, _, _ = client.request(
            "groups", f"/upsert/groups/{group.group_id}/members", "POST",
            headers=leader.headers(),
            body={"userId": str(peer.user_id), "name": peer.full_name})
        checks.status(201, status, f"invite member {peer.idx}")


def stage_group_accept(client, group, member, checks):
    if group is None or not group.group_id or member is group.members[0]:
        return
    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/members/{member.user_id}/accept",
        "POST", headers=member.headers())
    checks.status(200, status, "accept invitation")
    if status == 200:
        with group.lock:
            if member not in group.accepted:
                group.accepted.append(member)


def _split_payload(split_type, amount, group):
    # The server only splits among members whose invitation was accepted, so
    # PERCENTAGE/EXACT details must name that same set (otherwise the total
    # check rejects the request).
    members = group.accepted or group.members
    if split_type == "PERCENTAGE":
        pcts = percentages(len(members))
        return [{"userId": str(m.user_id), "userName": m.full_name, "value": p}
                for m, p in zip(members, pcts)]
    if split_type == "EXACT":
        shares = split_cents(amount, len(members))
        return [{"userId": str(m.user_id), "userName": m.full_name, "value": s}
                for m, s in zip(members, shares)]
    return None


def stage_group_expenses(client, group, member, checks, args, rng):
    if group is None or not group.group_id:
        return
    for n in range(args.expenses):
        description, category, low, high = rng.choice(GROUP_EXPENSES)
        amount = money(rng.uniform(low, high))
        split_type = rng.choices(["EQUAL", "PERCENTAGE", "EXACT"],
                                 weights=[7, 2, 1], k=1)[0]
        body = {"amount": amount,
                "description": f"{description} · {member.full_name.split()[0]}",
                "paidBy": str(member.user_id),
                "splitType": split_type,
                "expenseCategory": category,
                "currency": "INR"}
        details = _split_payload(split_type, amount, group)
        if details:
            body["splitDetails"] = details
        if member.receipt_urls and rng.random() < 0.5:
            body["receiptUrl"] = member.receipt_urls.pop()
        status, resp, _ = client.request(
            "groups", f"/upsert/groups/{group.group_id}/expenses", "POST",
            headers=member.headers(), body=body)
        checks.status(201, status, f"post shared expense {n}")
        if status == 201 and resp.get("id") is not None:
            with group.lock:
                group.expense_ids.append(resp["id"])

        if n == 0:
            uid = member.user_id
            status, _, _ = client.request(
                "analytics", f"/analytics/timeline-chart?userId={uid}", "GET",
                headers=member.headers())
            checks.status(200, status, "analytics during writes")
            status, _, _ = client.request(
                "analytics", f"/analytics/comprehensive?userId={uid}", "GET",
                headers=member.headers())
            checks.status(200, status, "comprehensive analytics during writes")


def _balances(client, group, member):
    status, body, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/balances", "GET",
        headers=member.headers())
    return status, body


def _balance_signature(balances):
    """Order-independent, rounding-stable view of a balances payload."""
    if not isinstance(balances, dict):
        return None
    members = tuple(sorted(
        (str(m.get("userId")), round(float(m.get("netBalance", 0) or 0), 2))
        for m in balances.get("memberBalances") or []))
    debts = tuple(sorted(
        (str(d.get("fromUserId")), str(d.get("toUserId")),
         round(float(d.get("amount", 0) or 0), 2))
        for d in balances.get("simplifiedDebts") or []))
    return members, debts


def stage_group_cache(client, group, checks, args):
    """Warm the group-balances cache, add a canary expense, then delete it.

    Both transitions must be visible through the cached endpoint: this is the
    group-level eviction path (queryCacheEvictor.evictGroupKeys + the guard),
    not just the user-level one exercised in stage 5.
    """
    if not group.group_id or not group.members:
        return
    leader = group.members[0]
    budget = max(10.0, args.cache_timeout)

    # The group projection is still catching up with the expenses posted in the
    # previous stage, so a single 200 is not a baseline. Wait until the read
    # model lists every expense this group actually posted — a stability window
    # alone is not enough when a replica can pause for seconds and then deliver
    # a late row after the baseline was taken.
    def regular_expense_count():
        status, body, _ = client.request(
            "groups", f"/upsert/groups/{group.group_id}/expenses?page=0&size=200",
            "GET", headers=leader.headers())
        rows = body.get("content", []) if isinstance(body, dict) else []
        return status, len([r for r in rows if r.get("splitType") != "SETTLEMENT"])

    (status, listed), settled = poll(
        regular_expense_count,
        lambda r: r[0] == 200 and r[1] == len(group.expense_ids),
        timeout=budget)
    checks.ok(settled, "group expense projection settled before the canary",
              f"{listed} listed, {len(group.expense_ids)} posted")

    # Warm the cache, then require two consecutive identical payloads before
    # treating the balances as settled.
    for _ in range(2):
        _balances(client, group, leader)

    def stable_baseline():
        s1, b1 = _balances(client, group, leader)
        if s1 != 200 or not isinstance(b1, dict):
            return None
        sig1 = _balance_signature(b1)
        time.sleep(0.6)
        s2, b2 = _balances(client, group, leader)
        if s2 != 200 or not isinstance(b2, dict):
            return None
        return sig1 if _balance_signature(b2) == sig1 else None

    status, before_sig = 0, None
    deadline = time.time() + budget
    while time.time() < deadline:
        status, _ = _balances(client, group, leader)
        if status == 200:
            before_sig = stable_baseline()
            if before_sig is not None:
                break
        time.sleep(0.4)
    checks.status(200, status, "warm group balances")
    if before_sig is None:
        return

    canary_amount = 2016.00
    status, resp, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/expenses", "POST",
        headers=leader.headers(),
        body={"amount": canary_amount,
              "description": "Cache canary · rooftop dinner",
              "paidBy": str(leader.user_id), "splitType": "EQUAL",
              "expenseCategory": "RESTAURANTS", "currency": "INR"})
    checks.status(201, status, "post cache canary expense")
    canary_id = resp.get("id") if isinstance(resp, dict) else None
    if not canary_id:
        return

    started = time.time()
    (status, after), changed = poll(
        lambda: _balances(client, group, leader),
        lambda r: r[0] == 200 and _balance_signature(r[1]) != before_sig,
        timeout=budget)
    checks.ok(changed, "group balances cache evicts after a new expense",
              f"status={status} before={before_sig} after={_balance_signature(after)}")
    checks.metrics.note_cache_latency("balances after create", time.time() - started)

    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/expenses/{canary_id}",
        "DELETE", headers=leader.headers())
    checks.status(200, status, "delete cache canary expense")
    if status != 200:
        return

    started = time.time()
    (status, restored), back = poll(
        lambda: _balances(client, group, leader),
        lambda r: r[0] == 200 and _balance_signature(r[1]) == before_sig,
        timeout=budget)
    checks.ok(back, "group balances cache evicts after the canary is deleted",
              f"status={status} expected={before_sig} "
              f"observed={_balance_signature(restored)}")
    checks.metrics.note_cache_latency("balances after delete", time.time() - started)


def stage_settle_or_archive(client, group, member, checks, args, rng):
    if group is None or not group.group_id:
        return
    position = group.members.index(member)

    if position < len(group.members) // 2:
        (status, balances), _ = poll(
            lambda: _balances(client, group, member),
            lambda r: r[0] == 200 and isinstance(r[1], dict)
            and bool(r[1].get("simplifiedDebts")),
            timeout=25)
        checks.status(200, status, "read balances before settling")
        debts = balances.get("simplifiedDebts", []) if isinstance(balances, dict) else []
        mine = [d for d in debts if str(d.get("fromUserId")) == str(member.user_id)]
        target = (mine or debts or [None])[0]
        if target:
            s, _, _ = client.request(
                "groups",
                f"/upsert/groups/{group.group_id}/settle"
                f"?fromUserId={target.get('fromUserId')}&toUserId={target.get('toUserId')}",
                "POST", headers=member.headers())
            checks.status(200, s, "settle debt")
            if s == 200:
                with group.lock:
                    group.settled += 1
        else:
            checks.ok(False, "settle debt", "no debt to settle")
        return

    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/archive?archive=true",
        "PUT", headers=member.headers())
    checks.status((200, 204), status, "archive membership")
    if status in (200, 204):
        with group.lock:
            group.archived += 1


def stage_group_deletes(client, group, checks, args, rng):
    if not group.group_id or not group.expense_ids:
        return
    deleters = group.members[:min(5, len(group.members))]
    with group.lock:
        rng.shuffle(group.expense_ids)
        victims = group.expense_ids[:min(len(deleters), len(group.expense_ids))]

    for member, expense_id in zip(deleters, victims):
        status, _, _ = client.request(
            "groups",
            f"/upsert/groups/{group.group_id}/expenses/{expense_id}",
            "DELETE", headers=member.headers())
        checks.status(200, status, "delete shared expense")
        if status == 200:
            with group.lock:
                group.deleted += 1
                if expense_id in group.expense_ids:
                    group.expense_ids.remove(expense_id)


# ────────────────────────────────────────────────────────────────────────────
# Stage 12 — verification
# ────────────────────────────────────────────────────────────────────────────
def _read_listing(client, member):
    """Sum the transaction listing with a page size that misses its cache key."""
    uid = member.user_id
    listed, income, expense, page = 0, 0.0, 0.0, 0
    while page < 60:
        status, body, _ = client.request(
            "personal", f"/upsert/entries?userId={uid}&page={page}&size=173",
            "GET", headers=member.headers())
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
        page += 1
        if listed >= int(body.get("totalElements", listed) or listed):
            break
    return listed, income, expense


def verify_member(client, member, checks, args):
    uid = member.user_id
    deadline = time.time() + args.verify_timeout

    summary = {}
    while time.time() < deadline:
        status, summary = _summary(client, member)
        if status == 200 and int(summary.get("totalCount", 0) or 0) >= len(member.created):
            break
        time.sleep(0.5)
    checks.status(200, status, "summary reachable")
    checks.ok(int(summary.get("totalCount", 0) or 0) >= len(member.created),
              "summary sees the seeded transactions",
              f"read {summary.get('totalCount')} >= written {len(member.created)}")

    # Summary must equal the listing sum once both have settled. OCR rows can
    # land while they are compared, so re-read both sides until they agree.
    listed, income, expense = _read_listing(client, member)
    eps = 0.05
    settle_deadline = time.time() + args.verify_timeout
    while True:
        si = float(summary.get("totalIncome", 0) or 0)
        se = float(summary.get("totalExpense", 0) or 0)
        if abs(si - income) < eps and abs(se - expense) < eps:
            break
        if time.time() >= settle_deadline:
            break
        time.sleep(0.5)
        listed, income, expense = _read_listing(client, member)
        status, summary = _summary(client, member)

    checks.ok(abs(float(summary.get("totalIncome", 0) or 0) - income) < eps,
              "summary income equals listing sum",
              f"{summary.get('totalIncome')} vs {income:.2f}")
    checks.ok(abs(float(summary.get("totalExpense", 0) or 0) - expense) < eps,
              "summary expense equals listing sum",
              f"{summary.get('totalExpense')} vs {expense:.2f}")
    checks.ok(float(summary.get("totalIncome", 0) or 0) >= member.expected_income - eps,
              "income at least what was written",
              f"{summary.get('totalIncome')} >= {member.expected_income:.2f}")
    checks.ok(float(summary.get("totalExpense", 0) or 0) >= member.expected_expense - eps,
              "expense at least what was written",
              f"{summary.get('totalExpense')} >= {member.expected_expense:.2f}")

    # Cross-endpoint consistency: the analytics projection must agree with the
    # summary projection for the same user. Both are cached independently and
    # both are re-read inside the poll: OCR rows can land between comparisons,
    # so pinning one side to an older snapshot would make this disagree forever.
    def both_totals():
        s1, summ = _summary(client, member)
        s2, comp = client.request(
            "analytics", f"/analytics/comprehensive?userId={uid}", "GET",
            headers=member.headers())[:2]
        return s1, s2, summ, comp if isinstance(comp, dict) else {}

    (s1, s2, summ, comp), agree = poll(
        both_totals,
        lambda r: r[0] == 200 and r[1] == 200 and isinstance(r[3], dict)
        and abs(float(r[3].get("totalIncome", 0) or 0)
                - float(r[2].get("totalIncome", 0) or 0)) < eps
        and abs(float(r[3].get("totalExpense", 0) or 0)
                - float(r[2].get("totalExpense", 0) or 0)) < eps,
        timeout=max(10.0, args.verify_timeout))
    checks.ok(agree, "analytics totals agree with summary",
              f"analytics=({comp.get('totalIncome')}, {comp.get('totalExpense')}) "
              f"summary=({summ.get('totalIncome')}, {summ.get('totalExpense')})")

    status, body, _ = client.request("personal", f"/upsert/recurring?userId={uid}",
                                     "GET", headers=member.headers())
    checks.status(200, status, "recurring list")
    status, body, _ = client.request("personal", f"/upsert/goals?userId={uid}",
                                     "GET", headers=member.headers())
    checks.status(200, status, "goals list")

    for path, label in (
        (f"/analytics/category-pie-chart?userId={uid}", "category pie chart"),
        (f"/analytics/timeline-chart?userId={uid}", "timeline chart"),
        (f"/analytics/health-score?userId={uid}", "health score"),
    ):
        status, _, _ = client.request("analytics", path, "GET", headers=member.headers())
        checks.status(200, status, label)


def check_balances_settle(checks, balances, label):
    """Nets must sum to zero and every suggestion must clear the debt it claims.

    Split rounding previously let one expense's shares drift a few paise from
    the amount paid, leaving a balance no suggestion could clear; this asserts
    the arithmetic, not just a 200."""
    if not isinstance(balances, dict):
        checks.ok(False, f"{label}: balances payload", repr(balances)[:200])
        return

    members = balances.get("memberBalances") or []
    debts = balances.get("simplifiedDebts") or []

    net_total = round(sum(float(m.get("netBalance", 0) or 0) for m in members), 2)
    checks.ok(abs(net_total) < 0.005,
              f"{label}: net balances sum to zero",
              f"sum={net_total} over {len(members)} members")

    outstanding = {str(m.get("userId")): round(float(m.get("netBalance", 0) or 0), 2)
                   for m in members}
    for d in debts:
        amount = round(float(d.get("amount", 0) or 0), 2)
        frm, to = str(d.get("fromUserId")), str(d.get("toUserId"))
        outstanding[frm] = round(outstanding.get(frm, 0) + amount, 2)
        outstanding[to] = round(outstanding.get(to, 0) - amount, 2)
    unresolved = {u: v for u, v in outstanding.items() if abs(v) >= 0.005}
    checks.ok(not unresolved,
              f"{label}: suggestions settle every member",
              f"unresolved={unresolved}")


def verify_group(client, group, checks, args):
    if not group.group_id:
        checks.ok(False, "group exists", f"group {group.index}")
        return
    leader = group.members[0]

    # Exactly the expenses that were posted and not deleted. The cache canary
    # was created and deleted again, so it nets out. Settlement rows are
    # excluded: the settle endpoint may legitimately store zero or one row per
    # caller (a concurrent settle can leave nothing to settle), so their count
    # is not what this check is about. A larger regular count means a deletion
    # was not reflected in the read model.
    expected_expenses = args.expenses * len(group.members) - group.deleted

    def expense_counts():
        status, body, _ = client.request(
            "groups", f"/upsert/groups/{group.group_id}/expenses?page=0&size=200",
            "GET", headers=leader.headers())
        rows = body.get("content", []) if isinstance(body, dict) else []
        regular = [r for r in rows if r.get("splitType") != "SETTLEMENT"]
        settlements = [r for r in rows if r.get("splitType") == "SETTLEMENT"]
        return status, len(regular), len(settlements)

    (status, regular, settlements), count_ok = poll(
        expense_counts,
        lambda r: r[0] == 200 and r[1] == expected_expenses,
        timeout=max(10.0, args.verify_timeout))
    checks.status(200, status, "list group expenses")
    checks.ok(count_ok, "group expenses reflect the deletions",
              f"{regular} regular + {settlements} settlement rows, "
              f"{expected_expenses} regular expected "
              f"({args.expenses * len(group.members)} posted, {group.deleted} deleted)")

    status, members, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/members", "GET",
        headers=leader.headers())
    checks.status(200, status, "list group members")
    archived = [m for m in members if m.get("archived")] if isinstance(members, list) else []
    checks.ok(len(archived) == group.archived,
              "archived membership count matches",
              f"{len(archived)} archived, {group.archived} requested")

    status, balances, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/balances", "GET",
        headers=leader.headers())
    checks.status(200, status, "group balances")
    if status == 200:
        check_balances_settle(checks, balances, "group balances")

        # Every member must see the same cached group balances.
        readers = group.members[:3]
        signatures = []
        for reader in readers:
            s, b = _balances(client, group, reader)
            if s == 200:
                signatures.append(_balance_signature(b))
        checks.ok(len(signatures) == len(readers) and len(set(signatures)) == 1,
                  "every member sees the same balances",
                  f"{len(set(signatures))} distinct over {len(signatures)} readers")

    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/activity?page=0&size=20",
        "GET", headers=leader.headers())
    checks.status(200, status, "group activity")


# ────────────────────────────────────────────────────────────────────────────
# Orchestration
# ────────────────────────────────────────────────────────────────────────────
def _ramp(args, position, total):
    """Realistic mode only: spread arrivals across ``--ramp-seconds``.

    Without this every stage releases all users at the same instant, which
    synchronises the whole run and makes each stage hit one endpoint class in
    a single burst. Real traffic arrives continuously.
    """
    if not getattr(args, "realistic", False) or args.ramp_seconds <= 0 or total <= 1:
        return
    time.sleep(args.ramp_seconds * (position / (total - 1)))


def run_stage(members, metrics, name, fn, args=None):
    if not members:
        return

    total = len(members)
    position = {m.idx: i for i, m in enumerate(members)}

    def wrapped(member):
        try:
            if args is not None:
                _ramp(args, position[member.idx], total)
                _think(args, random.Random(args.seed + 10000 + member.idx))
            fn(member)
        except Exception as exc:
            metrics.check(name, f"user{member.idx}", "stage completed", False, repr(exc))

    with ThreadPoolExecutor(max_workers=len(members)) as pool:
        list(pool.map(wrapped, members))
    print(f"{DIM}  · {name} complete{RESET}")


def _build_members(args):
    rng = random.Random(args.seed)
    combos = [f"{f} {l}" for f in FIRST_NAMES for l in LAST_NAMES]
    rng.shuffle(combos)
    names = []
    while len(names) < args.users:
        names.extend(combos)
        rng.shuffle(combos)
    return [Member(i, names[i], args.password, i // max(1, args.users // max(1, args.groups)))
            for i in range(args.users)]


def run(args):
    metrics = Metrics()
    client = Client(args.base_url, metrics, timeout=args.timeout, pace=args.pace)

    if args.groups and args.groups > 0:
        group_count = min(args.groups, max(1, args.users // 2))
        members_per_group = max(2, args.users // group_count)
    else:
        members_per_group = max(2, args.members_per_group)
        group_count = max(1, args.users // members_per_group)
    members = _build_members(args)
    for m in members:
        m.group_index = min(group_count - 1, m.idx // members_per_group)
    groups = {g: GroupState(g, members[g * members_per_group:(g + 1) * members_per_group])
              for g in range(group_count)}

    print(f"{BOLD}{CYAN}{args.users}-user golden scenario{RESET}")
    print(f"  gateway : {args.base_url}")
    print(f"  users   : {args.users}  txns/user: {args.txns}  deletes/user: {args.deletes}")
    print(f"  groups  : {group_count} x {members_per_group} members, "
          f"{args.expenses} expenses/member, {args.bills} receipt(s)/user")
    print(f"  receipts: {args.samples}")
    if args.think_time > 0:
        print(f"  pacing  : think-time 0..{2 * args.think_time:.1f}s before each step")
    if args.realistic:
        print(f"  mode    : realistic — arrivals over {args.ramp_seconds:.1f}s per stage, "
              f"refresh race on {args.refresh_race_fraction:.0%} of users")
    started = time.time()

    print(f"\n{BOLD}Stage 1 — registering and logging in{RESET}")
    with ThreadPoolExecutor(max_workers=args.auth_concurrency) as pool:
        futures = [pool.submit(stage_register, client, m, Checks(metrics, "auth", f"user{m.idx}"))
                   for m in members]
        for fut in futures:
            fut.result()
    ready = [m for m in members if m.user_id and m.token]
    print(f"  {len(ready)}/{len(members)} users ready")
    if not ready:
        print(f"{RED}no users could be created{RESET}")
        return 1
    for group in groups.values():
        group.members = [m for m in group.members if m in ready]
    groups = {g: s for g, s in groups.items() if len(s.members) >= 2}
    active = [m for g in groups.values() for m in g.members]

    def group_of(member):
        group = groups.get(member.group_index)
        return group if group is not None and member in group.members else None

    print(f"\n{BOLD}Stage 2 — transactions, recurring subscriptions, goal, budget{RESET}")
    run_stage(ready, metrics, "personal",
              lambda m: stage_personal(client, m, Checks(metrics, "personal", f"user{m.idx}"),
                                       args, random.Random(args.seed + m.idx)),
              args=args)

    print(f"\n{BOLD}Stage 3 — security and concurrency races{RESET}")
    if args.realistic:
        race_count = max(1, round(len(ready) * args.refresh_race_fraction))
        race_users = {m.idx for m in ready[:race_count]}
        print(f"  refresh race: {race_count}/{len(ready)} users "
              f"(fraction {args.refresh_race_fraction:.0%})")
    else:
        race_users = None  # every user races
    run_stage(ready, metrics, "security",
              lambda m: (
                  stage_refresh_race(client, m, Checks(metrics, "security", f"user{m.idx}"),
                                     race=race_users is None or m.idx in race_users),
                  stage_idempotency(client, m, Checks(metrics, "security", f"user{m.idx}")),
                  stage_authz(client, m, Checks(metrics, "security", f"user{m.idx}"), group_of(m)),
              ),
              args=args)

    print(f"\n{BOLD}Stage 4 — subscriptions, bulk, search, analytics{RESET}")
    run_stage(ready, metrics, "complex",
              lambda m: stage_complex(client, m, Checks(metrics, "complex", f"user{m.idx}"),
                                      args, random.Random(args.seed + 500 + m.idx)),
              args=args)

    print(f"\n{BOLD}Stage 5 — cache eviction and read consistency{RESET}")
    run_stage(ready, metrics, "cache",
              lambda m: stage_cache(client, m, Checks(metrics, "cache", f"user{m.idx}"),
                                    args, random.Random(args.seed + 700 + m.idx)),
              args=args)

    print(f"\n{BOLD}Stage 6 — {args.bills} receipt uploads per user (OCR){RESET}")
    run_stage(ready, metrics, "ocr",
              lambda m: stage_ocr(client, m, Checks(metrics, "ocr", f"user{m.idx}"),
                                  args, random.Random(args.seed + 900 + m.idx)),
              args=args)

    print(f"\n{BOLD}Stage 7 — creating {len(groups)} groups{RESET}")
    with ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
        list(pool.map(lambda p: (_ramp(args, p[0], len(groups)),
                                 _think(args, random.Random(args.seed + p[1].index)),
                                 stage_group_create(
                                     client, p[1], Checks(metrics, "groups", f"group{p[1].index}"),
                                     args)), enumerate(groups.values())))

    print(f"\n{BOLD}Stage 8 — accepting invitations{RESET}")
    with ThreadPoolExecutor(max_workers=max(1, len(active))) as pool:
        list(pool.map(lambda p: (_ramp(args, p[0], len(active)),
                                 _think(args, random.Random(args.seed + 20000 + p[1].idx)),
                                 stage_group_accept(
                                     client, group_of(p[1]), p[1],
                                     Checks(metrics, "groups", f"user{p[1].idx}"))), enumerate(active)))

    print(f"\n{BOLD}Stage 9 — shared expenses (EQUAL / PERCENTAGE / EXACT){RESET}")
    run_stage(active, metrics, "split",
              lambda m: stage_group_expenses(client, group_of(m), m,
                                             Checks(metrics, "split", f"user{m.idx}"),
                                             args, random.Random(args.seed + 1000 + m.idx)),
              args=args)

    print(f"\n{BOLD}Stage 10 — group balances cache eviction{RESET}")
    with ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
        list(pool.map(lambda p: (_ramp(args, p[0], len(groups)), stage_group_cache(
            client, p[1], Checks(metrics, "cache", f"group{p[1].index}"), args)),
            enumerate(groups.values())))

    print(f"\n{BOLD}Stage 11 — half settle, half archive{RESET}")
    run_stage(active, metrics, "settle",
              lambda m: stage_settle_or_archive(
                  client, group_of(m), m,
                  Checks(metrics, "settle", f"user{m.idx}"),
                  args, random.Random(args.seed + 2000 + m.idx)),
              args=args)

    print(f"\n{BOLD}Stage 12 — deleting 5 expenses per group{RESET}")
    with ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
        list(pool.map(lambda p: (_ramp(args, p[0], len(groups)),
                                 _think(args, random.Random(args.seed + 30000 + p[1].index)),
                                 stage_group_deletes(
                                     client, p[1], Checks(metrics, "split", f"group{p[1].index}"),
                                     args, random.Random(args.seed + 3000 + p[1].index))),
                      enumerate(groups.values())))

    print(f"\n{BOLD}Stage 13 — verification{RESET}")
    run_stage(ready, metrics, "verify",
              lambda m: verify_member(client, m, Checks(metrics, "verify", f"user{m.idx}"), args),
              args=args)
    with ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
        list(pool.map(lambda g: verify_group(
            client, g, Checks(metrics, "verify", f"group{g.index}"), args), groups.values()))

    elapsed = time.time() - started
    write_credentials(args.csv, members, groups)
    return report(args, metrics, ready, groups, elapsed)


def write_credentials(path, members, groups):
    group_of = {m.idx: g.index for g in groups.values() for m in g.members}
    with open(path, "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["email", "username", "password", "userId", "group", "name"])
        for m in members:
            writer.writerow([m.email, m.username, m.password, m.user_id or "",
                             group_of.get(m.idx, ""), m.full_name])
    print(f"{DIM}credentials written to {path}{RESET}")


def report(args, metrics, members, groups, elapsed):
    print(f"\n{BOLD}{CYAN}=== Results ==={RESET}")
    header = (f"{'phase':<12}{'reqs':>7}{'2xx':>7}{'4xx':>7}{'5xx':>6}{'retry':>7}"
              f"{'429':>6}{'503':>6}{'p50':>7}{'p95':>7}{'p99':>7}")
    print(header)
    print("-" * len(header))
    totals = {"n": 0, "2xx": 0, "4xx": 0, "5xx": 0}
    for phase in sorted(metrics.status):
        counts = metrics.status[phase]
        ok = sum(n for c, n in counts.items() if 200 <= c < 300)
        c4 = sum(n for c, n in counts.items() if 400 <= c < 500)
        c5 = sum(n for c, n in counts.items() if c >= 500)
        n = sum(counts.values())
        totals["n"] += n
        totals["2xx"] += ok
        totals["4xx"] += c4
        totals["5xx"] += c5
        print(f"{phase:<12}{n:>7}{ok:>7}{c4:>7}{c5:>6}{metrics.retries[phase]:>7}"
              f"{metrics.throttled[phase]:>6}{metrics.breaker[phase]:>6}"
              f"{metrics.percentile(phase, 50):>6.0f}m{metrics.percentile(phase, 95):>6.0f}m"
              f"{metrics.percentile(phase, 99):>6.0f}m")
    print("-" * len(header))
    print(f"{'TOTAL':<12}{totals['n']:>7}{totals['2xx']:>7}{totals['4xx']:>7}{totals['5xx']:>6}"
          f"{sum(metrics.retries.values()):>7}{sum(metrics.throttled.values()):>6}"
          f"{sum(metrics.breaker.values()):>6}")

    checks_total = len(metrics.checks)
    checks_ok = sum(1 for c in metrics.checks if c["ok"])
    failed = checks_total - checks_ok
    print(f"\n  users: {len(members)}   groups: {len(groups)}   checks: {checks_ok}/{checks_total}")
    print(f"  OCR uploads: {sum(m.uploads for m in members)}")
    for g in groups.values():
        print(f"  group {g.index}: {len(g.members)} members, "
              f"{len(g.expense_ids)} expenses left, {g.deleted} deleted, "
              f"{g.settled} settled, {g.archived} archived")
    for name, values in metrics.cache_latency.items():
        if values:
            values = sorted(values)
            print(f"  cache latency [{name}]: median {values[len(values) // 2]:.1f}s "
                  f"max {values[-1]:.1f}s over {len(values)}")
    print(f"  duration: {elapsed:.1f}s   "
          f"throughput: {totals['n'] / max(elapsed, 0.001):.1f} req/s")

    if metrics.failures:
        print(f"\n{RED}{BOLD}Failures ({len(metrics.failures)}):{RESET}")
        for owner, name, detail in metrics.failures[:40]:
            print(f"  {RED}×{RESET} [{owner}] {name}" + (f" — {detail}" if detail else ""))
        if len(metrics.failures) > 40:
            print(f"  … and {len(metrics.failures) - 40} more")

    verdict_ok = failed == 0 and totals["5xx"] == 0
    print(f"\n{GREEN}{BOLD}PASS{RESET}" if verdict_ok else f"\n{RED}{BOLD}FAIL{RESET}")

    if args.out:
        payload = {
            "gateway": args.base_url,
            "config": {k: v for k, v in vars(args).items() if k != "password"},
            "duration_s": round(elapsed, 1),
            "totals": totals,
            "retries": dict(metrics.retries),
            "throttled_429": dict(metrics.throttled),
            "breaker_503": dict(metrics.breaker),
            "cache_latency": {k: v for k, v in metrics.cache_latency.items()},
            "groups": {str(g.index): {
                "members": len(g.members), "expenses_left": len(g.expense_ids),
                "deleted": g.deleted, "settled": g.settled, "archived": g.archived,
            } for g in groups.values()},
            "checks": metrics.checks,
            "verdict": "PASS" if verdict_ok else "FAIL",
        }
        with open(args.out, "w") as fh:
            json.dump(payload, fh, indent=2)
        print(f"{DIM}report written to {args.out}{RESET}")

    return 0 if verdict_ok else 1


def parse_args(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    default_samples = os.path.join(here, "large-receipt-image-dataset-SRD")
    if not os.path.isdir(default_samples):
        default_samples = os.path.join(here, "sample-documents")
    p = argparse.ArgumentParser(description="Golden end-to-end + load test")
    p.add_argument("--users", type=int, default=50)
    p.add_argument("--txns", type=int, default=20, help="personal transactions per user")
    p.add_argument("--deletes", type=int, default=5, help="transactions each user deletes")
    p.add_argument("--months", type=int, default=6, help="months of transaction history")
    p.add_argument("--groups", type=int, default=10)
    p.add_argument("--members-per-group", type=int, default=10)
    p.add_argument("--expenses", type=int, default=3, help="shared expenses per member")
    p.add_argument("--bills", type=int, default=10, help="OCR uploads per user")
    p.add_argument("--base-url", default=os.environ.get("GATEWAY_URL", "http://localhost:8080"))
    p.add_argument("--samples", default=default_samples)
    p.add_argument("--password", default="GoldenTest!")
    p.add_argument("--seed", type=int, default=11)
    p.add_argument("--pace", type=float, default=0.06,
                   help="seconds between writes per user (gateway allows 20/s per user)")
    p.add_argument("--think-time", type=float, default=0.0,
                   help="mean seconds to pause before each step, jittered 0..2x")
    p.add_argument("--timeout", type=float, default=90.0)
    p.add_argument("--verify-timeout", type=float, default=30.0)
    p.add_argument("--cache-timeout", type=float, default=45.0,
                   help="budget for a cached read to converge after a write; "
                        "group-balances eviction goes through the outbox and can "
                        "take several seconds under load (TTL is 10 minutes, so "
                        "this still proves eviction, not expiry)")
    p.add_argument("--auth-concurrency", type=int, default=5)
    p.add_argument("--realistic", action="store_true",
                   help="spread stage arrivals over time instead of releasing all users "
                        "at once, and exercise the refresh race for a small subset")
    p.add_argument("--ramp-seconds", type=float, default=20.0,
                   help="realistic mode: window over which each stage's users start")
    p.add_argument("--refresh-race-fraction", type=float, default=0.05,
                   help="realistic mode: fraction of users that run the 4-way refresh race")
    p.add_argument("--csv", default=os.path.join(here, "golden-users.csv"))
    p.add_argument("--out", default=os.path.join(here, "golden-report.json"))
    return p.parse_args(argv)


if __name__ == "__main__":
    sys.exit(run(parse_args(sys.argv[1:])))
