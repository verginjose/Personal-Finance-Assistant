"""
Shared utilities for PFA API test suite.
All per-service test files import from here.
"""
import sys
import time
import uuid
import random
import string
from datetime import datetime
from typing import Any

import requests

# ── Unique run tag — appended to names so re-runs never clash ────────────────
RUN_ID = uuid.uuid4().hex[:8]          # e.g. "a3f7c120"
NOW_TS = datetime.now().strftime("%H%M%S")

def run_tag() -> str:
    """Short tag unique to this process invocation."""
    return f"{NOW_TS}-{RUN_ID}"


# ── Global state shared across modules ──────────────────────────────────────
STATE: dict[str, Any] = {
    "token":           None,
    "refresh_token":   None,
    "user_id":         None,
    "expense_id":      None,
    "income_id":       None,
    "recurring_id":    None,
    "netflix_id":      None,
    "group_id":        None,
    "member_id":       None,
    "shared_exp_id":   None,
    "ocr_created_ids": [],
}

RESULTS:  list[dict] = []
CURRENT_SECTION = "SETUP"
_case_counter   = [0]

# ── Config (populated by CLI args in test scripts) ──────────────────────────
CFG: dict[str, Any] = {
    "base":             "http://localhost:8080",
    "email":            None,          # will be set by unique generation or CLI
    "password":         None,          # will be set by unique generation or CLI
    "fail_fast":        False,
    "request_delay_ms": 350,           # ms to sleep between every request
}


def _next_case() -> str:
    _case_counter[0] += 1
    return f"case{_case_counter[0]}"


def headers(extra: dict = None) -> dict:
    h = {"Content-Type": "application/json"}
    if STATE["token"]:
        h["Authorization"] = f"Bearer {STATE['token']}"
    if extra:
        h.update(extra)
    return h


def _record(case_id, name, method, url, status, elapsed_ms, passed,
            note="", response_body=""):
    RESULTS.append({
        "case_id":       case_id,
        "section":       CURRENT_SECTION,
        "name":          name,
        "method":        method,
        "url":           url,
        "status":        status,
        "elapsed_ms":    elapsed_ms,
        "passed":        passed,
        "note":          note,
        "response_body": response_body,
    })

    badge = "PASS" if passed else "FAIL"
    print(f"#{case_id}")
    print(f"  {badge}  [{status}]  {elapsed_ms:>7.1f}ms  {name}" +
          (f"  ({note})" if note else ""))

    if not passed:
        print(f"  FAILURE #{case_id}")
        print(f"    Section  : {CURRENT_SECTION}")
        print(f"    Request  : {method} {url}")
        print(f"    Status   : {status}")
        if note:
            print(f"    Note     : {note}")
        if response_body:
            lines = response_body.strip().splitlines()
            print(f"    Response :")
            for line in lines[:8]:
                print(f"      {line}")
            if len(lines) > 8:
                print(f"      ... ({len(lines) - 8} more lines)")

    if not passed and CFG["fail_fast"]:
        print_summary()
        sys.exit(1)


def req(name: str, method: str, path: str, *,
        expected: int | list[int] = 200,
        json_body=None, params=None,
        extra_headers: dict = None,
        files=None,
        capture: dict = None,
        note: str = "",
        no_delay: bool = False) -> requests.Response | None:
    """
    Execute one HTTP request, record the result, return the Response.

    Set no_delay=True for requests where you intentionally DON'T want the
    inter-request pause (e.g. the idempotency repeat call, or cleanup loops).
    """
    delay_s = CFG["request_delay_ms"] / 1000.0
    if not no_delay and delay_s > 0:
        time.sleep(delay_s)

    case_id = _next_case()
    url = f"{CFG['base']}{path}"
    expected_codes = [expected] if isinstance(expected, int) else expected

    try:
        t0 = time.perf_counter()
        if files:
            resp = requests.request(
                method, url,
                headers={k: v for k, v in headers(extra_headers).items()
                         if k != "Content-Type"},
                files=files, params=params, timeout=30,
            )
        else:
            resp = requests.request(
                method, url,
                headers=headers(extra_headers),
                json=json_body, params=params, timeout=30,
            )
        elapsed = (time.perf_counter() - t0) * 1000
        passed  = resp.status_code in expected_codes

        if capture and passed:
            try:
                body = resp.json()
                for state_key, json_path in capture.items():
                    val = body
                    for part in json_path.split("."):
                        val = val[part]
                    STATE[state_key] = val
            except Exception:
                pass

        resp_body = ""
        if not passed:
            ct = resp.headers.get("Content-Type", "")
            if "json" in ct or "text" in ct or "xml" in ct:
                try:
                    resp_body = resp.text
                except Exception:
                    pass

        _record(case_id, name, method, url, resp.status_code,
                elapsed, passed, note=note, response_body=resp_body)
        return resp

    except requests.exceptions.ConnectionError:
        _record(case_id, name, method, url, 0, 0, False,
                note="CONNECTION REFUSED — is the gateway running?")
        return None
    except Exception as e:
        _record(case_id, name, method, url, 0, 0, False, note=str(e))
        return None


def section(title: str):
    global CURRENT_SECTION
    CURRENT_SECTION = title
    print(f"\n{'─'*64}")
    print(f"  {title}")
    print(f"{'─'*64}")


def print_summary():
    total  = len(RESULTS)
    if not total:
        print("\n  No tests were run.")
        return

    passed = sum(1 for r in RESULTS if r["passed"])
    failed = total - passed
    avg_ms = sum(r["elapsed_ms"] for r in RESULTS) / total
    slow   = [r for r in RESULTS if r["elapsed_ms"] > 1000]

    print(f"\n{'='*64}")
    print(f"  RESULTS")
    print(f"{'='*64}")
    print(f"  Total   : {total}")
    print(f"  Passed  : {passed}")
    if failed:
        print(f"  Failed  : {failed}")
    print(f"  Avg RT  : {avg_ms:.1f}ms")

    if failed:
        failures = [r for r in RESULTS if not r["passed"]]
        print(f"\n  FAILURE INDEX  ({failed} failed)")
        print(f"  {'CASE':<10}  {'SECTION':<28}  TEST")
        print(f"  {'─'*10}  {'─'*28}  {'─'*30}")
        for r in failures:
            print(f"  #{r['case_id']:<9}  {r['section']:<28}  {r['name']}")

        print(f"\n  FAILURE DETAILS")
        for r in failures:
            print(f"\n  #{r['case_id']}  {r['section']} > {r['name']}")
            print(f"    {r['method']:<6}  {r['url']}")
            print(f"    Got status: {r['status']}")
            if r["note"]:
                print(f"    Note     : {r['note']}")
            if r["response_body"]:
                lines = r["response_body"].strip().splitlines()
                print(f"    Response :")
                for line in lines[:10]:
                    print(f"      {line}")
                if len(lines) > 10:
                    print(f"      ... ({len(lines) - 10} more lines)")

    if slow:
        print(f"\n  SLOW REQUESTS (>1000ms)")
        for r in sorted(slow, key=lambda x: -x["elapsed_ms"]):
            label = f"#{r['case_id']}" if not r["passed"] else ""
            print(f"  {r['elapsed_ms']:.0f}ms  {label}  {r['name']}")

    print(f"\n  PER-SERVICE BREAKDOWN")
    services = {
        "Auth":      [r for r in RESULTS if "/auth"      in r["url"]],
        "Upsert":    [r for r in RESULTS if "/upsert"    in r["url"] and "/groups" not in r["url"]],
        "Analytics": [r for r in RESULTS if "/analytics" in r["url"]],
        "Split":     [r for r in RESULTS if "/groups"    in r["url"]],
        "OCR":       [r for r in RESULTS if "/bill"      in r["url"]],
        "Cleanup":   [r for r in RESULTS if r["section"].startswith("CLEANUP")],
    }
    print(f"  {'SERVICE':<12}  {'PASS/TOTAL':<12}  {'AVG RT':>8}  FAILED CASES")
    print(f"  {'─'*12}  {'─'*12}  {'─'*8}  {'─'*20}")
    for svc, runs in services.items():
        if not runs:
            continue
        ok        = sum(1 for r in runs if r["passed"])
        avg       = sum(r["elapsed_ms"] for r in runs) / len(runs)
        fail_tags = " ".join(f"#{r['case_id']}" for r in runs if not r["passed"])
        print(f"  {svc:<12}  {ok}/{len(runs):<11}  {avg:>7.1f}ms  {fail_tags or '—'}")

    print(f"\n{'='*64}")
    print(f"  {'ALL PASSED' if not failed else f'{failed} FAILED'}\n")


# ── Unique user helpers (for per‑run isolation) ─────────────────────────────
def generate_unique_credentials() -> tuple[str, str, str]:
    """
    Generate a random email and strong password unique to this test run.
    Returns (email, password, role).
    """
    tag = run_tag()                       # e.g. "143022-a3f7c120"
    safe_tag = tag.replace(":", "").replace("-", "")
    random_suffix = ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    email = f"test_{safe_tag}_{random_suffix}@pfa.local"
    password = f"Temp{random_suffix}123!"
    role = "USER"
    return email, password,role


def register_user(email: str, password: str, role: str) -> bool:
    """
    Register a new user using the /api/auth/register endpoint.
    Returns True on success (status 201).
    """
    # Temporarily disable delay for this critical request
    orig_delay = CFG["request_delay_ms"]
    CFG["request_delay_ms"] = 0
    resp = req("Register user", "POST", "/api/auth/register",
               json_body={"email": email, "password": password, "role": role},
               expected=201, no_delay=True)
    CFG["request_delay_ms"] = orig_delay
    return resp is not None and resp.status_code == 201


def login_user(email: str, password: str) -> bool:
    """
    Log in an existing user, capturing token, refresh_token and user_id into STATE.
    Returns True on success.
    """
    # Temporarily store original CFG credentials (if any)
    orig_email = CFG.get("email")
    orig_pw = CFG.get("password")
    CFG["email"] = email
    CFG["password"] = password

    resp = req("Login", "POST", "/api/auth/login",
               json_body={"email": email, "password": password},
               expected=200,
               capture={"token": "token",
                        "refresh_token": "refreshToken",
                        "user_id": "userId"},
               no_delay=True)

    # Restore original CFG values
    if orig_email is not None:
        CFG["email"] = orig_email
    else:
        CFG.pop("email", None)
    if orig_pw is not None:
        CFG["password"] = orig_pw
    else:
        CFG.pop("password", None)

    return resp is not None and resp.status_code == 200


def ensure_unique_user() -> tuple[str, str]:
    """
    Convenience function: generates unique credentials, registers the user,
    logs them in, and updates CFG['email']/CFG['password'].
    Returns (email, password).
    """
    email, password, role = generate_unique_credentials()
    print(f"  Creating unique test user: {email}")
    if not register_user(email, password, role):
        print("  FATAL: Registration failed.")
        print_summary()
        sys.exit(1)
    if not login_user(email, password):
        print("  FATAL: Login failed.")
        print_summary()
        sys.exit(1)
    # Store credentials in CFG for any later direct use
    CFG["email"] = email
    CFG["password"] = password
    CFG["role"] = role
    return email, password, role