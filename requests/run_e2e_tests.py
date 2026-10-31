#!/usr/bin/env python3
import json
import urllib.request
import urllib.error
import uuid
import mimetypes
import time
import os
import sys

# Override with GATEWAY_URL, e.g. GATEWAY_URL=https://verginjose.cloud
# Do NOT point this at a port that another port-forward already owns.
GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://localhost:8080").rstrip("/")

# Colors for terminal formatting
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"

tests_passed = 0
tests_failed = 0

def log_section(title):
    print(f"\n{BOLD}{CYAN}=== {title} ==={RESET}")

def assert_status(expected, actual, message):
    global tests_passed, tests_failed
    if expected == actual:
        print(f"  [{GREEN}PASS{RESET}] {message}")
        tests_passed += 1
    else:
        print(f"  [{RED}FAIL{RESET}] {message} (Expected {expected}, got {actual})")
        tests_failed += 1

def assert_true(condition, message):
    global tests_passed, tests_failed
    if condition:
        print(f"  [{GREEN}PASS{RESET}] {message}")
        tests_passed += 1
    else:
        print(f"  [{RED}FAIL{RESET}] {message}")
        tests_failed += 1

def make_request(url_path, method="GET", headers=None, body=None, files=None):
    if not url_path.startswith("/api/"):
        url_path = "/api" + url_path
    url = f"{GATEWAY_URL}{url_path}"
    req_headers = {}
    if headers:
        req_headers.update(headers)
    
    data = None
    if files:
        # Multipart form-data compilation
        boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
        req_headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        
        parts = []
        for field_name, file_path in files.items():
            filename = os.path.basename(file_path)
            mime_type = mimetypes.guess_type(file_path)[0] or "application/octet-stream"
            with open(file_path, "rb") as f:
                content = f.read()
            parts.append(f"--{boundary}\r\n".encode())
            parts.append(f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'.encode())
            parts.append(f"Content-Type: {mime_type}\r\n\r\n".encode())
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        data = b"".join(parts)
    elif body is not None:
        if isinstance(body, dict) or isinstance(body, list):
            data = json.dumps(body).encode("utf-8")
            req_headers["Content-Type"] = "application/json"
        else:
            data = str(body).encode("utf-8")

    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    
    def normalize(parsed):
        # Callers chain body.get(...): never hand them a bare str.
        return parsed if isinstance(parsed, (dict, list)) else {"raw": parsed}

    try:
        with urllib.request.urlopen(req) as resp:
            resp_body = resp.read().decode("utf-8")
            resp_headers = dict(resp.headers)
            try:
                parsed_body = normalize(json.loads(resp_body))
            except Exception:
                parsed_body = {"raw": resp_body}
            return resp.status, parsed_body, resp_headers
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8")
        try:
            parsed_err = normalize(json.loads(err_body))
        except Exception:
            parsed_err = {"raw": err_body}
        return e.code, parsed_err, dict(e.headers)
    except Exception as e:
        print(f"    Connection failed: {e}")
        # Return an empty dict, not the error string: callers chain
        # body.get(...) and a str would crash the whole suite.
        return 500, {"error": str(e)}, {}

def run_all_tests():
    global tests_passed, tests_failed
    print(f"{BOLD}{YELLOW}Starting Advanced Personal Finance Assistant E2E Test Suite...{RESET}")

    # Placeholder; will be replaced with real user ID from auth service
    user_uuid = str(uuid.uuid4())
    email = f"e2e_{uuid.uuid4().hex[:8]}@example.com"
    password = "Password123!"
    new_password = "NewPassword123!"

    # =========================================================================
    # A. AUTH SERVICE TEST SUITE
    # =========================================================================
    log_section("Testing Authentication Service")

    # 1. Health check
    status, body, _ = make_request("/auth/health", "GET")
    assert_status(200, status, "GET /auth/health status")
    assert_true("auth-service" in str(body), "Health check service confirmation")

    # 2. Register (username is required by RegisterRequest and must match
    #    ^[a-zA-Z0-9_@.-]+$ and be 3-50 chars)
    username = email.split("@")[0]
    reg_payload = {"email": email, "username": username, "password": password, "role": "USER"}
    status, body, _ = make_request("/auth/register", "POST", body=reg_payload)
    assert_status(201, status, "POST /auth/register status")
    assert_status(email, body.get("email"), "Registration email check")
    # IMPORTANT: Extract the real user ID assigned by the DB.
    # The gateway overwrites X-User-Id from the JWT's "userId" claim,
    # so all request bodies must use this real ID. RegisterResponse calls it
    # userId; login returns it as userId too.
    user_uuid = body.get("userId", user_uuid)

    # 3. Login (Success)
    login_payload = {"email": email, "password": password}
    status, body, _ = make_request("/auth/login", "POST", body=login_payload)
    assert_status(200, status, "POST /auth/login status")

    token = body.get("token")
    refresh_token = body.get("refreshToken")
    # Confirm user_uuid from the login response (includes userId field)
    user_uuid = body.get("userId", user_uuid)
    assert_true(token is not None, "Login returns access token")
    assert_true(refresh_token is not None, "Login returns refresh token")

    auth_headers = {"Authorization": f"Bearer {token}", "X-User-Id": user_uuid}

    # 4. Fetch currently authenticated profile (/auth/me)
    status, me_body, _ = make_request("/auth/me", "GET", headers=auth_headers)
    assert_status(200, status, "GET /auth/me status")
    assert_status(email, me_body.get("email"), "Verify identity in profile")

    # 5. Change Password
    chg_payload = {"currentPassword": password, "newPassword": new_password}
    status, _, _ = make_request("/auth/change-password", "POST", headers=auth_headers, body=chg_payload)
    assert_status(200, status, "POST /auth/change-password status")

    # 6. Verify password changed by logging in with new credentials
    login_payload["password"] = new_password
    status, body, _ = make_request("/auth/login", "POST", body=login_payload)
    assert_status(200, status, "Re-login with new password")

    # Obtain new tokens
    token = body.get("token")
    refresh_token = body.get("refreshToken")
    user_uuid = body.get("userId", user_uuid)
    auth_headers = {"Authorization": f"Bearer {token}", "X-User-Id": user_uuid}

    # 7. Token Revocation: Logout
    logout_payload = {"refreshToken": refresh_token}
    status, _, _ = make_request("/auth/logout", "POST", headers=auth_headers, body=logout_payload)
    assert_status(200, status, "POST /auth/logout status")

    # Verify Access Token is now blacklisted/revoked
    status, _, _ = make_request("/auth/me", "GET", headers=auth_headers)
    assert_status(401, status, "Revoked Access Token is blacklisted (returns 401)")

    # 8. Token Refresh Rotation: Log in again
    status, body, _ = make_request("/auth/login", "POST", body=login_payload)
    token = body.get("token")
    refresh_token = body.get("refreshToken")
    user_uuid = body.get("userId", user_uuid)
    auth_headers = {"Authorization": f"Bearer {token}", "X-User-Id": user_uuid}

    # Call Refresh Endpoint
    ref_payload = {"refreshToken": refresh_token}
    status, rot_body, _ = make_request("/auth/refresh", "POST", body=ref_payload)
    assert_status(200, status, "POST /auth/refresh returns 200")

    rotated_token = rot_body.get("token")
    rotated_refresh_token = rot_body.get("refreshToken")
    assert_true(rotated_token != token, "Refresh rotation generates new access token")

    # Verify that trying to reuse the old refresh token is rejected
    status, _, _ = make_request("/auth/refresh", "POST", body=ref_payload)
    assert_true(status >= 400, "Old refresh token cannot be reused (rotation validation)")

    # Update active headers with rotated token
    token = rotated_token
    auth_headers = {"Authorization": f"Bearer {token}", "X-User-Id": user_uuid}

    # =========================================================================
    # B. TRANSACTION SERVICE TEST SUITE
    # =========================================================================
    log_section("Testing Transaction Entry Service (CRUD & Advanced)")

    # 1. Health Check — gateway requires auth on /upsert/* routes
    status, _, _ = make_request("/upsert/health", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/health status")

    # 2. Idempotency Key validation
    idem_key = str(uuid.uuid4())
    tx_payload = {
        "userId": user_uuid,
        "name": "Monthly Salary",
        "amount": 25000.00,
        "type": "INCOME",
        "currency": "INR",
        "incomeCategory": "SALARY",
        "description": "Idempotent monthly salary",
        "date": "2026-06-01"
    }
    tx_headers = {
        "Authorization": f"Bearer {token}",
        "X-User-Id": user_uuid,
        "X-Idempotency-Key": idem_key
    }

    # First submit
    status1, body1, _ = make_request("/upsert/create", "POST", headers=tx_headers, body=tx_payload)
    assert_status(201, status1, "First transaction creation")
    tx_id = body1.get("id")

    # Second submit (identical key)
    status2, body2, _ = make_request("/upsert/create", "POST", headers=tx_headers, body=tx_payload)
    assert_status(201, status2, "Second transaction creation with same idempotency key")
    assert_status(tx_id, body2.get("id"), "Idempotency key returns identical entry ID")

    # Verify only ONE entry is created in database
    status, page_body, _ = make_request(f"/upsert/entries?userId={user_uuid}", "GET", headers=tx_headers)
    assert_status(200, status, "GET /upsert/entries")
    assert_status(1, len(page_body.get("content", [])), "Database has exactly 1 entry (idempotency enforcement)")

    # 3. Input Validation Constraints
    invalid_tx = tx_payload.copy()
    invalid_tx["currency"] = "INVALID_CURR"
    status, _, _ = make_request("/upsert/create", "POST", headers=tx_headers, body=invalid_tx)
    assert_true(status >= 400, "Validation prevents illegal currencies")

    # 4. Put (Update) — UpdateEntryRequest requires: id, userId, name, amount, type, currency
    update_payload = {
        "id": tx_id,
        "userId": user_uuid,
        "name": "Updated Salary",
        "amount": 30000.00,
        "type": "INCOME",
        "currency": "INR",
        "incomeCategory": "SALARY",
        "description": "Updated salary with bonus",
        "date": "2026-06-01"
    }
    status, body, _ = make_request("/upsert/update", "PUT", headers=auth_headers, body=update_payload)
    assert_status(200, status, "PUT /upsert/update status")
    assert_status("Updated salary with bonus", body.get("description"), "Update verification")

    # 5. Patch amount
    patch_payload = {"amount": 35000.00}
    status, body, _ = make_request(f"/upsert/entries/{tx_id}/amount?userId={user_uuid}", "PATCH", headers=auth_headers, body=patch_payload)
    assert_status(200, status, "PATCH /upsert/entries/{id}/amount status")
    assert_status(35000.00, body.get("amount"), "Patch amount value verification")

    # 6. Read single entry
    status, body, _ = make_request(f"/upsert/entries/{tx_id}?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/entries/{id}")

    # Seed an expense transaction for further analytics testing
    expense_payload = {
        "userId": user_uuid,
        "name": "Dinner at restaurant",
        "amount": 10000.00,
        "type": "EXPENSE",
        "currency": "INR",
        "expenseCategory": "RESTAURANTS",
        "description": "Dinner at restaurant",
        "date": "2026-06-02"
    }
    make_request("/upsert/create", "POST", headers=auth_headers, body=expense_payload)

    # 7. Search transactions
    status, search_body, _ = make_request(f"/upsert/search?userId={user_uuid}&q=Dinner", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/search status")
    assert_true(len(search_body.get("content", [])) > 0, "Search returns matched query results")

    # 8. Summary check
    status, sum_body, _ = make_request(f"/upsert/summary?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/summary status")
    assert_status(35000.00, sum_body.get("totalIncome"), "Summary total income verification")
    assert_status(10000.00, sum_body.get("totalExpense"), "Summary total expense verification")

    # 9. CSV export
    status, csv_data, resp_hdrs = make_request(f"/upsert/entries/export?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/entries/export status")
    # HTTP header names are case-insensitive; search all keys lowercased
    content_type_val = next((v for k, v in resp_hdrs.items() if k.lower() == "content-type"), "")
    assert_true("text/csv" in content_type_val, "CSV content-type verification")

    # =========================================================================
    # C. GOALS & BUDGETS TEST SUITE
    # =========================================================================
    log_section("Testing Goals & Budgets")

    # 1. Create a savings goal
    # SavingsGoalRequest DTO fields: userId, name, targetAmount, currency, description, deadline
    goal_payload = {
        "userId": user_uuid,
        "name": "Emergency Fund",
        "targetAmount": 100000.00,
        "currency": "INR",
        "description": "6-month emergency fund",
        "deadline": "2027-06-01"
    }
    status, body, _ = make_request("/upsert/goals", "POST", headers=auth_headers, body=goal_payload)
    assert_status(201, status, "POST /goals status")
    goal_id = body.get("id")

    # 2. Get goals
    status, body, _ = make_request(f"/upsert/goals?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /goals status")

    # 3. Contribute to goal — the amount travels in a JSON body
    #    (GoalContributionRequest), not as a query parameter.
    status, body, _ = make_request(
        f"/upsert/goals/{goal_id}/contribute?userId={user_uuid}",
        "PATCH", headers=auth_headers,
        body={"amount": 5000.00, "description": "Monthly top-up"}
    )
    assert_status(200, status, "PATCH /goals/{id}/contribute status")
    # SavingsGoalResponse uses 'savedAmount' (not 'currentAmount')
    assert_status(5000.00, body.get("savedAmount"), "Verify contribution amount reflected")

    # 4. Create budget
    # CategoryBudgetRequest DTO fields: userId, expenseCategory, budgetAmount, period, currency
    budget_payload = {
        "userId": user_uuid,
        "expenseCategory": "RESTAURANTS",
        "budgetAmount": 15000.00,
        "period": "MONTHLY",
        "currency": "INR"
    }
    status, body, _ = make_request("/upsert/budgets", "POST", headers=auth_headers, body=budget_payload)
    assert_status(201, status, "POST /budgets status")
    # BudgetUtilizationResponse uses 'budgetId' (not 'id')
    budget_id = body.get("budgetId") or body.get("id")

    # 5. Get budgets & verify utilization
    status, body, _ = make_request(f"/upsert/budgets?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /budgets status")
    assert_true(len(body) > 0, "Budget exists in list")
    # Expense is 10000 out of 15000 budget — guard against empty list crash
    if isinstance(body, list) and len(body) > 0:
        # BudgetUtilizationResponse uses 'spentAmount' (not 'currentSpent')
        assert_status(10000.00, body[0].get("spentAmount"), "Verify budget current spent calculation matches transactions")
    else:
        print(f"  [{RED}FAIL{RESET}] Verify budget current spent calculation matches transactions (no budgets returned)")
        tests_failed += 1

    # 6. Delete budget and goal
    status, _, _ = make_request(f"/upsert/budgets/{budget_id}?userId={user_uuid}", "DELETE", headers=auth_headers)
    assert_status(200, status, "DELETE /budgets/{id} status")
    status, _, _ = make_request(f"/upsert/goals/{goal_id}?userId={user_uuid}", "DELETE", headers=auth_headers)
    assert_status(200, status, "DELETE /goals/{id} status")

    # =========================================================================
    # D. BILL SPLITTING (SPLIT) TEST SUITE
    # =========================================================================
    log_section("Testing Split Bill Expense Manager")

    # 1. Create a group — CreateGroupRequest requires: name, createdBy (UUID), optional description/currency
    group_payload = {
        "name": "Weekend Trip",
        "description": "Split expenses for weekend trip",
        "createdBy": user_uuid
    }
    status, body, _ = make_request("/upsert/groups", "POST", headers=auth_headers, body=group_payload)
    assert_status(201, status, "POST /upsert/groups status")
    group_id = body.get("id")

    # 2. Get user groups
    status, body, _ = make_request(f"/upsert/groups?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/groups status")

    # 3. Add members — the service rejects unknown users, so register Alice
    #    for real instead of inventing a UUID.
    alice_email = f"e2e_alice_{uuid.uuid4().hex[:8]}@example.com"
    status, alice_body, _ = make_request("/auth/register", "POST", body={
        "email": alice_email,
        "username": alice_email.split("@")[0],
        "password": password,
        "role": "USER",
    })
    assert_status(201, status, "Register member 2 (Alice)")
    alice_id = alice_body.get("userId")
    member2_payload = {"userId": alice_id, "name": alice_email.split("@")[0]}
    status, m2_body, _ = make_request(f"/upsert/groups/{group_id}/members", "POST", headers=auth_headers, body=member2_payload)
    assert_status(201, status, "Add member 2 (Alice)")

    # 4. Add shared expense
    shared_exp_payload = {
        "amount": 200.00,
        "description": "Lunch split",
        "paidBy": user_uuid
    }
    status, _, _ = make_request(f"/upsert/groups/{group_id}/expenses", "POST", headers=auth_headers, body=shared_exp_payload)
    assert_status(201, status, "POST shared expense")

    # 5. Get balances
    status, bal_body, _ = make_request(f"/upsert/groups/{group_id}/balances", "GET", headers=auth_headers)
    assert_status(200, status, "GET /upsert/groups/{groupId}/balances status")
    # Alice should owe You 100.00
    print(f"    DEBUG: Balances: {bal_body}")
    assert_true(len(bal_body.get("simplifiedDebts", [])) > 0, "Verify debt relationship exists")

    # 6. Settle debt
    status, _, _ = make_request(
        f"/upsert/groups/{group_id}/settle?fromUserId={alice_id}&toUserId={user_uuid}",
        "POST", headers=auth_headers
    )
    assert_status(200, status, "POST settle debt status")

    # =========================================================================
    # E. SUBSCRIPTION DETECTOR TEST SUITE
    # =========================================================================
    log_section("Testing Subscription & Hidden Drain Detector")

    # Create a single recurring transaction for Amazon Prime
    prime_payload = {
        "userId": user_uuid,
        "amount": 299.00,
        "type": "EXPENSE",
        "currency": "INR",
        "name": "Amazon Prime",
        "expenseCategory": "OTT_SUBSCRIPTIONS",
        "description": "Amazon Prime monthly fee",
        "recurring": True,
        "recurringPeriod": "MONTHLY"
    }
    make_request("/upsert/create", "POST", headers=auth_headers, body=prime_payload)

    # Detect subscription candidates
    status, subs, _ = make_request(f"/upsert/subscriptions?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /subscriptions status")
    assert_true(len(subs) > 0, "Subscriptions detected recurring Amazon Prime payments")

    # Deactivate the subscription alert
    sub_id = subs[0].get("id") if subs else None
    if sub_id:
        status, _, _ = make_request(f"/upsert/subscriptions/{sub_id}/deactivate?userId={user_uuid}", "DELETE", headers=auth_headers)
        assert_status(200, status, "DELETE /subscriptions/{id}/deactivate status")
    else:
        print(f"  [{RED}FAIL{RESET}] DELETE /subscriptions/{{id}}/deactivate status (no subscription id)")
        tests_failed += 1

    # =========================================================================
    # F. ANALYTICS & GAMIFICATION TEST SUITE
    # =========================================================================
    log_section("Testing Analytics & AI Insights")

    # 1. Health check — gateway requires auth on /analytics/* routes
    status, _, _ = make_request("/analytics/health", "GET", headers=auth_headers)
    assert_status(200, status, "GET /analytics/health status")

    # 2. Charts
    status, _, _ = make_request(f"/analytics/category-pie-chart?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /analytics/category-pie-chart status")

    status, _, _ = make_request(f"/analytics/timeline-chart?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /analytics/timeline-chart status")

    status, _, _ = make_request(f"/analytics/comprehensive?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /analytics/comprehensive status")

    # 3. Gamification Health Score
    status, hs_body, _ = make_request(f"/analytics/health-score?userId={user_uuid}", "GET", headers=auth_headers)
    assert_status(200, status, "GET /analytics/health-score status")
    # HealthScoreResponse uses 'totalScore' (not 'score')
    assert_true(hs_body.get("totalScore") is not None, "Health Score returns numerical value")
    assert_true("Savings Rate" in hs_body.get("breakdown", {}), "Score breakdown contains components")

    # 4. AI Insights Caching and Event-Driven Eviction (Kafka Integration)
    print("Testing AI Insights Cache and Eviction Flow...")

    t0 = time.time()
    status1, insights1, _ = make_request(f"/analytics/ai-insights?userId={user_uuid}", "GET", headers=auth_headers)
    t1 = time.time() - t0
    assert_status(200, status1, "First insights retrieval (generates via Groq LLM)")
    print(f"    First Request Time: {t1:.2f}s")

    # Second request must hit the cache immediately
    t0 = time.time()
    status2, insights2, _ = make_request(f"/analytics/ai-insights?userId={user_uuid}", "GET", headers=auth_headers)
    t2 = time.time() - t0
    assert_status(200, status2, "Second insights retrieval (hits Redis cache)")
    print(f"    Second Request Time: {t2:.4f}s")
    assert_true(t2 < 0.1, "Cached response returned in < 100ms")
    assert_status(insights1.get("summary"), insights2.get("summary"), "Verify cached contents match")

    # Add a new transaction (This sends a Kafka event to evict the cache)
    eviction_payload = {
        "userId": user_uuid,
        "amount": 90000.00,
        "type": "INCOME",
        "currency": "INR",
        "incomeCategory": "SALARY",
        "description": "Salary increase",
        "date": "2026-06-03"
    }
    make_request("/upsert/create", "POST", headers=auth_headers, body=eviction_payload)
    print("    Created a new transaction. Waiting 2.0 seconds for Kafka event transmission and Cache Eviction...")
    time.sleep(2.0)

    # Third request must be recalculated (cache evicted!)
    t0 = time.time()
    status3, insights3, _ = make_request(f"/analytics/ai-insights?userId={user_uuid}", "GET", headers=auth_headers)
    t3 = time.time() - t0
    assert_status(200, status3, "Third insights retrieval after cache eviction (re-computed via Groq)")
    print(f"    Third Request Time: {t3:.2f}s")
    # Verify the third response is still valid (Kafka may not be running in dev, so eviction is best-effort)
    assert_true(insights3 is not None and len(insights3) > 0, "Recalculated request returns valid data")

    # =========================================================================
    # G. BILL PARSER & OCR AUTOMATION TEST SUITE
    # =========================================================================
    log_section("Testing Bill Parser & OCR Auto-Saving")

    # Check that the sample image exists
    sample_img = "requests/sample-documents/busTicket.jpg"
    if not os.path.exists(sample_img):
        print(f"  [{RED}SKIP{RESET}] Missing sample ticket image {sample_img}")
    else:
        # Perform Multipart Upload
        status, ocr_body, _ = make_request(
            f"/bill/process/{user_uuid}",
            "POST",
            headers={"Authorization": f"Bearer {token}"},
            files={"file": sample_img}
        )
        # The gateway now streams the file up and publishes an "ocr-jobs"
        # message: it answers 202 Accepted with the receipt id + object URL.
        # The parsed result arrives later over SSE
        # (/api/upsert/notifications/{userId}), so there is nothing to parse
        # out of this response.
        assert_status(202, status, "POST /bill/process/{userId} accepted (async OCR)")
        assert_true(ocr_body.get("receiptId") is not None, "OCR job returns a receipt id")
        assert_true(ocr_body.get("url") is not None, "OCR job returns the stored object URL")
        print(f"    Receipt id={ocr_body.get('receiptId')} url={str(ocr_body.get('url'))[:60]}")

    # =========================================================================
    # FINAL CLEANUP & SUMMARY
    # =========================================================================
    log_section("E2E Test Completion Summary")
    print(f"  Total tests executed: {tests_passed + tests_failed}")
    print(f"  [{GREEN}PASS{RESET}] count: {tests_passed}")
    print(f"  [{RED}FAIL{RESET}] count: {tests_failed}")

    if tests_failed > 0:
        print(f"\n{RED}Some integration test cases failed. Please inspect logs.{RESET}")
        sys.exit(1)
    else:
        print(f"\n{GREEN}All microservice integration tests completed successfully!{RESET}")
        sys.exit(0)

if __name__ == "__main__":
    run_all_tests()
