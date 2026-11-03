# Debug tooling — group-balances cache canary

Scripts used on 2026-11-02/03 to investigate the golden test's group-balances
canary failing intermittently, plus the queries used to read the cluster.

## Prerequisites

```bash
export KUBECONFIG="$HOME/.kube/k3s.yaml"
kubectl port-forward -n pfa svc/api-gateway 18090:8080 &
```

Credentials come from the test's CSV (`requests/golden-users*.csv`):
`email,username,password,userId,group,name`. The password default is
`GoldenTest!`.

## Scripts

| Script | What it does |
|---|---|
| `probe_canary.py` | Logs in as one member, warms balances, posts a 2016 canary expense, samples the Redis cache key/tracking set, polls until balances change, deletes the canary, polls until restored. Prints `POST_EPOCH`/`CANARY_ID` markers for correlation. `--repeat N` loops. |
| `probe_diag.py` | Same canary, but before each API poll it checks whether the row is on the replica (`sql_before=`) and prints the API deltas plus Redis `EXISTS`/`TTL`. This is the script that isolated the current bug. |
| `analyze_trace.py` | Merges the golden test's `GOLDEN_TRACE` markers with a Redis `MONITOR` capture per group. |
| `redis_monitor.sh` | Captures `MONITOR` from `redis-0` into a file. |
| `watch_pg.sh` | Watches `pg_stat_activity` on a postgres pod for a query pattern (`\watch 0.2`). Sampling misses sub-ms statements; validate with `select pg_last_wal_replay_lsn(), pg_sleep(3)`. |
| `dbwatch.sh` | Watches a `shared_expenses` row + its split count every 50 ms — measures read-replica visibility. |

### Typical session

```bash
# 1. one group, canary race, five attempts
python3 requests/debug/probe_canary.py --group 88 --email mihir@example.com --repeat 5

# 2. instrumented: which cache state does each poll see?
python3 requests/debug/probe_diag.py --group 102 --email mihir@example.com

# 3. end-to-end: golden test with trace markers + Redis monitor
GOLDEN_TRACE=/tmp/golden-trace.log \
  python3 requests/run_golden_test.py --users 12 --groups 2 --bills 2 --think-time 0.2 \
    --base-url http://localhost:18090 --out /tmp/report.json &
requests/debug/redis_monitor.sh /tmp/redis-monitor.log &
# ... wait for the run, then stop the monitor ...
python3 requests/debug/analyze_trace.py /tmp/golden-trace.log /tmp/redis-monitor.log

# 4. replica visibility of a fresh row
requests/debug/dbwatch.sh postgres-replica-0 /tmp/vis.log 30 102 'probe canary%' &
python3 requests/debug/probe_canary.py --group 102 --email mihir@example.com
```

`GOLDEN_TRACE` is only honoured when set; the golden test is otherwise unchanged.

## Cluster queries used

```bash
# Query-service logs (warnings/errors) — ClickHouse
kubectl -n pfa exec clickhouse-0 -- clickhouse-client -q \
 "select timestamp, service, level, message from default.logs
  where level in ('WARN','ERROR') order by timestamp desc limit 30"

# Per-endpoint latency means — Prometheus (port-forward 19090:9090)
#   http_server_requests_seconds_sum / _count
#   histogram buckets are NOT exported (no percentiles-histogram), only count/sum/max

# Outbox backlog — Postgres primary
kubectl -n pfa exec postgres-primary-0 -c postgres -- psql -U pfa_admin -d pfa_db -c \
 "select status, count(*), min(created_at) from finance.outbox_events group by 1"

# Replica replay lag: BYTES, not timestamps.
# now()-pg_last_xact_replay_timestamp() is idle time, not lag — it grows on a
# caught-up replica whenever the primary stops writing. Use WAL positions:
#   primary: select pg_current_wal_lsn();
#   replica: select pg_last_wal_replay_lsn();
#   diff:    select pg_wal_lsn_diff('<primary>', '<replica>');

# Redis cache key / tracking set
kubectl -n pfa exec redis-0 -- sh -c \
 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli EXISTS finance:analytics:v1:group-balances:102'
kubectl -n pfa exec redis-0 -- sh -c \
 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli SMEMBERS finance:query:v1:group-keys:102'
```

## What was found (2026-11-03)

Measured on group 102, `probe_diag.py`:

- Eviction is correct: the canary POST deletes
  `finance:analytics:v1:group-balances:<id>` and the tracking set ~8 ms before
  the HTTP response returns.
- The next balance read (miss) refills the key **16 ms after the write**, and
  the cached payload is the **pre-canary baseline**.
- In other runs, that key then keeps the stale value for its full **600 s TTL**,
  so the golden test's 45 s poll never sees the change. That is the canary
  failure.
- The row is on the replica when this happens: `probe_diag.py` prints
  `sql_before=1` while the API returns zero deltas and the key reappears with
  TTL 600.
- Replica visibility of a fresh `shared_expenses` row measured between **11 ms**
  and **~250 ms** (`dbwatch.sh`). Replica byte lag stays in the tens of KB.

Fix attempted (committed `33afc25`, deployed as image `192552d54053`):
`CacheConsistencyGuard` now (a) waits up to 250 ms for the replica to replay the
write before a fill loads, and (b) fails closed when the gen/WAL tokens are
missing. **The canary still fails intermittently after this change**, so the
outstanding question is why the guard certifies a fill whose loaded value does
not contain the already-replicated row — the next thing to prove is whether the
guard's `pg_last_wal_replay_lsn()` check actually runs on the replica (all
query-service connections are on `postgres-replica-0`; `10.42.0.227` showed 5
connections there and none on the primary).

Related fixes shipped the same day: `--realistic` golden-test mode (`361a4e3`),
auth CPU limit 4 + Lettuce pool 200 (`714415d`), and `GOLDEN_TRACE` markers
(`180cfa6`/`192552d`).
