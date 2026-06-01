"""
PFA Analytics Service — standalone test
Usage:
    python test_analytics.py
    python test_analytics.py --url http://localhost:8080
    python test_analytics.py --delay 500     # raise if still hitting 429
    python test_analytics.py --fail-fast
"""
import argparse
import sys
import time
from datetime import datetime, timedelta

from test_utils import CFG, STATE, section, req, print_summary, ensure_unique_user

# ── CLI ───────────────────────────────────────────────────────────────────────
p = argparse.ArgumentParser()
p.add_argument("--url",       default="http://localhost:8080")
p.add_argument("--delay",     type=int, default=500,
               help="ms between requests (default 500 — analytics is rate-limited)")
p.add_argument("--fail-fast", action="store_true")
ARGS = p.parse_args()

CFG["base"]             = ARGS.url.rstrip("/")
CFG["fail_fast"]        = ARGS.fail_fast
CFG["request_delay_ms"] = ARGS.delay

# No fixed email/password – we create a fresh user every run


def test_analytics():
    section("ANALYTICS SERVICE")

    uid = STATE["user_id"]
    now   = datetime.now()
    start = (now - timedelta(days=365)).strftime("%Y-%m-%dT00:00:00")
    end   = now.strftime("%Y-%m-%dT23:59:59")

    req("Analytics health check", "GET", "/api/analytics/health", expected=200)

    # ── Pie chart ─────────────────────────────────────────────────────────────
    req("Pie chart - all", "GET", "/api/analytics/category-pie-chart",
        params={"userId": uid}, expected=200)

    req("Pie chart - EXPENSE only", "GET", "/api/analytics/category-pie-chart",
        params={"userId": uid, "transactionFilter": "EXPENSE"}, expected=200)

    req("Pie chart - INCOME only", "GET", "/api/analytics/category-pie-chart",
        params={"userId": uid, "transactionFilter": "INCOME"}, expected=200)

    req("Pie chart - date range", "GET", "/api/analytics/category-pie-chart",
        params={"userId": uid, "startDate": start, "endDate": end}, expected=200)

    req("Pie chart - invalid filter -> 400", "GET", "/api/analytics/category-pie-chart",
        params={"userId": uid, "transactionFilter": "INVALID"}, expected=400)

    # ── Timeline ──────────────────────────────────────────────────────────────
    req("Timeline - MONTHLY", "GET", "/api/analytics/timeline-chart",
        params={"userId": uid, "timelineType": "MONTHLY"}, expected=200)

    req("Timeline - WEEKLY", "GET", "/api/analytics/timeline-chart",
        params={"userId": uid, "timelineType": "WEEKLY"}, expected=200)

    req("Timeline - with date range", "GET", "/api/analytics/timeline-chart",
        params={"userId": uid, "timelineType": "MONTHLY",
                "startDate": start, "endDate": end}, expected=200)

    # ── Comprehensive ─────────────────────────────────────────────────────────
    req("Comprehensive - MONTHLY", "GET", "/api/analytics/comprehensive",
        params={"userId": uid, "timelineType": "MONTHLY"}, expected=200)

    req("Comprehensive - WEEKLY", "GET", "/api/analytics/comprehensive",
        params={"userId": uid, "timelineType": "WEEKLY"}, expected=200)

    req("Comprehensive - with date range", "GET", "/api/analytics/comprehensive",
        params={"userId": uid, "timelineType": "MONTHLY",
                "startDate": start, "endDate": end}, expected=200)

    # ── Custom analytics POST ─────────────────────────────────────────────────
    req("Custom analytics POST", "POST", "/api/analytics/custom-analytics",
        json_body={"userId": uid, "timelineType": "MONTHLY",
                   "startDate": start, "endDate": end},
        expected=200)

    req("Custom analytics userId mismatch -> 403", "POST", "/api/analytics/custom-analytics",
        json_body={"userId": "00000000-0000-0000-0000-000000000000",
                   "timelineType": "MONTHLY"},
        expected=403)

    # ── Transaction entries ───────────────────────────────────────────────────
    req("Transaction entries page 0", "GET", "/api/analytics/transaction-entries",
        params={"userId": uid, "page": 0, "size": 10}, expected=200)

    req("Transaction entries page 1", "GET", "/api/analytics/transaction-entries",
        params={"userId": uid, "page": 1, "size": 10}, expected=200)

    # ── By category ───────────────────────────────────────────────────────────
    req("Income by category - SALARY", "GET",
        "/api/analytics/transactions/income-by-category",
        params={"userId": uid, "incomeCategory": "SALARY"}, expected=200)

    req("Income by category - with date range", "GET",
        "/api/analytics/transactions/income-by-category",
        params={"userId": uid, "incomeCategory": "SALARY",
                "startDate": "2026-01-01", "endDate": "2026-12-31"},
        expected=200)

    req("Income by category - invalid -> 400", "GET",
        "/api/analytics/transactions/income-by-category",
        params={"userId": uid, "incomeCategory": "INVALID"}, expected=400)

    # ── By type ───────────────────────────────────────────────────────────────
    req("By type - EXPENSE", "GET", "/api/analytics/transactions/by-type",
        params={"userId": uid, "type": "EXPENSE"}, expected=200)

    req("By type - INCOME", "GET", "/api/analytics/transactions/by-type",
        params={"userId": uid, "type": "INCOME"}, expected=200)

    req("By type - EXPENSE with date range", "GET", "/api/analytics/transactions/by-type",
        params={"userId": uid, "type": "EXPENSE",
                "startDate": "2026-01-01", "endDate": "2026-12-31"},
        expected=200)

    req("By type - unauthenticated -> 401", "GET", "/api/analytics/transactions/by-type",
        params={"userId": uid, "type": "EXPENSE"},
        extra_headers={"Authorization": "Bearer invalidtoken"},
        expected=401)


# ── Rate-limit stress test (optional) ────────────────────────────────────────
def test_analytics_rate_limits():
    """
    Rapidly hit a cheap analytics endpoint to confirm 429.
    """
    section("ANALYTICS — RATE LIMIT STRESS")
    uid = STATE["user_id"]
    print("  Firing /api/analytics/health 50× with no delay ...")
    hit_429 = False
    for i in range(50):
        resp = req(f"Rate probe {i+1}/50", "GET", "/api/analytics/health",
                   expected=[200, 429], no_delay=True)
        if resp and resp.status_code == 429:
            hit_429 = True
            print(f"    429 received on probe {i+1} ✓")
            print("    Waiting 10s for rate limit window to reset ...")
            time.sleep(10)
            recovery = req("Recovery after rate limit", "GET", "/api/analytics/health",
                           expected=200, no_delay=True)
            if recovery and recovery.status_code == 200:
                print("    Recovery confirmed ✓")
            break
    if not hit_429:
        print("  NOTE  No 429 in 50 rapid calls — threshold may be higher.")


# ─────────────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"\n{'='*64}")
    print(f"  PFA — ANALYTICS SERVICE TEST")
    print(f"  {CFG['base']}")
    print(f"  delay={ARGS.delay}ms")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*64}")

    # Create a fresh user and log in (unique per run)
    ensure_unique_user()

    test_analytics()

    # Uncomment to run rate-limit stress:
    # test_analytics_rate_limits()

    print_summary()