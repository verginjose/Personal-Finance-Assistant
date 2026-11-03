#!/bin/bash
# Watch pg_stat_activity for a query pattern on a postgres pod for N seconds.
#
# Useful to prove *which* database runs a piece of SQL (e.g. the consistency
# guard's replica check). Sampling cannot catch sub-millisecond statements
# reliably; use a long-lived statement or pg_sleep to validate the watcher.
#
# Usage: requests/debug/watch_pg.sh <pod> <out> [seconds] [pattern]
set -u
pod="$1"; out="$2"; secs="${3:-30}"; pattern="${4:-%pg_last_wal_replay_lsn%}"
timeout "$secs" kubectl -n pfa exec -i "$pod" -c postgres -- \
  psql -U pfa_admin -d pfa_db -At > "$out" 2>&1 <<SQL
select clock_timestamp()||'|'||pg_backend_pid()||'|'||coalesce(client_addr::text,'local')||'|'||left(regexp_replace(query,'[[:space:]]+',' ','g'),100)
from pg_stat_activity
where query ilike '$pattern' and pid <> pg_backend_pid() and query not ilike '%pg_stat_activity%';
\\watch 0.2
SQL
