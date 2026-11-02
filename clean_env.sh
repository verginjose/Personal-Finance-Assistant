#!/usr/bin/env bash
#
# Clean load-test data out of Postgres and/or flush the Redis cache.
#
# Safe by default: prints what would be deleted and changes nothing until
# --yes is passed. The seed table finance.financial_rules is never touched.
#
#   ./clean_env.sh --scenario --dry-run   # default: show the plan
#   ./clean_env.sh --scenario --yes       # delete s50_*/lt* test users + their data
#   ./clean_env.sh --all --dry-run        # show what a full wipe would remove
#   ./clean_env.sh --all --yes --i-mean-all   # TRUNCATE every app table (keeps rules)
#   ./clean_env.sh --redis-only --yes     # FLUSHALL (caches + refresh tokens: logs users out)
#   ./clean_env.sh --scenario --yes --redis-only
#
# Scenario mode removes only users whose email/username starts with "s50_" or
# "lt" and groups named "Scenario Group *"; real accounts are left alone.
# All mode removes EVERY user, transaction and group — including real ones.
# Redis FLUSHALL also drops refresh_token:* keys, so every session is logged
# out (access tokens stay valid until they expire).
#
set -euo pipefail

NAMESPACE=${NAMESPACE:-pfa}
PG_POD=${PG_POD:-postgres-primary-0}
REDIS_POD=${REDIS_POD:-redis-0}

MODE=""
DO_REDIS=0
REDIS_ONLY=0
APPLY=0
ALLOW_ALL=0

usage() {
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        --scenario)   MODE=scenario ;;
        --all)        MODE=all ;;
        --redis-only) DO_REDIS=1; REDIS_ONLY=1 ;;
        --redis)      DO_REDIS=1 ;;
        --yes|-y)     APPLY=1 ;;
        --i-mean-all) ALLOW_ALL=1 ;;
        --dry-run)    APPLY=0 ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; usage >&2; exit 2 ;;
    esac
done
if [ "$REDIS_ONLY" -eq 0 ]; then
    MODE=${MODE:-scenario}
else
    MODE=${MODE:-redis}
fi

psql_exec() {
    # $1 = SQL. Runs inside the primary pod with the pod's own DB_USER/DB_NAME,
    # so no credentials are read or printed here.
    printf '%s\n' "$1" |
        kubectl -n "$NAMESPACE" exec -i "$PG_POD" -- \
            sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -X -q -f -'
}

TEST_USERS_CTE="
    WITH test_users AS (
        SELECT id FROM auth.users
        WHERE email ~ '^(s50_|lt)' OR username ~ '^(s50_|lt)'
    ),
    test_groups AS (
        SELECT id FROM groups.expense_groups
        WHERE name LIKE 'Scenario Group %'
           OR created_by IN (SELECT id FROM test_users)
    )
"

scenario_plan_sql() {
    cat <<SQL
${TEST_USERS_CTE}
SELECT 'auth.users' AS table_name, count(*) AS rows_to_delete FROM test_users
UNION ALL SELECT 'groups.expense_groups', count(*) FROM test_groups
UNION ALL SELECT 'groups.group_members', count(*) FROM groups.group_members
    WHERE group_id IN (SELECT id FROM test_groups) OR user_id IN (SELECT id FROM test_users)
UNION ALL SELECT 'groups.shared_expenses', count(*) FROM groups.shared_expenses
    WHERE group_id IN (SELECT id FROM test_groups) OR paid_by IN (SELECT id FROM test_users)
UNION ALL SELECT 'groups.expense_splits', count(*) FROM groups.expense_splits
    WHERE user_id IN (SELECT id FROM test_users)
       OR shared_expense_id IN (SELECT id FROM groups.shared_expenses
              WHERE group_id IN (SELECT id FROM test_groups) OR paid_by IN (SELECT id FROM test_users))
UNION ALL SELECT 'finance.transaction_entries', count(*) FROM finance.transaction_entries
    WHERE user_id IN (SELECT id FROM test_users)
UNION ALL SELECT 'finance.category_budgets', count(*) FROM finance.category_budgets
    WHERE user_id IN (SELECT id FROM test_users)
UNION ALL SELECT 'finance.savings_goals', count(*) FROM finance.savings_goals
    WHERE user_id IN (SELECT id FROM test_users)
UNION ALL SELECT 'finance.subscriptions', count(*) FROM finance.subscriptions
    WHERE user_id IN (SELECT id FROM test_users)
UNION ALL SELECT 'activity.group_activities', count(*) FROM activity.group_activities
    WHERE group_id IN (SELECT id FROM test_groups) OR actor_user_id IN (SELECT id FROM test_users)
ORDER BY table_name;
SQL
}

scenario_delete_sql() {
    cat <<SQL
BEGIN;
${TEST_USERS_CTE}
DELETE FROM finance.transaction_goal_allocations
 WHERE transaction_id IN (SELECT id FROM finance.transaction_entries WHERE user_id IN (SELECT id FROM test_users))
    OR goal_id IN (SELECT id FROM finance.savings_goals WHERE user_id IN (SELECT id FROM test_users));

DELETE FROM groups.expense_transaction_links
 WHERE user_id IN (SELECT id FROM test_users)
    OR shared_expense_id IN (SELECT id FROM groups.shared_expenses
           WHERE group_id IN (SELECT id FROM test_groups) OR paid_by IN (SELECT id FROM test_users));

DELETE FROM groups.expense_splits
 WHERE user_id IN (SELECT id FROM test_users)
    OR shared_expense_id IN (SELECT id FROM groups.shared_expenses
           WHERE group_id IN (SELECT id FROM test_groups) OR paid_by IN (SELECT id FROM test_users));

DELETE FROM groups.shared_expenses
 WHERE group_id IN (SELECT id FROM test_groups) OR paid_by IN (SELECT id FROM test_users);

DELETE FROM groups.group_members
 WHERE group_id IN (SELECT id FROM test_groups) OR user_id IN (SELECT id FROM test_users);

DELETE FROM activity.group_activities
 WHERE group_id IN (SELECT id FROM test_groups) OR actor_user_id IN (SELECT id FROM test_users);

DELETE FROM groups.expense_groups
 WHERE id IN (SELECT id FROM test_groups);

DELETE FROM finance.transaction_entries WHERE user_id IN (SELECT id FROM test_users);
DELETE FROM finance.savings_goals        WHERE user_id IN (SELECT id FROM test_users);
DELETE FROM finance.category_budgets     WHERE user_id IN (SELECT id FROM test_users);
DELETE FROM finance.subscriptions        WHERE user_id IN (SELECT id FROM test_users);
DELETE FROM finance.idempotency_records  WHERE user_id IN (SELECT id FROM test_users);
DELETE FROM finance.outbox_events        WHERE user_id IN (SELECT id FROM test_users);
DELETE FROM auth.users                   WHERE id IN (SELECT id FROM test_users);
COMMIT;
SQL
}

all_plan_sql() {
    cat <<'SQL'
SELECT 'auth.users' AS table_name, count(*) AS rows_to_delete FROM auth.users
UNION ALL SELECT 'finance.transaction_entries', count(*) FROM finance.transaction_entries
UNION ALL SELECT 'finance.savings_goals', count(*) FROM finance.savings_goals
UNION ALL SELECT 'finance.category_budgets', count(*) FROM finance.category_budgets
UNION ALL SELECT 'finance.subscriptions', count(*) FROM finance.subscriptions
UNION ALL SELECT 'finance.idempotency_records', count(*) FROM finance.idempotency_records
UNION ALL SELECT 'finance.outbox_events', count(*) FROM finance.outbox_events
UNION ALL SELECT 'groups.expense_groups', count(*) FROM groups.expense_groups
UNION ALL SELECT 'groups.group_members', count(*) FROM groups.group_members
UNION ALL SELECT 'groups.shared_expenses', count(*) FROM groups.shared_expenses
UNION ALL SELECT 'groups.expense_splits', count(*) FROM groups.expense_splits
UNION ALL SELECT 'groups.expense_transaction_links', count(*) FROM groups.expense_transaction_links
UNION ALL SELECT 'activity.group_activities', count(*) FROM activity.group_activities
ORDER BY table_name;
SQL
}

all_delete_sql() {
    cat <<'SQL'
BEGIN;
TRUNCATE activity.group_activities,
         groups.expense_transaction_links,
         groups.expense_splits,
         groups.shared_expenses,
         groups.group_members,
         groups.expense_groups,
         finance.transaction_goal_allocations,
         finance.transaction_entries,
         finance.savings_goals,
         finance.category_budgets,
         finance.subscriptions,
         finance.idempotency_records,
         finance.outbox_events,
         auth.users
RESTART IDENTITY CASCADE;
COMMIT;
SQL
}

flush_redis() {
    echo "Flushing Redis on ${REDIS_POD} (caches + refresh tokens) ..."
    kubectl -n "$NAMESPACE" exec "$REDIS_POD" -- \
        sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHALL'
}

if [ "$MODE" != "redis" ]; then
    if [ "$MODE" = "all" ]; then
        PLAN_SQL=$(all_plan_sql)
        DELETE_SQL=$(all_delete_sql)
        echo "Mode: ALL — deletes every user, transaction and group (financial_rules kept)."
    else
        PLAN_SQL=$(scenario_plan_sql)
        DELETE_SQL=$(scenario_delete_sql)
        echo "Mode: scenario — deletes s50_*/lt* users and 'Scenario Group *' groups only."
    fi

    echo
    echo "Planned deletions:"
    psql_exec "$PLAN_SQL"
    echo

    if [ "$MODE" = "all" ] && [ "$APPLY" -eq 1 ] && [ "$ALLOW_ALL" -ne 1 ]; then
        echo "!!! --all deletes every user, including REAL accounts." >&2
        echo "!!! Re-run with: --all --yes --i-mean-all" >&2
        exit 1
    fi

    if [ "$APPLY" -eq 1 ]; then
        [ "$MODE" = "all" ] && echo "Wiping all application data ..."
        psql_exec "$DELETE_SQL"
        echo "Postgres cleaned."
    else
        echo "Dry run — nothing changed. Add --yes to apply."
    fi
fi

if [ "$DO_REDIS" -eq 1 ]; then
    if [ "$APPLY" -eq 1 ]; then
        flush_redis
    else
        echo "Would run FLUSHALL on Redis ${REDIS_POD} — this also deletes refresh"
        echo "tokens, so every user is logged out (add --yes to apply)."
    fi
fi
