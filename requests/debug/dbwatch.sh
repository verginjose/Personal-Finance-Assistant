#!/bin/bash
# Watch a row predicate on a postgres pod every 50 ms for N seconds.
#
# Used to measure read-replica visibility: when does a just-inserted
# shared_expenses row become visible on the replica? (Observed: 11 ms in one
# run, ~250 ms in another.)
#
# Usage: requests/debug/dbwatch.sh <pod> <out> [seconds] [group_id] [description]
set -u
pod="$1"; out="$2"; secs="${3:-30}"; gid="${4:-102}"; desc="${5:-probe canary%}"
timeout "$secs" kubectl -n pfa exec -i "$pod" -c postgres -- \
  psql -U pfa_admin -d pfa_db -At > "$out" 2>&1 <<SQL
select to_char(clock_timestamp(),'HH24:MI:SS.MS')||'|exp='||count(*)||'|split='||coalesce((
  select count(*) from groups.expense_splits s
  join groups.shared_expenses e on e.id = s.shared_expense_id
  where e.group_id = $gid and e.description like '$desc'),0)
from groups.shared_expenses where group_id = $gid and description like '$desc';
\\watch 0.05
SQL
