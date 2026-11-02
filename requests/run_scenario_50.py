#!/usr/bin/env python3
"""50-user group scenario for the Personal Finance Assistant.

One run exercises the whole product the way a user cohort would:

  stage 1  every user creates --txns transactions spread over --months months,
           posts 2 recurring transactions and creates a savings goal, then
           deletes --deletes of their transactions in parallel
  stage 2  every user uploads --bills receipts to the OCR pipeline
  stage 3  the cohort splits into groups; each group's leader creates the group
           and invites the other members
  stage 4  every invited member accepts their own invitation
  stage 5  every member posts --expenses 50/50 (EQUAL) shared expenses
  stage 6  half of each group settles a debt, the other half archives their
           membership
  stage 7  five members of each group delete five of the group's expenses
  stage 8  verification: read-model consistency, balances, archived flags

The HTTP plumbing, retry/back-off and CQRS polling are reused from
run_load_test.py.

    python3 requests/run_scenario_50.py --users 50
    GATEWAY_URL=https://verginjose.cloud python3 requests/run_scenario_50.py
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import random
import sys
import threading
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_load_test import (  # noqa: E402
    Client,
    Checks,
    Metrics,
    iso,
    money,
    poll,
)

EXPENSE_CATEGORIES = [
    "GROCERIES", "RESTAURANTS", "FOOD_DELIVERY", "FUEL", "PUBLIC_TRANSPORT",
    "CAB_AND_AUTO", "ELECTRICITY", "INTERNET", "DOCTOR_AND_CLINIC", "MEDICINES",
    "CLOTHING", "MOVIES_AND_EVENTS", "PETS", "COFFEE_AND_SNACKS",
]
GROUP_EXPENSE_LABELS = [
    "Dinner", "Cab ride", "Groceries", "Hotel", "Tickets", "Fuel",
    "Lunch", "Snacks", "Parking", "Supplies",
]
RECURRING_VENDORS = ["Netflix", "Spotify", "Amazon Prime", "Hotstar"]


# ────────────────────────────────────────────────────────────────────────────
# Cohort model
# ────────────────────────────────────────────────────────────────────────────
class Member:
    def __init__(self, idx, password, group_index):
        self.idx = idx
        self.group_index = group_index
        self.email = f"s50_{idx:02d}_{uuid.uuid4().hex[:6]}@example.com"
        self.username = f"s50_{idx:02d}_{uuid.uuid4().hex[:6]}"
        self.password = password
        self.user_id = None
        self.token = None
        self.refresh = None
        self.created = []          # [(id, type, amount)] still present
        self.expected_income = 0.0
        self.expected_expense = 0.0
        self.goal_id = None
        self.recurring_names = []

    def headers(self):
        return {"Authorization": f"Bearer {self.token}", "X-User-Id": str(self.user_id)}


class GroupState:
    def __init__(self, index, members):
        self.index = index
        self.members = members
        self.group_id = None
        self.expense_ids = []      # ids of non-settlement shared expenses
        self.deleted = 0
        self.settled = 0
        self.archived = 0
        self.lock = threading.Lock()


# ────────────────────────────────────────────────────────────────────────────
# Stage 0 — identity
# ────────────────────────────────────────────────────────────────────────────
def stage_register(client, member, checks):
    payload = {"email": member.email, "username": member.username,
               "password": member.password, "role": "USER"}
    status, body, _ = client.request("auth", "/auth/register", "POST", body=payload)
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
# Stage 1 — personal transactions, recurring, goal, deletes
# ────────────────────────────────────────────────────────────────────────────
def stage_personal(client, member, checks, args, rng):
    now = datetime.now()

    for i in range(args.txns):
        months_ago = i % max(1, args.months)
        base = now - timedelta(days=30 * months_ago)
        stamped = base.replace(day=rng.randint(1, 27), hour=rng.randint(7, 22),
                               minute=rng.randint(0, 59), second=0, microsecond=0)
        is_income = rng.random() < 0.15
        amount = money(rng.uniform(15000, 80000) if is_income else rng.uniform(40, 3000))
        body = {
            "userId": str(member.user_id),
            "name": f"{rng.choice(EXPENSE_CATEGORIES).lower()} {months_ago}-{i}",
            "amount": amount,
            "type": "INCOME" if is_income else "EXPENSE",
            "category": "SALARY" if is_income else rng.choice(EXPENSE_CATEGORIES),
            "currency": "INR",
            "description": f"scenario seed {i}",
            "createdAt": iso(stamped),
        }
        # A real client sends a key per click so its own retry cannot duplicate
        # the row; the shared retry layer benefits from the same protection.
        headers = member.headers()
        headers["X-Idempotency-Key"] = str(uuid.uuid4())
        status, resp, _ = client.request("personal", "/upsert/create", "POST",
                                         headers=headers, body=body)
        if status == 201:
            member.created.append((resp.get("id"), body["type"], amount))
            if is_income:
                member.expected_income += amount
            else:
                member.expected_expense += amount
        else:
            checks.ok(False, "create transaction", f"status {status}")
        # A user browses between entries instead of machine-gunning the API.
        if i % 5 == 4:
            _think(args, rng)

    checks.ok(len(member.created) == args.txns, "all transactions created",
              f"{len(member.created)}/{args.txns}")

    # Recurring transactions (the subscription detector's raw material).
    for vendor in rng.sample(RECURRING_VENDORS, 2):
        headers = member.headers()
        headers["X-Idempotency-Key"] = str(uuid.uuid4())
        status, resp, _ = client.request(
            "personal", "/upsert/create", "POST", headers=headers,
            body={"userId": str(member.user_id), "name": vendor, "amount": 499.0,
                  "type": "EXPENSE", "category": "OTT_SUBSCRIPTIONS", "currency": "INR",
                  "description": "recurring seed", "recurring": True,
                  "recurringPeriod": "MONTHLY", "createdAt": iso(now)})
        checks.status(201, status, f"create recurring {vendor}")
        if status == 201:
            member.created.append((resp.get("id"), "EXPENSE", 499.0))
            member.expected_expense += 499.0
            member.recurring_names.append(vendor)

    # Savings goal.
    status, resp, _ = client.request(
        "personal", "/upsert/goals", "POST", headers=member.headers(),
        body={"userId": str(member.user_id), "name": f"Goal {member.idx}",
              "targetAmount": 50000.0, "currency": "INR",
              "description": "scenario goal", "deadline": "2027-12-31"})
    checks.status(201, status, "create goal")
    member.goal_id = resp.get("id")
    if member.goal_id:
        status, resp, _ = client.request(
            "personal", f"/upsert/goals/{member.goal_id}/contribute?userId={member.user_id}",
            "PATCH", headers=member.headers(),
            body={"amount": 1000.0, "description": "scenario contribution"})
        checks.status(200, status, "contribute to goal")

    # Delete a random subset in parallel across the cohort.
    victims = rng.sample(member.created, k=min(args.deletes, len(member.created)))
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
# Stage 1b — security and concurrency races
# ────────────────────────────────────────────────────────────────────────────
def stage_refresh_race(client, member, checks):
    """Four clients replay the same refresh token at the same instant. Token
    rotation must let exactly one through; every loser gets a 4xx, never a
    second live session."""
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

    # The winner consumed the token; restore a session for the rest of the run.
    status, body, _ = client.request(
        "auth", "/auth/login", "POST",
        body={"email": member.email, "password": member.password})
    checks.status(200, status, "re-login after refresh race")
    if status == 200 and body.get("token"):
        member.token = body["token"]
        member.refresh = body.get("refreshToken") or member.refresh


def stage_idempotency(client, member, checks):
    """Two eager clicks on save with the same key must yield one row. This is
    also the check that the retry layer cannot duplicate a write."""
    key = str(uuid.uuid4())
    payload = {"userId": str(member.user_id), "name": "idempotent double submit",
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
    """Read-only cross-user probes: another member's token must never open this
    member's data. Describes stay untouched so the run stays repeatable."""
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
    checks.ok(status in (403, 404), "foreign entry id not readable",
              f"got {status}")

    status, _, _ = client.request(
        "personal", f"/upsert/summary?userId={victim.user_id}", "GET",
        headers=member.headers())
    checks.ok(status in (403, 404), "foreign summary not readable",
              f"got {status}")


# ────────────────────────────────────────────────────────────────────────────
# Stage 1c — richer personal flows: budgets, subscriptions, bulk, search,
#            filtered listings and the analytics read model
# ────────────────────────────────────────────────────────────────────────────
def stage_complex(client, member, checks, args, rng):
    """Exercise the features a real user touches besides plain CRUD.

    Every row this stage creates is appended to ``member.created`` (and removed
    again when deleted) so the verification stage's listing/summary equality
    still holds.
    """
    now = datetime.now()
    uid = member.user_id
    phase = "personal"

    # ── Budget: create, appear in listing, drive budget analytics ──────────
    status, _, _ = client.request(
        phase, "/upsert/budgets", "POST", headers=member.headers(),
        body={"userId": str(uid), "expenseCategory": "RESTAURANTS",
              "budgetAmount": 50000.0, "period": "MONTHLY", "currency": "INR"})
    checks.status(201, status, "create budget")
    _think(args, rng)

    (status, body), seen = poll(
        lambda: client.request(phase, f"/upsert/budgets?userId={uid}", "GET",
                               headers=member.headers())[:2],
        lambda r: isinstance(r[1], list) and len(r[1]) > 0,
        timeout=max(10.0, args.verify_timeout))
    checks.status(200, status, "list budgets")
    checks.ok(seen, "budget appears in listing")
    _think(args, rng)

    status, status_body, _ = client.request(
        "analytics", f"/analytics/budgets/trends?userId={uid}", "GET",
        headers=member.headers())
    checks.status(200, status, "budget trends")
    _think(args, rng)

    # ── Subscriptions: the recurring vendors seeded in stage 1 must surface ─
    status, subs, _ = client.request(
        phase, f"/upsert/subscriptions?userId={uid}", "GET",
        headers=member.headers())
    checks.status(200, status, "list subscriptions")
    names = {s.get("name") for s in subs} if isinstance(subs, list) else set()
    checks.ok(any(v in names for v in member.recurring_names),
              "recurring subscription detected", f"names={sorted(names)}")
    _think(args, rng)

    # ── Bulk create, then bulk-delete half of it ───────────────────────────
    bulk = [{"userId": str(uid), "name": f"complex bulk {k}",
             "amount": money(12.5 + k), "type": "EXPENSE", "category": "OTHERS",
             "currency": "INR", "description": "complex bulk",
             "createdAt": iso(now)} for k in range(4)]
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

    # ── Search probes the read model, so poll for eventual visibility ──────
    probe = f"complexprobe-{uuid.uuid4().hex[:8]}"
    status, resp, _ = client.request(
        phase, "/upsert/create", "POST", headers=member.headers(),
        body={"userId": str(uid), "name": probe, "amount": 3.0, "type": "EXPENSE",
              "category": "OTHERS", "currency": "INR", "createdAt": iso(now)})
    checks.status(201, status, "search probe created")
    if status == 201:
        member.created.append((resp.get("id"), "EXPENSE", 3.0))
        member.expected_expense += 3.0
    q = urllib.parse.quote(probe)
    (status, body), found = poll(
        lambda: client.request(phase, f"/upsert/search?userId={uid}&q={q}", "GET",
                               headers=member.headers())[:2],
        lambda r: isinstance(r[1], dict) and any(
            row.get("name") == probe for row in r[1].get("content", [])),
        timeout=max(10.0, args.verify_timeout))
    checks.status(200, status, "search")
    checks.ok(found, "search returns the matching row")
    _think(args, rng)

    # ── Filtered listing: every returned row must fall inside the window ───
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
    checks.ok(not outside, "date filter excludes out-of-range rows",
              f"{len(outside)} outside")
    _think(args, rng)

    # ── Analytics read model (AI insights is opt-in: it calls Groq) ────────
    analytics = [
        ("category pie chart", f"/analytics/category-pie-chart?userId={uid}"),
        ("timeline chart", f"/analytics/timeline-chart?userId={uid}"),
        ("comprehensive analytics", f"/analytics/comprehensive?userId={uid}"),
        ("analytics entries page",
         f"/analytics/transaction-entries?userId={uid}&page=0&size=20"),
        ("health score", f"/analytics/health-score?userId={uid}"),
        ("income by category",
         f"/analytics/transactions/income-by-category?userId={uid}"
         f"&categoryName=SALARY&startDate={start_d}&endDate={end_d}"),
        ("transactions by type",
         f"/analytics/transactions/by-type?userId={uid}"
         f"&transactionType=EXPENSE&startDate={start_d}&endDate={end_d}"),
    ]
    if member.goal_id:
        analytics.append(
            ("goal forecast",
             f"/analytics/goals/{member.goal_id}/forecast?userId={uid}"))
    for label, path in analytics:
        status, _, _ = client.request("analytics", path, "GET",
                                      headers=member.headers())
        checks.status(200, status, label)
        _think(args, rng)


# ────────────────────────────────────────────────────────────────────────────
# Stage 2 — OCR
# ────────────────────────────────────────────────────────────────────────────
def stage_ocr(client, member, checks, args):
    if args.bills <= 0:
        return
    files = [os.path.join(args.samples, f) for f in sorted(os.listdir(args.samples))
             if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".pdf"))]
    if not files:
        checks.ok(False, "sample documents present", args.samples)
        return
    for n in range(args.bills):
        path = files[(member.idx + n) % len(files)]
        status, body, _ = client.request(
            "ocr", f"/bill/process/{member.user_id}", "POST",
            headers={"Authorization": f"Bearer {member.token}"},
            files={"file": path}, timeout=120)
        checks.status(202, status, f"receipt upload {os.path.basename(path)}")
        checks.ok(body.get("receiptId") is not None, "receipt id returned")


# ────────────────────────────────────────────────────────────────────────────
# Stages 3-7 — groups
# ────────────────────────────────────────────────────────────────────────────
def stage_group_create(client, group, checks, args):
    leader = group.members[0]
    status, body, _ = client.request(
        "groups", "/upsert/groups", "POST", headers=leader.headers(),
        body={"name": f"Scenario Group {group.index}",
              "description": "50-user scenario",
              "createdBy": str(leader.user_id)})
    checks.status(201, status, "create group")
    group.group_id = body.get("id")
    if not group.group_id:
        return

    for peer in group.members[1:]:
        status, _, _ = client.request(
            "groups", f"/upsert/groups/{group.group_id}/members", "POST",
            headers=leader.headers(),
            body={"userId": str(peer.user_id), "name": peer.username})
        checks.status(201, status, f"invite member {peer.idx}")


def stage_group_accept(client, group, member, checks):
    if group is None or not group.group_id or member is group.members[0]:
        return
    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/members/{member.user_id}/accept",
        "POST", headers=member.headers())
    checks.status(200, status, "accept invitation")


def stage_group_expenses(client, group, member, checks, args, rng):
    if group is None or not group.group_id:
        return
    for n in range(args.expenses):
        label = rng.choice(GROUP_EXPENSE_LABELS)
        status, body, _ = client.request(
            "groups", f"/upsert/groups/{group.group_id}/expenses", "POST",
            headers=member.headers(),
            body={"amount": money(rng.uniform(200, 2000)),
                  "description": f"{label} by {member.idx} #{n}",
                  "paidBy": str(member.user_id),
                  "splitType": "EQUAL",
                  "currency": "INR"})
        checks.status(201, status, f"post shared expense {n}")
        if status == 201 and body.get("id") is not None:
            with group.lock:
                group.expense_ids.append(body["id"])

        # Read the analytics endpoints while the cohort is still writing, so
        # stale-read handling is exercised under contention rather than after.
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


def _balances(client, group, member_id):
    status, body, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/balances", "GET",
        headers=member_id.headers())
    return status, body


def stage_settle_or_archive(client, group, member, checks, args, rng):
    if group is None or not group.group_id:
        return
    position = group.members.index(member)

    if position < len(group.members) // 2:
        # Settle: prefer a debt this member owes, otherwise settle any debt in
        # the group (the endpoint only requires membership).
        (status, balances), _ = poll(
            lambda: _balances(client, group, member),
            lambda r: r[0] == 200 and isinstance(r[1], dict)
            and bool(r[1].get("simplifiedDebts")),
            timeout=20)
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
    """Five different members delete five of the group's expenses, in parallel
    with the same step in every other group."""
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
# Stage 8 — verification
# ────────────────────────────────────────────────────────────────────────────
def verify_member(client, member, checks, args):
    uid = member.user_id
    deadline = time.time() + args.verify_timeout

    summary = {}
    while time.time() < deadline:
        status, summary, _ = client.request(
            "personal", f"/upsert/summary?userId={uid}", "GET", headers=member.headers())
        if status == 200 and int(summary.get("totalCount", 0) or 0) >= len(member.created):
            break
        time.sleep(0.5)
    checks.status(200, status, "summary reachable")
    checks.ok(int(summary.get("totalCount", 0) or 0) >= len(member.created),
              "summary sees the seeded transactions",
              f"read {summary.get('totalCount')} >= written {len(member.created)}")

    # Summary must equal the sum over the listing once both have settled.
    #
    # Both endpoints are @Cacheable in query-service, so a single pair of reads
    # can compare two different snapshots: the first summary read may land
    # before a delete has replicated and then sit in Redis for its TTL. Read
    # the listing with an unusual page size to miss its cached key (a fresh
    # replica read), then re-poll the summary until it agrees with the listing
    # or verify_timeout expires -- a summary that never converges is a real
    # stale-cache defect, not a harness race.
    def read_listing():
        """Sum the transaction listing with a page size that misses its cache key."""
        listed, income, expense, page = 0, 0.0, 0.0, 0
        while page < 50:
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

    listed, income, expense = read_listing()
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
        # Both endpoints are read-model projections and OCR rows can land while
        # they are being compared. Re-read the listing as well as the summary,
        # otherwise an OCR row appended after the one-shot listing read makes
        # the summary legitimately larger forever and the check can never pass.
        listed, income, expense = read_listing()
        status, summary, _ = client.request(
            "personal", f"/upsert/summary?userId={uid}", "GET",
            headers=member.headers())

    checks.ok(abs(float(summary.get("totalIncome", 0) or 0) - income) < eps,
              "summary income equals listing sum",
              f"{summary.get('totalIncome')} vs {income:.2f}")
    checks.ok(abs(float(summary.get("totalExpense", 0) or 0) - expense) < eps,
              "summary expense equals listing sum",
              f"{summary.get('totalExpense')} vs {expense:.2f}")
    checks.ok(float(summary.get("totalIncome", 0) or 0) >= member.expected_income - eps,
              "income at least what was written")
    checks.ok(float(summary.get("totalExpense", 0) or 0) >= member.expected_expense - eps,
              "expense at least what was written")

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


def verify_group(client, group, checks, args):
    if not group.group_id:
        checks.ok(False, "group exists", f"group {group.index}")
        return
    leader = group.members[0]

    status, body, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/expenses?page=0&size=100",
        "GET", headers=leader.headers())
    checks.status(200, status, "list group expenses")
    total = int(body.get("totalElements", 0) or 0) if isinstance(body, dict) else 0
    checks.ok(total >= args.expenses * len(group.members) - group.deleted,
              "group expenses reflect the deletions",
              f"{total} listed, {group.deleted} deleted")

    status, members, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/members", "GET",
        headers=leader.headers())
    checks.status(200, status, "list group members")
    archived = [m for m in members if m.get("archived")] if isinstance(members, list) else []
    checks.ok(len(archived) == group.archived,
              "archived membership count matches",
              f"{len(archived)} archived, {group.archived} requested")

    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/balances", "GET",
        headers=leader.headers())
    checks.status(200, status, "group balances")

    status, _, _ = client.request(
        "groups", f"/upsert/groups/{group.group_id}/activity?page=0&size=20",
        "GET", headers=leader.headers())
    checks.status(200, status, "group activity")


# ────────────────────────────────────────────────────────────────────────────
# Orchestration
# ────────────────────────────────────────────────────────────────────────────
def _think(args, rng):
    """Human think time before a step, jittered so users desynchronise.

    A uniform 0..2x draw means no two simulated users fire a step at the same
    instant: the result is a steady stream of requests instead of one burst per
    stage. Zero (the default) preserves the old back-to-back behaviour.
    """
    if getattr(args, "think_time", 0) > 0:
        time.sleep(rng.uniform(0, 2 * args.think_time))


def run_stage(members, metrics, name, fn, args=None):
    if not members:
        return

    def wrapped(member):
        try:
            if args is not None:
                _think(args, random.Random(args.seed + 10000 + member.idx))
            fn(member)
        except Exception as exc:
            metrics.check(name, f"user{member.idx}", "stage completed", False, repr(exc))

    with ThreadPoolExecutor(max_workers=len(members)) as pool:
        list(pool.map(wrapped, members))
    print(f"\033[2m  · {name} complete\033[0m")


def run(args):
    metrics = Metrics()
    client = Client(args.base_url, metrics, timeout=args.timeout, pace=args.pace)

    if args.groups and args.groups > 0:
        group_count = min(args.groups, max(1, args.users // 2))
        members_per_group = max(2, args.users // group_count)
    else:
        members_per_group = max(2, args.members_per_group)
        group_count = max(1, args.users // members_per_group)
    members = [Member(i, args.password, i // members_per_group) for i in range(args.users)]
    groups = {g: GroupState(g, members[g * members_per_group:(g + 1) * members_per_group])
              for g in range(group_count)}

    print(f"\033[1m\033[96m{args.users}-user group scenario\033[0m")
    print(f"  gateway : {args.base_url}")
    print(f"  users   : {args.users}  txns/user: {args.txns}  deletes/user: {args.deletes}")
    print(f"  groups  : {group_count} x {members_per_group} members, "
          f"{args.expenses} expenses/member, {args.bills} receipt(s)/user")
    if args.think_time > 0:
        print(f"  pacing  : think-time 0..{2 * args.think_time:.1f}s before each step")
    if not args.no_complex:
        print("  complex : budgets, subscriptions, bulk, search, analytics per user")
    started = time.time()

    print("\n\033[1mStage 1 — registering and logging in\033[0m")
    with ThreadPoolExecutor(max_workers=args.auth_concurrency) as pool:
        futures = [pool.submit(stage_register, client, m, Checks(metrics, "auth", f"user{m.idx}"))
                   for m in members]
        for fut in futures:
            fut.result()
    ready = [m for m in members if m.user_id and m.token]
    print(f"  {len(ready)}/{len(members)} users ready")
    if not ready:
        print("\033[91mno users could be created\033[0m")
        return 1
    for group in groups.values():
        group.members = [m for m in group.members if m in ready]
    groups = {g: s for g, s in groups.items() if len(s.members) >= 2}
    active = [m for g in groups.values() for m in g.members]

    def group_of(member):
        group = groups.get(member.group_index)
        return group if group is not None and member in group.members else None

    print("\n\033[1mStage 2 — 20 transactions, recurring, goal, deletes\033[0m")
    run_stage(ready, metrics, "personal",
              lambda m: stage_personal(client, m, Checks(metrics, "personal", f"user{m.idx}"),
                                       args, random.Random(args.seed + m.idx)),
              args=args)

    print("\n\033[1mStage 2b — security and concurrency races\033[0m")
    run_stage(ready, metrics, "security",
              lambda m: (
                  stage_refresh_race(client, m, Checks(metrics, "security", f"user{m.idx}")),
                  stage_idempotency(client, m, Checks(metrics, "security", f"user{m.idx}")),
                  stage_authz(client, m, Checks(metrics, "security", f"user{m.idx}"),
                              group_of(m)),
              ),
              args=args)

    if not args.no_complex:
        print("\n\033[1mStage 2c — budgets, subscriptions, bulk, search, analytics\033[0m")
        run_stage(ready, metrics, "complex",
                  lambda m: stage_complex(client, m,
                                          Checks(metrics, "complex", f"user{m.idx}"),
                                          args, random.Random(args.seed + 500 + m.idx)),
                  args=args)

    print("\n\033[1mStage 3 — receipt uploads (OCR)\033[0m")
    run_stage(ready, metrics, "ocr",
              lambda m: stage_ocr(client, m, Checks(metrics, "ocr", f"user{m.idx}"), args),
              args=args)

    print(f"\n\033[1mStage 4 — creating {len(groups)} groups\033[0m")
    with ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
        list(pool.map(lambda g: (_think(args, random.Random(args.seed + g.index)),
                                 stage_group_create(
                                     client, g, Checks(metrics, "groups", f"group{g.index}"),
                                     args)), groups.values()))

    print("\n\033[1mStage 5 — accepting invitations\033[0m")
    with ThreadPoolExecutor(max_workers=max(1, len(active))) as pool:
        list(pool.map(lambda m: (_think(args, random.Random(args.seed + 20000 + m.idx)),
                                 stage_group_accept(
                                     client, group_of(m), m,
                                     Checks(metrics, "groups", f"user{m.idx}"))), active))

    print("\n\033[1mStage 6 — shared expenses (equal split)\033[0m")
    run_stage(active, metrics, "split",
              lambda m: stage_group_expenses(client, group_of(m), m,
                                             Checks(metrics, "split", f"user{m.idx}"),
                                             args, random.Random(args.seed + 1000 + m.idx)),
              args=args)

    print("\n\033[1mStage 7 — half settle, half archive\033[0m")
    run_stage(active, metrics, "settle",
              lambda m: stage_settle_or_archive(
                  client, group_of(m), m,
                  Checks(metrics, "settle", f"user{m.idx}"),
                  args, random.Random(args.seed + 2000 + m.idx)),
              args=args)

    print("\n\033[1mStage 8 — deleting 5 expenses per group\033[0m")
    with ThreadPoolExecutor(max_workers=max(1, len(groups))) as pool:
        list(pool.map(lambda g: (_think(args, random.Random(args.seed + 30000 + g.index)),
                                 stage_group_deletes(
                                     client, g, Checks(metrics, "split", f"group{g.index}"),
                                     args, random.Random(args.seed + 3000 + g.index))),
                      groups.values()))

    print("\n\033[1mStage 9 — verification\033[0m")
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
        writer.writerow(["email", "username", "password", "userId", "group"])
        for m in members:
            writer.writerow([m.email, m.username, m.password, m.user_id or "",
                             group_of.get(m.idx, "")])
    print(f"\n\033[2mcredentials written to {path}\033[0m")


def report(args, metrics, members, groups, elapsed):
    print("\n\033[1m\033[96m=== Results ===\033[0m")
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
    for g in groups.values():
        print(f"  group {g.index}: {len(g.members)} members, "
              f"{len(g.expense_ids)} expenses left, {g.deleted} deleted, "
              f"{g.settled} settled, {g.archived} archived")
    print(f"  duration: {elapsed:.1f}s   "
          f"throughput: {totals['n'] / max(elapsed, 0.001):.1f} req/s")

    if metrics.failures:
        print(f"\n\033[91m\033[1mFailures ({len(metrics.failures)}):\033[0m")
        for owner, name, detail in metrics.failures[:40]:
            print(f"  \033[91m×\033[0m [{owner}] {name}" + (f" — {detail}" if detail else ""))
        if len(metrics.failures) > 40:
            print(f"  … and {len(metrics.failures) - 40} more")

    verdict_ok = failed == 0 and totals["5xx"] == 0
    print(("\n\033[92m\033[1mPASS\033[0m" if verdict_ok else "\n\033[91m\033[1mFAIL\033[0m"))

    if args.out:
        payload = {
            "gateway": args.base_url,
            "config": {k: v for k, v in vars(args).items() if k != "password"},
            "duration_s": round(elapsed, 1),
            "totals": totals,
            "retries": dict(metrics.retries),
            "throttled_429": dict(metrics.throttled),
            "breaker_503": dict(metrics.breaker),
            "groups": {str(g.index): {
                "members": len(g.members), "expenses_left": len(g.expense_ids),
                "deleted": g.deleted, "settled": g.settled, "archived": g.archived,
            } for g in groups.values()},
            "checks": metrics.checks,
            "verdict": "PASS" if verdict_ok else "FAIL",
        }
        with open(args.out, "w") as fh:
            json.dump(payload, fh, indent=2)
        print(f"\033[2mreport written to {args.out}\033[0m")

    return 0 if verdict_ok else 1


def parse_args(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description="50-user group scenario")
    p.add_argument("--users", type=int, default=50)
    p.add_argument("--txns", type=int, default=20, help="personal transactions per user")
    p.add_argument("--deletes", type=int, default=5, help="transactions each user deletes")
    p.add_argument("--months", type=int, default=6, help="months of transaction history")
    p.add_argument("--groups", type=int, default=5)
    p.add_argument("--members-per-group", type=int, default=10)
    p.add_argument("--expenses", type=int, default=2, help="shared expenses per member")
    p.add_argument("--bills", type=int, default=1, help="OCR uploads per user")
    p.add_argument("--base-url", default=os.environ.get("GATEWAY_URL", "http://localhost:8080"))
    p.add_argument("--samples", default=os.path.join(here, "sample-documents"))
    p.add_argument("--password", default="Scenario50!")
    p.add_argument("--seed", type=int, default=11)
    p.add_argument("--pace", type=float, default=0.06,
                   help="seconds between writes per user (gateway allows 20/s per user)")
    p.add_argument("--think-time", type=float, default=0.0,
                   help="mean seconds to pause before each step, jittered 0..2x; "
                        "spreads requests so the run resembles real traffic")
    p.add_argument("--no-complex", action="store_true",
                   help="skip the budgets/subscriptions/bulk/search/analytics stage")
    p.add_argument("--timeout", type=float, default=90.0)
    p.add_argument("--verify-timeout", type=float, default=30.0)
    p.add_argument("--auth-concurrency", type=int, default=5)
    p.add_argument("--csv", default=os.path.join(here, "loadtest-users.csv"))
    p.add_argument("--out", default=os.path.join(here, "scenario50-report.json"))
    return p.parse_args(argv)


if __name__ == "__main__":
    sys.exit(run(parse_args(sys.argv[1:])))
