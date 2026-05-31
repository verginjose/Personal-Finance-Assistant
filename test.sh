#!/bin/bash
# test.sh — API verification script (replaces all .http files)
# Usage:
#   ./test.sh              → full test suite (register → login → all endpoints)
#   ./test.sh auth         → auth tests only
#   ./test.sh upsert       → transaction tests only
#   ./test.sh ocr          → ocr parser tests only
#   ./test.sh analytics    → analytics tests only
#   ./test.sh health       → just health checks
#   ./test.sh db           → query Postgres/Redis/ClickHouse directly
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

GW="http://localhost:8080"
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; GRAY='\033[0;37m'; NC='\033[0m'
PASS=0; FAIL=0

# ── Helpers ───────────────────────────────────────────────────────────────────

check() {
    local name="$1" expected="$2" actual_code="$3" body="$4"
    if [[ "$actual_code" == "$expected" ]]; then
        echo -e "  ${GREEN}✔${NC} $name  ${GRAY}[$actual_code]${NC}"
        PASS=$((PASS+1))
    else
        echo -e "  ${RED}✗${NC} $name  ${GRAY}[got $actual_code, want $expected]${NC}"
        echo -e "    ${GRAY}$body${NC}" | head -3
        FAIL=$((FAIL+1))
    fi
}

json() { echo "$1" | python3 -m json.tool 2>/dev/null || echo "$1"; }

section() { echo -e "\n${CYAN}══ $1 ══${NC}"; }

# ── Auth ──────────────────────────────────────────────────────────────────────

do_auth() {
    section "AUTH SERVICE"

    EMAIL="testuser_$$@example.com"
    PASSWORD="Password123!"

    # 1. Register
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/auth/register" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"role\":\"USER\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /api/auth/register" "201" "$code" "$body"

    # 2. Login
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /api/auth/login" "200" "$code" "$body"
    TOKEN=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token',''))" 2>/dev/null || echo "")
    USER_ID=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('userId',''))" 2>/dev/null || echo "")
    echo -e "    ${GRAY}userId=$USER_ID${NC}"

    # 3. Bad login (wrong password)
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"wrongpass\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /api/auth/login (wrong password → 401)" "401" "$code" "$body"

    # 4. Refresh token
    REFRESH=$(echo "$(curl -s -X POST "$GW/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('refreshToken',''))" 2>/dev/null)" || echo "")
    if [[ -n "$REFRESH" ]]; then
        resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/auth/refresh" \
            -H "Content-Type: application/json" \
            -d "{\"refreshToken\":\"$REFRESH\"}")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "POST /api/auth/refresh" "200" "$code" "$body"
    fi

    export TOKEN USER_ID
}

# ── Upsert / Transactions ──────────────────────────────────────────────────────

do_upsert() {
    section "UPSERT SERVICE (transactions)"
    [[ -z "${TOKEN:-}" ]] && { echo -e "  ${YELLOW}⚠ No token — run auth first${NC}"; return; }

    AUTH="-H \"Authorization: Bearer $TOKEN\""

    # Create INCOME
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/create" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"userId\":\"$USER_ID\",\"name\":\"Salary\",\"amount\":50000,\"type\":\"INCOME\",\"incomeCategory\":\"SALARY\",\"currency\":\"INR\",\"description\":\"Monthly salary\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /create (income)" "201" "$code" "$body"
    INCOME_ID=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || echo "")

    # Create EXPENSE
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/create" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"userId\":\"$USER_ID\",\"name\":\"Rent\",\"amount\":15000,\"type\":\"EXPENSE\",\"currency\":\"INR\",\"expenseCategory\":\"BILLS_AND_UTILITIES\",\"description\":\"Monthly rent\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /create (expense)" "201" "$code" "$body"
    EXPENSE_ID=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || echo "")

    # Create RECURRING
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/create" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"userId\":\"$USER_ID\",\"name\":\"Gym\",\"amount\":1200,\"type\":\"EXPENSE\",\"currency\":\"INR\",\"expenseCategory\":\"HEALTHCARE\",\"recurring\":true,\"recurringPeriod\":\"MONTHLY\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /create (recurring monthly)" "201" "$code" "$body"

    # List all entries
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/entries?userId=$USER_ID&page=0&size=10" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /entries" "200" "$code" "$body"
    TOTAL=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('totalElements','?'))" 2>/dev/null || echo "?")
    echo -e "    ${GRAY}totalElements=$TOTAL${NC}"

    # Get single entry
    if [[ -n "$INCOME_ID" ]]; then
        resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/entries/$INCOME_ID?userId=$USER_ID" \
            -H "Authorization: Bearer $TOKEN")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "GET /entries/{id}" "200" "$code" "$body"
    fi

    # Summary
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/summary?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /summary" "200" "$code" "$body"
    echo -e "    ${GRAY}$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print('income='+str(d.get('totalIncome','?'))+' expense='+str(d.get('totalExpense','?')))" 2>/dev/null)${NC}"

    # List recurring
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/recurring?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /recurring" "200" "$code" "$body"

    # Date filter
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/entries?userId=$USER_ID&startDate=2026-01-01&endDate=2026-12-31&page=0&size=10" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /entries?startDate&endDate" "200" "$code" "$body"

    # Patch amount
    if [[ -n "$EXPENSE_ID" ]]; then
        resp=$(curl -s -w "\n%{http_code}" -X PATCH "$GW/api/upsert/entries/$EXPENSE_ID/amount?userId=$USER_ID" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TOKEN" \
            -d '{"amount":16000}')
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "PATCH /entries/{id}/amount" "200" "$code" "$body"
    fi

    # Export CSV
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/entries/export?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | tail -1)
    check "GET /entries/export (CSV)" "200" "$body" ""

    # Search
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/search?userId=$USER_ID&q=salary&page=0&size=5" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /search?query=salary" "200" "$code" "$body"

    # Unauthenticated → 401
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/entries?userId=$USER_ID")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /entries (no token → 401)" "401" "$code" "$body"

    export INCOME_ID EXPENSE_ID
}

# ── Group Shared Expenses & Debt Settlement ───────────────────────────────────

do_group_split_tests() {
    section "GROUP SHARED EXPENSES & DEBT SETTLEMENT"
    [[ -z "${TOKEN:-}" ]] && { echo -e "  ${YELLOW}⚠ No token — run auth first${NC}"; return; }

    # 1. Register a second user to be a member of the group
    local member_email="member_group_$$@example.com"
    local member_password="Password123!"
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/auth/register" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$member_email\",\"password\":\"$member_password\",\"role\":\"USER\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /api/auth/register (group member)" "201" "$code" "$body"
    local member_id=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('userId',''))" 2>/dev/null || echo "")

    # 2. Create Group
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/groups" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"name\":\"Paris Trip\",\"description\":\"Paris holiday shared expenses\",\"createdBy\":\"$USER_ID\",\"currency\":\"EUR\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /upsert/groups (create group)" "201" "$code" "$body"
    local group_id=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || echo "")

    if [[ -n "$group_id" && -n "$member_id" ]]; then
        # 3. Add Member to Group
        resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/groups/$group_id/members" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TOKEN" \
            -d "{\"userId\":\"$member_id\",\"name\":\"Friend Member\"}")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "POST /upsert/groups/{id}/members (add group member)" "201" "$code" "$body"

        # 4. Add Shared Expense
        resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/groups/$group_id/expenses" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TOKEN" \
            -d "{\"description\":\"Hotel Booking\",\"amount\":200,\"currency\":\"EUR\",\"paidBy\":\"$USER_ID\",\"splitType\":\"EQUAL\",\"expenseCategory\":\"LODGING\"}")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "POST /upsert/groups/{id}/expenses (add shared expense)" "201" "$code" "$body"

        # 5. Get Group Balances (should suggest settlement)
        resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/groups/$group_id/balances" \
            -H "Authorization: Bearer $TOKEN")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "GET /upsert/groups/{id}/balances" "200" "$code" "$body"

        # 6. Settle Debt between member and owner
        resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/groups/$group_id/settle?fromUserId=$member_id&toUserId=$USER_ID" \
            -H "Authorization: Bearer $TOKEN")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "POST /upsert/groups/{id}/settle (settle debt)" "200" "$code" "$body"

        # 7. Check Balances again (should show 0/settled suggestions)
        resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/groups/$group_id/balances" \
            -H "Authorization: Bearer $TOKEN")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "GET /upsert/groups/{id}/balances (after settlement)" "200" "$code" "$body"
    fi
}

# ── Analytics ─────────────────────────────────────────────────────────────────

do_analytics() {
    section "ANALYTICS SERVICE"
    [[ -z "${TOKEN:-}" ]] && { echo -e "  ${YELLOW}⚠ No token — run auth first${NC}"; return; }

    for endpoint in "category-pie-chart" "timeline-chart" "comprehensive"; do
        resp=$(curl -s -w "\n%{http_code}" "$GW/api/analytics/$endpoint?userId=$USER_ID" \
            -H "Authorization: Bearer $TOKEN")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "GET /analytics/$endpoint" "200" "$code" "$body"
    done
}

# ── Redis Caching ─────────────────────────────────────────────────────────────

do_caching_tests() {
    section "REDIS CACHING & INVALIDATION SCENARIOS"
    [[ -z "${TOKEN:-}" ]] && { echo -e "  ${YELLOW}⚠ No token — run auth first${NC}"; return; }

    # Ensure Redis starts clean of our cache namespace
    docker exec redis redis-cli -a "${REDIS_PASSWORD:-}" flushall > /dev/null 2>&1 || true

    # 1. Warm up cache by performing comprehensive analytics query
    echo -e "  Warming up cache via GET /analytics/comprehensive..."
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/analytics/comprehensive?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /analytics/comprehensive" "200" "$code" "$body"

    # Verify keys are populated in Redis
    local keys_after_warmup=$(docker exec redis redis-cli keys "*analytics::*" | tr -d '\r' | grep -v '^$' | wc -l | xargs)
    if [[ "$keys_after_warmup" -gt 0 ]]; then
        echo -e "  ${GREEN}✔${NC} Cache populated successfully (keys count=$keys_after_warmup)"
        PASS=$((PASS+1))
    else
        echo -e "  ${RED}✗${NC} Cache population failed (keys count=0)"
        FAIL=$((FAIL+1))
    fi

    # 2. Test Eviction on Create Transaction
    echo -e "  Testing eviction on Create Transaction..."
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/create" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"userId\":\"$USER_ID\",\"name\":\"Salary Cache Test\",\"amount\":5000,\"type\":\"INCOME\",\"incomeCategory\":\"SALARY\",\"currency\":\"INR\",\"description\":\"Cache test\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /create (for cache eviction check)" "201" "$code" "$body"
    local created_id=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null || echo "")

    # Verify keys are evicted from Redis
    local keys_after_create=$(docker exec redis redis-cli keys "*analytics::*" | tr -d '\r' | grep -v '^$' | wc -l | xargs)
    if [[ "$keys_after_create" -eq 0 ]]; then
        echo -e "  ${GREEN}✔${NC} Cache evicted successfully after Create Transaction (keys count=0)"
        PASS=$((PASS+1))
    else
        echo -e "  ${RED}✗${NC} Cache eviction failed after Create Transaction (keys count=$keys_after_create)"
        FAIL=$((FAIL+1))
    fi

    # 3. Warm up cache again
    echo -e "  Warming up cache again..."
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/analytics/comprehensive?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /analytics/comprehensive (rewarm)" "200" "$code" "$body"

    # 4. Test Eviction on Update Transaction
    if [[ -n "$created_id" ]]; then
        echo -e "  Testing eviction on Update Transaction..."
        resp=$(curl -s -w "\n%{http_code}" -X PUT "$GW/api/upsert/update" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TOKEN" \
            -d "{\"id\":$created_id,\"userId\":\"$USER_ID\",\"name\":\"Salary Cache Test Updated\",\"amount\":6000,\"type\":\"INCOME\",\"incomeCategory\":\"SALARY\",\"currency\":\"INR\",\"description\":\"Cache test updated\"}")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "PUT /update (for cache eviction check)" "200" "$code" "$body"

        # Verify keys are evicted from Redis
        local keys_after_update=$(docker exec redis redis-cli keys "*analytics::*" | tr -d '\r' | grep -v '^$' | wc -l | xargs)
        if [[ "$keys_after_update" -eq 0 ]]; then
            echo -e "  ${GREEN}✔${NC} Cache evicted successfully after Update Transaction (keys count=0)"
            PASS=$((PASS+1))
        else
            echo -e "  ${RED}✗${NC} Cache eviction failed after Update Transaction (keys count=$keys_after_update)"
            FAIL=$((FAIL+1))
        fi
    fi

    # 5. Warm up cache again
    echo -e "  Warming up cache again..."
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/analytics/comprehensive?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /analytics/comprehensive (rewarm 2)" "200" "$code" "$body"

    # 6. Test Eviction on Patch Transaction
    if [[ -n "$created_id" ]]; then
        echo -e "  Testing eviction on Patch Transaction..."
        resp=$(curl -s -w "\n%{http_code}" -X PATCH "$GW/api/upsert/entries/$created_id/amount?userId=$USER_ID" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer $TOKEN" \
            -d '{"amount":7000}')
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "PATCH /entries/{id}/amount (for cache eviction check)" "200" "$code" "$body"

        # Verify keys are evicted from Redis
        local keys_after_patch=$(docker exec redis redis-cli keys "*analytics::*" | tr -d '\r' | grep -v '^$' | wc -l | xargs)
        if [[ "$keys_after_patch" -eq 0 ]]; then
            echo -e "  ${GREEN}✔${NC} Cache evicted successfully after Patch Transaction (keys count=0)"
            PASS=$((PASS+1))
        else
            echo -e "  ${RED}✗${NC} Cache eviction failed after Patch Transaction (keys count=$keys_after_patch)"
            FAIL=$((FAIL+1))
        fi
    fi

    # 7. Warm up cache again
    echo -e "  Warming up cache again..."
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/analytics/comprehensive?userId=$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /analytics/comprehensive (rewarm 3)" "200" "$code" "$body"

    # 8. Test Eviction on Delete Transaction
    if [[ -n "$created_id" ]]; then
        echo -e "  Testing eviction on Delete Transaction..."
        resp=$(curl -s -w "\n%{http_code}" -X DELETE "$GW/api/upsert/delete/$created_id?userId=$USER_ID" \
            -H "Authorization: Bearer $TOKEN")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        check "DELETE /delete/{id} (for cache eviction check)" "200" "$code" "$body"

        # Verify keys are evicted from Redis
        local keys_after_delete=$(docker exec redis redis-cli keys "*analytics::*" | tr -d '\r' | grep -v '^$' | wc -l | xargs)
        if [[ "$keys_after_delete" -eq 0 ]]; then
            echo -e "  ${GREEN}✔${NC} Cache evicted successfully after Delete Transaction (keys count=0)"
            PASS=$((PASS+1))
        else
            echo -e "  ${RED}✗${NC} Cache eviction failed after Delete Transaction (keys count=$keys_after_delete)"
            FAIL=$((FAIL+1))
        fi
    fi
}

# ── Security & BOLA Checks ───────────────────────────────────────────────────

do_security_tests() {
    section "SECURITY AUDIT & BOLA HARDENING SCENARIOS"
    [[ -z "${TOKEN:-}" ]] && { echo -e "  ${YELLOW}⚠ No token — run auth first${NC}"; return; }

    # Random fake UUID for testing unauthorized access
    local fake_user="d3b07384-d113-49cd-a5d6-8ee3dc54c000"

    echo -e "  Testing BOLA: Accessing different user's analytics via query param..."
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/analytics/comprehensive?userId=$fake_user" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /analytics/comprehensive?userId=fake_user (expect 403)" "403" "$code" "$body"

    echo -e "  Testing BOLA: Accessing different user's transactions via query param..."
    resp=$(curl -s -w "\n%{http_code}" "$GW/api/upsert/entries?userId=$fake_user" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "GET /upsert/entries?userId=fake_user (expect 403)" "403" "$code" "$body"

    echo -e "  Testing BOLA: Mutating transaction (Create) for different user..."
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/upsert/create" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"userId\":\"$fake_user\",\"name\":\"BOLA Hack\",\"amount\":9999,\"type\":\"EXPENSE\",\"expenseCategory\":\"BILLS_AND_UTILITIES\",\"currency\":\"USD\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /create with different user ID in body (expect 403)" "403" "$code" "$body"

    echo -e "  Testing BOLA: Custom analytics with different user ID in body..."
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/analytics/custom-analytics" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $TOKEN" \
        -d "{\"userId\":\"$fake_user\",\"timelineType\":\"MONTHLY\"}")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /analytics/custom-analytics with different user ID in body (expect 403)" "403" "$code" "$body"


}

# ── OCR Parser ────────────────────────────────────────────────────────────────

do_ocr() {
    section "OCR PARSER SERVICE"
    [[ -z "${TOKEN:-}" ]] && { echo -e "  ${YELLOW}⚠ No token — run auth first${NC}"; return; }

    # 1. Process Receipt PDF
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/bill/process/$USER_ID" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@requests/ocr-parser/Receipt.pdf")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /bill/process (Receipt.pdf)" "200" "$code" "$body"

    # 2. Process ParkingLot PDF
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/bill/process/$USER_ID" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@requests/ocr-parser/ParkingLot.pdf")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /bill/process (ParkingLot.pdf)" "200" "$code" "$body"

    # 3. Process image PNG
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/bill/process/$USER_ID" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@requests/ocr-parser/image.png")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /bill/process (image.png)" "200" "$code" "$body"

    # 4. Validation (missing file param)
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/bill/process/$USER_ID" \
        -H "Authorization: Bearer $TOKEN")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /bill/process (missing file → 400)" "400" "$code" "$body"

    # 5. Unauthenticated (no token)
    resp=$(curl -s -w "\n%{http_code}" -X POST "$GW/api/bill/process/$USER_ID" \
        -F "file=@requests/ocr-parser/Receipt.pdf")
    body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
    check "POST /bill/process (no token → 401)" "401" "$code" "$body"
}

# ── Health checks ─────────────────────────────────────────────────────────────

do_health() {
    section "HEALTH CHECKS"
    for svc_port in "api-gateway:8080" "auth-service:8082" "upsert-service:8081" "analytics-service:8084" "ocr-parser-service:8083"; do
        svc="${svc_port%%:*}"; port="${svc_port##*:}"
        resp=$(curl -s -w "\n%{http_code}" "http://localhost:$port/actuator/health")
        body=$(echo "$resp" | head -1); code=$(echo "$resp" | tail -1)
        status=$(echo "$body" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
        check "$svc /actuator/health ($status)" "200" "$code" "$body"
    done
}

# ── DB direct checks ──────────────────────────────────────────────────────────

do_db() {
    section "DATABASE CHECKS"

    echo -e "\n${YELLOW}Postgres:${NC}"
    docker exec postgres-db psql -U finance_user -d finance_assistant \
        -c "SELECT schemaname, relname AS tablename, n_live_tup AS rows FROM pg_stat_user_tables ORDER BY schemaname,tablename" 2>&1 | grep -v "^$"

    echo -e "\n${YELLOW}Redis:${NC}"
    redis_info=$(docker exec redis redis-cli INFO server 2>/dev/null | grep -E "redis_version|uptime_in_seconds|connected_clients" || echo "not reachable")
    echo "  $redis_info"
    keys=$(docker exec redis redis-cli DBSIZE 2>/dev/null || echo "?")
    echo -e "  Keys in DB: ${GREEN}$keys${NC}"

    echo -e "\n${YELLOW}ClickHouse:${NC}"
    docker exec clickhouse clickhouse-client --user default --password clickhouse \
        --query "SELECT database, name, engine, total_rows, formatReadableSize(total_bytes) AS size FROM system.tables WHERE database NOT IN ('system','information_schema','INFORMATION_SCHEMA') ORDER BY database,name" 2>&1
}

# ── Main ──────────────────────────────────────────────────────────────────────

MODE="${1:-all}"

case "$MODE" in
    clean)
        echo "Cleaning databases..."
        docker exec postgres-db psql -U finance_user -d finance_assistant -c "
            SET search_path TO finance;
            TRUNCATE TABLE transaction_entries CASCADE;
            TRUNCATE TABLE expense_splits CASCADE;
            TRUNCATE TABLE shared_expenses CASCADE;
            TRUNCATE TABLE group_members CASCADE;
            TRUNCATE TABLE expense_groups CASCADE;
            TRUNCATE TABLE idempotency_records CASCADE;
        " > /dev/null 2>&1
        docker exec redis redis-cli flushall > /dev/null 2>&1 || true
        for tbl in container_logs error_events hourly_log_counts service_metrics; do
            docker exec clickhouse clickhouse-client --user default --password clickhouse -q "TRUNCATE TABLE IF EXISTS observability_logs.$tbl" > /dev/null 2>&1 || true
        done
        echo "Databases cleaned successfully."
        exit 0
        ;;
    health)    do_health ;;
    auth)      do_auth ;;
    upsert)    do_auth; do_upsert; do_group_split_tests ;;
    ocr)       do_auth; do_ocr ;;
    analytics) do_auth; do_upsert; do_analytics ;;
    cache)     do_auth; do_caching_tests ;;
    security)  do_auth; do_security_tests ;;
    db)        do_db ;;
    all)
        do_health
        do_auth
        do_upsert
        do_group_split_tests
        do_ocr
        do_analytics
        do_caching_tests
        do_security_tests
        do_db
        ;;
    *)
        echo "Usage: $0 [all|health|auth|upsert|ocr|analytics|cache|security|db|clean]"
        exit 1
        ;;
esac

# ── Summary ───────────────────────────────────────────────────────────────────

if [[ "$MODE" != "db" ]]; then
    echo -e "\n${CYAN}───────────────────────────────${NC}"
    TOTAL=$(( PASS + FAIL ))
    if [[ $FAIL -eq 0 ]]; then
        echo -e "  ${GREEN}✔ All $TOTAL checks passed${NC}"
    else
        echo -e "  ${GREEN}✔ $PASS passed${NC}  ${RED}✗ $FAIL failed${NC}  (total $TOTAL)"
        exit 1
    fi
fi
