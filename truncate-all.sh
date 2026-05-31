#!/bin/bash
# truncate-all.sh — wipe all data in ClickHouse + Postgres (keeps users/schema)
set -e
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'

echo -e "${YELLOW}⚠  This will DELETE all financial and log data. Schema and users are preserved.${NC}"
read -p "Are you sure? (yes/no): " CONFIRM
[[ "$CONFIRM" != "yes" ]] && echo "Aborted." && exit 0

echo -e "\n${YELLOW}── ClickHouse ──${NC}"
for tbl in container_logs error_events hourly_log_counts service_metrics; do
  docker exec clickhouse clickhouse-client --user default --password clickhouse \
    -q "TRUNCATE TABLE IF EXISTS observability_logs.$tbl" 2>&1 && \
    echo -e "  ${GREEN}✓${NC} observability_logs.$tbl"
done

echo -e "\n${YELLOW}── Postgres ──${NC}"
docker exec postgres-db psql -U finance_user -d finance_assistant << 'PSQL'
SET search_path TO finance;
TRUNCATE TABLE transaction_entries CASCADE;
TRUNCATE TABLE expense_splits       CASCADE;
TRUNCATE TABLE shared_expenses      CASCADE;
TRUNCATE TABLE group_members       CASCADE;
TRUNCATE TABLE expense_groups       CASCADE;
TRUNCATE TABLE idempotency_records  CASCADE;
SELECT 'finance schema truncated' AS status;
PSQL

echo -e "\n${GREEN}✅ All data truncated successfully.${NC}"
