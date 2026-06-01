"""
PFA Auth Service — standalone test
Usage:
    python test_auth.py
    python test_auth.py --url http://localhost:8080
    python test_auth.py --delay 500          # ms between requests (default 350)
    python test_auth.py --fail-fast
"""
import argparse
import sys
from datetime import datetime

from test_utils import (
    CFG, STATE, section, req, print_summary,
    ensure_unique_user, run_tag
)

# ── CLI ───────────────────────────────────────────────────────────────────────
p = argparse.ArgumentParser()
p.add_argument("--url",       default="http://localhost:8080")
p.add_argument("--delay",     type=int, default=350,
               help="ms between requests")
p.add_argument("--fail-fast", action="store_true")
# Keep --email/--password optional for manual override (rare)
p.add_argument("--email",     default=None,
               help="Optional: override the main test user email (auto‑generated if omitted)")
p.add_argument("--password",  default=None,
               help="Optional: override the main test user password (auto‑generated if omitted)")
ARGS = p.parse_args()

CFG["base"]             = ARGS.url.rstrip("/")
CFG["fail_fast"]        = ARGS.fail_fast
CFG["request_delay_ms"] = ARGS.delay

# Generate a fresh tag for this run (used in registration tests)
TAG = run_tag()


def test_auth():
    section("AUTH SERVICE")

    req("Health check", "GET", "/api/auth/health", expected=200)

    # ── Create a unique main test user (if not overridden) ──────────────────
    if ARGS.email and ARGS.password:
        # Manual override – use provided credentials (no automatic registration)
        CFG["email"] = ARGS.email
        CFG["password"] = ARGS.password
        print(f"  Using provided test user: {CFG['email']}")
        # We assume the user already exists – just login
        from test_utils import login_user
        if not login_user(CFG["email"], CFG["password"]):
            print("  FATAL: Login with provided credentials failed.")
            print_summary()
            sys.exit(1)
    else:
        # Automatic: generate, register, and login a fresh user
        ensure_unique_user()   # sets CFG["email"], CFG["password"] and populates STATE

    print(f"    userId = {STATE['user_id']}")

    # ── Registration tests (using separate fresh emails) ────────────────────
    reg_email1 = f"reg-{TAG}@test.com"
    reg_email2 = f"weak-{TAG}@test.com"

    req("Register new user (should succeed)", "POST", "/api/auth/register",
        json_body={"email": reg_email1, "password": "NewPass999!", "role": "USER"},
        expected=[200, 201])

    req("Register duplicate (same email) -> 409", "POST", "/api/auth/register",
        json_body={"email": reg_email1, "password": "NewPass999!", "role": "USER"},
        expected=409)

    req("Register weak password -> 400", "POST", "/api/auth/register",
        json_body={"email": reg_email2, "password": "abc", "role": "USER"},
        expected=400)

    # ── Login tests with the main user ──────────────────────────────────────
    # (already logged in via ensure_unique_user, but test login endpoint again)
    req("Login valid credentials (main user)", "POST", "/api/auth/login",
        json_body={"email": CFG["email"], "password": CFG["password"]},
        expected=200,
        capture={"token": "token", "refresh_token": "refreshToken", "user_id": "userId"})

    req("Login wrong password -> 401", "POST", "/api/auth/login",
        json_body={"email": CFG["email"], "password": "wrongpassword"},
        expected=401)

    req("Login non-existent user -> 401", "POST", "/api/auth/login",
        json_body={"email": "nobody@nowhere.com", "password": "x"},
        expected=401)

    # ── Authenticated endpoints ─────────────────────────────────────────────
    req("GET /me authenticated", "GET", "/api/auth/me", expected=200)

    # Without token
    saved = STATE["token"]
    STATE["token"] = None
    req("GET /me unauthenticated -> 401", "GET", "/api/auth/me", expected=401, no_delay=True)
    STATE["token"] = saved

    if STATE["refresh_token"]:
        req("Refresh token", "POST", "/api/auth/refresh",
            json_body={"refreshToken": STATE["refresh_token"]},
            expected=200,
            capture={"token": "token", "refresh_token": "refreshToken"})

    req("Change password (same password)", "POST", "/api/auth/change-password",
        json_body={"currentPassword": CFG["password"], "newPassword": CFG["password"]},
        expected=200)

    if STATE["refresh_token"]:
        req("Logout", "POST", "/api/auth/logout",
            json_body={"refreshToken": STATE["refresh_token"]},
            expected=200)

        # Re‑login after logout (using same credentials) – should work
        req("Re-login after logout", "POST", "/api/auth/login",
            json_body={"email": CFG["email"], "password": CFG["password"]},
            expected=200,
            capture={"token": "token", "refresh_token": "refreshToken", "user_id": "userId"})


# ── Rate-limit stress test (optional) ────────────────────────────────────────
def test_auth_rate_limits():
    """
    Fire the same unauthenticated endpoint rapidly to confirm the gateway
    returns 429 once the threshold is crossed.
    """
    section("AUTH — RATE LIMIT STRESS")
    print("  Firing /api/auth/health 30× with no delay to trigger 429 ...")

    hit_429 = False
    for i in range(30):
        resp = req(f"Rate limit probe {i+1}/30", "GET", "/api/auth/health",
                   expected=[200, 429], no_delay=True)
        if resp and resp.status_code == 429:
            hit_429 = True
            print(f"    429 received on probe {i+1} — rate limiting confirmed ✓")
            break

    if not hit_429:
        print("  NOTE  No 429 received in 30 rapid calls — "
              "rate limit threshold may be higher or not applied to /health.")


# ─────────────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"\n{'='*64}")
    print(f"  PFA — AUTH SERVICE TEST")
    print(f"  {CFG['base']}")
    print(f"  Run tag: {TAG}")
    print(f"  delay={ARGS.delay}ms")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'='*64}")

    test_auth()

    # Uncomment to also run rate-limit stress:
    # test_auth_rate_limits()

    print_summary()