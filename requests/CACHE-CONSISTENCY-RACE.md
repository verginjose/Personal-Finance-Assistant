# Worked example: the archive write that raced its own cache fill

Found 2026-11-03 on the live cluster, fixed the same day. Group 85
("Office lunch crew") stayed archived in the UI after an `archive=false`
returned `204`, while the DB row said `is_archived = false`.

This follows the same format as
[GROUP-BALANCES-STALE-CACHE.md](GROUP-BALANCES-STALE-CACHE.md): the two
earlier fixes are `39ff430` and `aa33819`, the race below is `6affd31`, and
the removal of the remaining HTTP eviction path is `6565cef`.

## The pieces

Two services, one Redis, one Postgres with a streaming replica:

| service | cache namespace | reads from |
|---|---|---|
| command-service | `finance:upsert:v1:*` | primary |
| query-service | `finance:analytics:v1:*` | **replica** |

`GET /api/upsert/groups` is `@Cacheable(value = "user-groups", key = "#userId", sync = true)`
with the default TTL of 600 s.

For every scope there are two write tokens in Redis, published by
command-service **in `afterCommit`**:

```
finance:gen:<scope>   integer, INCR per committed write     (scope = user:<uuid> | group:<id>)
finance:wal:<scope>   pg_current_wal_lsn() of that write
```

The read side (`TrackingRedisCache` + `CacheConsistencyGuard`) does:

1. **snapshot** before loading: read `gen` and `wal`; ask the replica
   `pg_last_wal_replay_lsn() >= wal`.
2. **load** from the replica.
3. **canCache** after loading: re-read the tokens; require `gen` unchanged and
   the snapshot's `replicaCaughtUp` to be true.
4. **put** the value in Redis, then register the key in
   `finance:analytics:v1:user-keys:<uuid>`.

Steps 3 and 4 are **separate Redis round-trips**. That gap is the defect.

## The cast

User `31e0f72a-…` (the only actor), group 85.

## Timeline (2026-11-03)

Observed lines:

```
11:04:30.820  query-service: "Evicted 1 keys for user=31e0f72a-…"
11:04:30.82x  finance:analytics:v1:user-groups:31e0f72a-… written, isArchived=true, TTL 600 s
11:04:30.836  frontend/nginx: PUT /api/upsert/groups/85/archive?archive=false → 204
11:04:30.886  frontend/nginx: GET /api/upsert/groups?userId=31e0f72a-… → cache hit
11:14:30      key expires (10-minute TTL)
```

The ordering that produces all of that:

| # | Actor | Action | State |
|---|---|---|---|
| 1 | reader R | `GET …/groups` → cache miss (the previous write at `:26.8` evicted it) | — |
| 2 | reader R | takes `snapshot`: `gen=87`, `wal=W_old`, replica caught up for `W_old` | — |
| 3 | writer W | `BEGIN; UPDATE group_members SET is_archived=false …` — **uncommitted** | replica still has `true` |
| 4 | reader R | `SELECT`s from the replica → `isArchived=true` (pre-commit, correct for the old world) | — |
| 5 | writer W | calls `publishForUsers(...)`; it is `@Async`, so the HTTP evict is dispatched **now** | — |
| 6 | thread A | arrives at query-service; `CacheKeyRegistry.evictForUser` DELs the key **and the tracking set** — the `:30.820` log line | key absent, registry set absent |
| 7 | reader R | `canCache`: re-reads `gen` → still **87** (the bump is in `afterCommit`, not yet run) → **true** | — |
| 8 | reader R | `super.put(stale)` → `isArchived=true`, TTL 600 s; then registers the key | **stale value present** |
| 9 | writer W | `COMMIT` → row is `false` on the primary | — |
| 10 | writer W | `afterCommit`: `gen 87→88`, `wal=0/3D6E8118`; `doEvictGroup` deletes the tracked keys — but the key was written after the delete, or the registry set was already gone | key survives |
| 11 | reader R' | browser refreshes → **cache hit**, serves `isArchived=true` | stale for 600 s |

## The three misses

**1. The generation check (`canCache`).** It is a time-of-check / time-of-use
problem: the check was correct at `:30.82` (gen still 87) and the commit
happened at `:30.83`, before the `put` at `:30.84`. `canCache` and `put` are
different Redis commands; nothing links them.

**2. The post-commit delete (`doEvictGroup`).** It has the right timing but
two problems: (a) it ran before R's `put`, and a delete cannot remove a value
written afterwards; (b) `evictForUser` at step 6 had already deleted the
tracking set (`finance:analytics:v1:user-keys:<uuid>`), so the registry-driven
delete fell back to the hardcoded key — which was also absent at that instant.

**3. The pre-commit eviction (`CacheEvictPublisher`, HTTP).** It emptied the
cache ~16 ms before the commit — while the old value was still the truth.
It also deleted the tracking set, and being `@Async` its position in time was
undefined. The open hole it created is exactly what R filled.

**Implied 4th miss:** cache **hits are never guarded**. The `:30.886` GET was
a hit and served the stale value without consulting the guard.

## The fixes

### `6affd31` — close the check→put window

- `TrackingRedisCache.get(key, loader)` re-reads the generation **after**
  `super.put`; if it moved, it evicts the value it just wrote.
- `CacheConsistencyGuard.generationUnchanged(scope, before)` implements the
  check and fails closed.
- `archiveGroup()` moved its fan-out eviction to `afterCommit`, registered
  after the write-token callbacks, so the delete lands behind the bump.

This makes the sequence `check → put → check → undo if needed`, which is safe
even though the three commands are not atomic. A Lua script doing
`GET gen; SET key; if changed DEL key` would make it truly atomic; not needed.

### `6565cef` — one eviction path instead of two

Both services share Redis and the same user-key registry, so the
`/internal/cache-evict` HTTP hop was redundant. It was also the unordered
half of the race.

- `SplitService` evicts user caches in `afterCommit` through
  `CacheKeyRegistry.evictForUser` (command side).
- `OutboxProcessor` retries the eviction directly in Redis;
  `evictForUser` now returns `boolean` so an event is only marked `PROCESSED`
  when the delete actually ran.
- Deleted: both `CacheEvictPublisher` classes and
  `query-service/.../InternalCacheController`.

The invalidation contract is now: **commit → bump gen/wal → delete directly**.

## Files touched

- `query-service/.../cache/TrackingRedisCache.java` — post-put generation
  re-check.
- `query-service/.../cache/CacheConsistencyGuard.java` —
  `generationUnchanged`.
- `command-service/.../service/SplitService.java` — after-commit evictions.
- `command-service/.../service/OutboxProcessor.java` — direct Redis retry.
- `command-service/.../cache/CacheKeyRegistry.java` — `evictForUser` returns
  `boolean`.
- `query-service/src/test/.../CacheConsistencyGuardTest.java` — three tests
  for the post-put check.

## Re-checking it later

```bash
# the value the API serves, and its TTL
kubectl -n pfa exec redis-0 -- sh -c \
  'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning TTL finance:analytics:v1:user-groups:<uuid>'
kubectl -n pfa exec redis-0 -- sh -c \
  'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning GET finance:analytics:v1:user-groups:<uuid>'

# the write tokens
kubectl -n pfa exec redis-0 -- sh -c \
  'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning MGET finance:gen:user:<uuid> finance:wal:user:<uuid>'

# the truth
kubectl -n pfa exec postgres-primary-0 -- bash -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
   "select group_id, user_id, is_archived from groups.group_members where group_id = 85"'
```

If a stale value appears again, check in this order: (1) did the key's TTL
start at the write? (2) did `gen` change at commit? (3) is the key in the
user-keys set? (4) was the read a hit (hits are unguarded by design)?
