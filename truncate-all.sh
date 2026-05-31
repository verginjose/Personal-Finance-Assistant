#!/bin/bash
# truncate-all.sh — wipe all data in ClickHouse + Postgres (keeps schema)
set -e
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'

echo -e "${YELLOW}⚠  This will DELETE all financial and log data. Schema and users are preserved.${NC}"
read -p "Are you sure? (yes/no): " CONFIRM
[[ "$CONFIRM" != "yes" ]] && echo "Aborted." && exit 0

echo -e "\n${YELLOW}── ClickHouse ──${NC}"
for tbl in container_logs error_events hourly_log_counts service_metrics; do
  docker exec clickhouse clickhouse-client --user default --password clickhouse \
    -q "TRUNCATE TABLE IF EXISTS observability_logs.$tbl" && \
    echo -e "  ${GREEN}✓${NC} observability_logs.$tbl"
done

echo -e "\n${YELLOW}── Postgres: finance schema ──${NC}"
docker exec postgres-db psql -U finance_user -d finance_assistant \
  -c "TRUNCATE TABLE finance.transaction_entries, finance.expense_splits, finance.shared_expenses, finance.group_members, finance.expense_groups, finance.idempotency_records CASCADE;" && \
  echo -e "  ${GREEN}✓${NC} finance schema"

echo -e "\n${YELLOW}── Postgres: auth schema ──${NC}"
docker exec postgres-db psql -U finance_user -d finance_assistant \
  -c "TRUNCATE TABLE auth.users CASCADE;" && \
  echo -e "  ${GREEN}✓${NC} auth schema"

echo -e "\n${YELLOW}── Verifying ──${NC}"
AUTH_COUNT=$(docker exec postgres-db psql -U finance_user -d finance_assistant -t -c "SELECT count(*) FROM auth.users;")
FINANCE_COUNT=$(docker exec postgres-db psql -U finance_user -d finance_assistant -t -c "SELECT count(*) FROM finance.transaction_entries;")
echo -e "  auth.users:                  ${AUTH_COUNT// /} rows"
echo -e "  finance.transaction_entries: ${FINANCE_COUNT// /} rows"

echo -e "\n${GREEN}✅ All data truncated successfully.${NC}"