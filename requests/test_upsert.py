"""
PFA Upsert Service — standalone test
Usage:
    python test_upsert.py
    python test_upsert.py --url http://localhost:8080 --email u@t.com --password s
    python test_upsert.py --delay 400
    python test_upsert.py --no-cleanup     # keep created entries for manual inspection
    python test_upsert.py --fail-fast
"""
import argparse
import sys
import time
import uuid
from datetime import datetime

import requests

from test_utils import CFG, STATE, section, req, headers, print_summary, run_tag

# ── CLI ───────────────────────────────────────────────────────────────────────
p = argparse.ArgumentParser()
p.add_argument("--url",        default="http://localhost:8080")
p.add_argument("--email",      default="testverify@gmail.com")
p.add_argument("--password",   default="tempPass123")
p.add_argument("--delay",      type=int, default=350)
p.add_argument("--no-cleanup", action="store_true")
p.add_argument("--fail-fast",  action="store_true")
ARGS = p.parse_args()

CFG["base"]             = ARGS.url.rstrip("/")
CFG["email"]            = ARGS.email
CFG["password"]         = ARGS.password
CFG["fail_fast"]        = ARGS.fail_fast
CFG["request_delay_ms"] = ARGS.delay

TAG = run_tag()


# ── Login helper ──────────────────────────────────────────────────────────────
def _login():
    section("AUTH — LOGIN (prereq)")
    resp = req("Login", "POST", "/api/auth/login",
               json_body={"email": CFG["email"], "password": CFG["password"]},
               expected=200,
               capture={"token": "token", "refresh_token": "refreshToken", "user_id": "userId"})
    if not (resp and resp.status_code == 200):
        print("  FATAL: Login failed. Aborting.")
        print_summary()
        sys.exit(1)
    print(f"    userId = {STATE['user_id']}")


# ─────────────────────────────────────────────────────────────────────────────
def test_upsert():
    section("UPSERT SERVICE")
    uid = STATE["user_id"]

    # ── Create ────────────────────────────────────────────────────────────────
    resp = req(f"Create EXPENSE (Swiggy-{TAG})", "POST", "/api/upsert/create",
               json_body={
                   "userId":          uid,
                   "name":            f"Swiggy Food Order {TAG}",
                   "amount":          450.00,
                   "type":            "EXPENSE",
                   "currency":        "INR",
                   "expenseCategory": "FOOD_AND_DINING",
                   "description":     f"Dinner order run {TAG}",
               },
               expected=201,
               capture={"expense_id": "id"})
    if resp:
        print(f"    expense_id = {STATE['expense_id']}")

    resp = req(f"Create INCOME (Salary-{TAG})", "POST", "/api/upsert/create",
               json_body={
                   "userId":         uid,
                   "name":           f"Monthly Salary {TAG}",
                   "amount":         75000.00,
                   "type":           "INCOME",
                   "currency":       "INR",
                   "incomeCategory": "SALARY",
                   "description":    f"June 2026 salary credit run {TAG}",
               },
               expected=201,
               capture={"income_id": "id"})
    if resp:
        print(f"    income_id = {STATE['income_id']}")

    # ── Idempotency ───────────────────────────────────────────────────────────
    # Use a run-unique key so the 2nd call in the SAME run is the duplicate,
    # not a leftover from a prior run (which would make call-1 also return 500).
    idem_key = f"netflix-idem-{TAG}"
    idem_body = {
        "userId":          uid,
        "name":            f"Netflix Subscription {TAG}",
        "amount":          649.00,
        "type":            "EXPENSE",
        "currency":        "INR",
        "expenseCategory": "ENTERTAINMENT",
        "description":     f"Monthly Netflix plan run {TAG}",
    }
    resp1 = req("Create with idempotency key (1st call)", "POST", "/api/upsert/create",
                json_body=idem_body,
                extra_headers={"X-Idempotency-Key": idem_key},
                expected=201,
                capture={"netflix_id": "id"})

    # Small pause so the server can persist the key before the repeat hits
    time.sleep(0.1)

    resp2 = req("Create with idempotency key (2nd call — same key)", "POST", "/api/upsert/create",
                json_body=idem_body,
                extra_headers={"X-Idempotency-Key": idem_key},
                expected=201,
                no_delay=True)

    if resp1 and resp2 and resp1.status_code == 201 and resp2.status_code == 201:
        id1 = resp1.json().get("id")
        id2 = resp2.json().get("id")
        same = id1 == id2
        print(f"  {'PASS' if same else 'FAIL'}  Idempotency: id1={id1} == id2={id2}")
    elif resp1 and resp2:
        print(f"  NOTE  Idempotency calls returned {resp1.status_code}/{resp2.status_code} — "
              f"server may not support X-Idempotency-Key yet.")

    req(f"Create RECURRING (Swimming-{TAG})", "POST", "/api/upsert/create",
        json_body={
            "userId":          uid,
            "name":            f"Swimming Membership {TAG}",
            "amount":          2000.00,
            "type":            "EXPENSE",
            "currency":        "INR",
            "expenseCategory": "HEALTHCARE",
            "description":     f"Monthly swimming membership run {TAG}",
            "recurring":       True,
            "recurringPeriod": "MONTHLY",
        },
        expected=201,
        capture={"recurring_id": "id"})

    # ── Negative create ───────────────────────────────────────────────────────
    req("Create missing required field -> 400", "POST", "/api/upsert/create",
        json_body={"userId": uid, "type": "EXPENSE"},
        expected=400)

    req("Create userId mismatch -> 403", "POST", "/api/upsert/create",
        json_body={
            "userId":          "00000000-0000-0000-0000-000000000000",
            "name":            "Bad Request",
            "amount":          1.0,
            "type":            "EXPENSE",
            "currency":        "INR",
            "expenseCategory": "OTHERS",
        },
        expected=403)

    saved = STATE["token"]
    STATE["token"] = None
    req("Create unauthenticated -> 401", "POST", "/api/upsert/create",
        json_body={"userId": uid, "name": "X", "amount": 1.0,
                   "type": "EXPENSE", "currency": "INR", "expenseCategory": "OTHERS"},
        expected=401, no_delay=True)
    STATE["token"] = saved

    # ── Update ────────────────────────────────────────────────────────────────
    if STATE["expense_id"]:
        req("Update expense (PUT)", "PUT", "/api/upsert/update",
            json_body={
                "id":              STATE["expense_id"],
                "userId":          uid,
                "name":            f"Zomato Food Order {TAG}",
                "amount":          520.00,
                "type":            "EXPENSE",
                "currency":        "INR",
                "expenseCategory": "FOOD_AND_DINING",
                "description":     f"Updated to Zomato run {TAG}",
            },
            expected=200)

        req("Update wrong owner -> 403", "PUT", "/api/upsert/update",
            json_body={
                "id":              STATE["expense_id"],
                "userId":          "00000000-0000-0000-0000-000000000000",
                "name":            "Hack",
                "amount":          1.0,
                "type":            "EXPENSE",
                "currency":        "INR",
                "expenseCategory": "OTHERS",
            },
            expected=403)

    # ── Patch amount ──────────────────────────────────────────────────────────
    if STATE["expense_id"]:
        req("Patch amount", "PATCH",
            f"/api/upsert/entries/{STATE['expense_id']}/amount",
            params={"userId": uid},
            json_body={"amount": 999.99},
            expected=200)

        req("Patch amount negative -> 400", "PATCH",
            f"/api/upsert/entries/{STATE['expense_id']}/amount",
            params={"userId": uid},
            json_body={"amount": -50.0},
            expected=400)

    # ── List & search ─────────────────────────────────────────────────────────
    req("List all entries (page 0)", "GET", "/api/upsert/entries",
        params={"userId": uid, "page": 0, "size": 10}, expected=200)

    req("List EXPENSE only", "GET", "/api/upsert/entries",
        params={"userId": uid, "type": "EXPENSE", "page": 0, "size": 10}, expected=200)

    req("List INCOME only", "GET", "/api/upsert/entries",
        params={"userId": uid, "type": "INCOME", "page": 0, "size": 10}, expected=200)

    req("List with date range", "GET", "/api/upsert/entries",
        params={"userId": uid, "startDate": "2026-01-01",
                "endDate": "2026-12-31", "page": 0, "size": 10}, expected=200)

    req("List page 2 (may be empty)", "GET", "/api/upsert/entries",
        params={"userId": uid, "page": 1, "size": 10}, expected=200)

    req("List invalid userId -> 400/404", "GET", "/api/upsert/entries",
        params={"userId": "not-a-uuid", "page": 0, "size": 10}, expected=[400, 404])

    if STATE["income_id"]:
        req("Get single entry by id", "GET",
            f"/api/upsert/entries/{STATE['income_id']}",
            params={"userId": uid}, expected=200)

    req("Get non-existent entry -> 404", "GET",
        "/api/upsert/entries/9999999",
        params={"userId": uid}, expected=404)

    req("Search 'salary'", "GET", "/api/upsert/search",
        params={"userId": uid, "q": "salary", "page": 0, "size": 10}, expected=200)

    req("Search 'zomato'", "GET", "/api/upsert/search",
        params={"userId": uid, "q": "zomato", "page": 0, "size": 10}, expected=200)

    req("Search no results", "GET", "/api/upsert/search",
        params={"userId": uid, "q": "xyznonexistentquery999", "page": 0, "size": 10},
        expected=200, note="expect empty page")

    req("Summary", "GET", "/api/upsert/summary",
        params={"userId": uid}, expected=200)

    req("List recurring entries", "GET", "/api/upsert/recurring",
        params={"userId": uid}, expected=200)

    # ── Export ────────────────────────────────────────────────────────────────
    resp = req("Export CSV", "GET", "/api/upsert/entries/export",
               params={"userId": uid}, expected=200)
    if resp and resp.status_code == 200:
        ct = resp.headers.get("Content-Type", "")
        cd = resp.headers.get("Content-Disposition", "")
        csv_ok = "text/csv" in ct and "attachment" in cd
        print(f"  {'PASS' if csv_ok else 'FAIL'}  CSV headers: "
              f"Content-Type={ct!r}  Content-Disposition={cd!r}")

    # ── Bulk delete (netflix + recurring only) ────────────────────────────────
    list_resp = requests.get(f"{CFG['base']}/api/upsert/entries",
                             headers=headers(),
                             params={"userId": uid, "page": 0, "size": 50},
                             timeout=10)
    bulk_ids = []
    if list_resp.status_code == 200:
        try:
            content = list_resp.json().get("content", [])
            for e in content:
                if e.get("id") in [STATE["netflix_id"], STATE["recurring_id"]]:
                    bulk_ids.append(e["id"])
        except Exception:
            pass

    if bulk_ids:
        req("Bulk delete (netflix + recurring)", "POST", "/api/upsert/delete/bulk",
            params={"userId": uid}, json_body=bulk_ids, expected=[200, 204])

    req("Bulk delete empty list -> 400/200", "POST", "/api/upsert/delete/bulk",
        params={"userId": uid}, json_body=[], expected=[200, 400])

    # ── Soft delete income entry ──────────────────────────────────────────────
    if STATE["income_id"]:
        req("Soft delete income entry", "DELETE",
            f"/api/upsert/delete/{STATE['income_id']}",
            params={"userId": uid}, expected=[200, 204])

        req("Verify deleted entry gone -> 404", "GET",
            f"/api/upsert/entries/{STATE['income_id']}",
            params={"userId": uid}, expected=404,
            note="should be 404 after soft delete")

    req("Delete non-existent -> 404", "DELETE",
        "/api/upsert/delete/9999999",
        params={"userId": uid}, expected=404)

    req("Upsert health check", "GET", "/api/upsert/health", expected=200)


# ── Rate-limit stress test ────────────────────────────────────────────────────
def test_upsert_rate_limits():
    """Rapidly hit a read endpoint to confirm 429 behaviour."""
    section("UPSERT — RATE LIMIT STRESS")
    uid = STATE["user_id"]
    print("  Firing /api/upsert/entries 40× with no delay ...")
    hit_429 = False
    for i in range(40):
        resp = req(f"Rate probe {i+1}/40", "GET", "/api/upsert/entries",
                   params={"userId": uid, "page": 0, "size": 1},
                   expected=[200, 429], no_delay=True)
        if resp and resp.status_code == 429:
            hit_429 = True
            print(f"    429 received on probe {i+1} ✓")
            break
    if not hit_429:
        print("  NOTE  No 429 in 40 rapid calls — threshold may be higher.")


# ── Cleanup ───────────────────────────────────────────────────────────────────
def test_cleanup():
    section("CLEANUP — Delete all entries this run")
    uid = STATE["user_id"]

    print(f"    Fetching all entries for userId={uid} ...")
    all_ids: list = []
    page = 0
    while True:
        time.sleep(CFG["request_delay_ms"] / 1000.0)
        resp = requests.get(f"{CFG['base']}/api/upsert/entries",
                            headers=headers(),
                            params={"userId": uid, "page": page, "size": 50},
                            timeout=10)
        if resp.status_code != 200:
            print(f"  WARN  Could not fetch page {page}: {resp.status_code}")
            break
        body    = resp.json()
        content = body.get("content", [])
        all_ids.extend(e["id"] for e in content)
        if body.get("last", True):
            break
        page += 1

    print(f"    Total entries found: {len(all_ids)}")
    if not all_ids:
        print("    Nothing to delete.")
        return

    batch_size = 50
    batches = [all_ids[i:i+batch_size] for i in range(0, len(all_ids), batch_size)]
    for i, batch in enumerate(batches, 1):
        req(f"Bulk delete batch {i}/{len(batches)} ({len(batch)} entries)",
            "POST", "/api/upsert/delete/bulk",
            params={"userId": uid}, json_body=batch, expected=[200, 204])

    resp = req("Verify all entries deleted", "GET", "/api/upsert/entries",
               params={"userId": uid, "page": 0, "size": 10}, expected=200)
    if resp and resp.status_code == 200:
        remaining = resp.json().get("totalElements", -1)
        print(f"  {'PASS' if remaining == 0 else 'WARN (may be entries from other tests)'}"
              f"  Remaining entries: {remaining}")


# ─────────────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"\n{'='*64}")
    print(f"  PFA — UPSERT SERVICE TEST")
    print(f"  {CFG['base']}")
    print(f"  Run tag: {TAG}")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*64}")

    _login()
    test_upsert()

    if not ARGS.no_cleanup:
        test_cleanup()

    # Uncomment to run rate-limit stress after the functional tests:
    # test_upsert_rate_limits()

    print_summary()