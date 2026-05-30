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
    health)    do_health ;;
    auth)      do_auth ;;
    upsert)    do_auth; do_upsert ;;
    ocr)       do_auth; do_ocr ;;
    analytics) do_auth; do_upsert; do_analytics ;;
    db)        do_db ;;
    all)
        do_health
        do_auth
        do_upsert
        do_ocr
        do_analytics
        do_db
        ;;
    *)
        echo "Usage: $0 [all|health|auth|upsert|ocr|analytics|db]"
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
