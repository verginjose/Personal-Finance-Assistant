# Worked example: the group-balances stale cache

This is the defect the golden test's stage 10 reproduced on 2026-11-02, and
the reasoning behind the fix in commit `39ff430`. It is written as a timeline
so the failure mode is easy to re-check later.

## The pieces

`GET /api/upsert/groups/{id}/balances` is cached in Redis:

```
finance:analytics:v1:group-balances:<groupId>     TTL = 600 s (10 min)
```

query-service also records every key it caches in a registry Set, because
command-service evicts by deleting the members of that Set rather than by
scanning:

```
finance:query:v1:group-keys:<groupId>  =  { group-balances:<id>,
                                             group-expenses:<id>-0-200, ... }
```

On a group write, `QueryCacheEvictor.doEvictGroup()`:

1. `SMEMBERS finance:query:v1:group-keys:<groupId>`
2. `DEL` each member
3. `DEL` the registry Set itself
4. publish the write-generation + WAL tokens (after commit)

The cache fill and the eviction are **not atomic** with each other.

## The cast

Group **Flat 402**, `groupId = 50`, three members:

- **Arjun** (A)
- **Priya** (P)
- **Rahul** (R)

The group has no expenses yet, so every net balance is zero.

## Timeline

### T0 — Arjun adds dinner ₹3,000, EQUAL, paid by Arjun

The correct result is `A +2000`, `P -1000`, `R -1000` (each owes ₹1,000).

Priya opens the group at the same moment, so query-service is filling the
cache. The two requests interleave:

| # | Actor | Action | Redis state after |
|---|---|---|---|
| 1 | Arjun's writer | commits the dinner row, then (old code) evicts **inside the transaction**: `SMEMBERS S` → `{}` | `K` absent, `S = {}` |
| 2 | Priya's reader | cache miss → loads balances from the **replica**, which has not replayed the commit yet → sees the pre-dinner state | — |
| 3 | Priya's reader | `PUT K = {A:0, P:0, R:0, debts:[]}` | `K` exists (all zeros) |
| 4 | Priya's reader | `SADD S K` | `S = {K}` |
| 5 | Arjun's writer | finishes its eviction pipeline, built from the empty snapshot in step 1: `DEL <members>` (none) then **`DEL S`** | `K` exists, **`S = {}`** ← the gap |

Two bad things landed together:

- Priya cached the **pre-commit** snapshot, because the write-generation token
  is only published after Arjun's transaction commits, and the fill was
  already in flight.
- The eviction unconditionally deleted the registry Set, wiping the
  registration the reader had just written. The eviction never saw `K`
  (it was created after `SMEMBERS`), so `K` survived.

End state: **a cached value that exists but is not tracked by any registry**.

### T1 — Priya adds a cab ₹600, EQUAL, paid by Priya

Correct result: `A 2000−200 = 1800`, `P −1000+600−200 = −600`,
`R −1000−200 = −1200`.

The write evicts again:

```
SMEMBERS S           → {}          ← nothing to delete
DEL <members of {}>  → (nothing)
DEL S                → (nothing)
```

`K` is still the snapshot from before the dinner. Both writes now look lost.

### T2 — what users see

- Arjun: "I paid ₹3,000 and the app says everyone is settled."
- Priya: "My cab isn't showing either."
- They re-add expenses (duplicates) or settle debts that do not exist.
- A direct database query shows both rows the whole time. Only the Redis
  value is wrong, which is why this is easy to mistake for a write bug.

### T3 — why it stays wrong

Every later `GET /balances` is a cache **hit**. Before the fix,
`TrackingRedisCache.get(Object)` only took a consistency snapshot on a hit;
it did not re-register the key. So `S` stayed empty, every future eviction
saw `{}` and deleted nothing, and the stale JSON lived until its 600 s TTL
expired.

That matches the controlled probe: after a `POST .../expenses`, the balance
key still had `TTL ≈ 583 s` (i.e. it had never been rewritten) while
`SMEMBERS finance:query:v1:group-keys:<id>` returned empty.

## Why the registry could be empty in the first place

Three interleavings all end in "key present, registry empty":

1. **Register-after-read.** Eviction reads `S` before the reader's `SADD`,
   then deletes `S` after it. The new registration is erased.
2. **Fill-during-evict.** The fill's `PUT` and `SADD` land between the
   eviction's `SMEMBERS` and its `DEL S`; the `DEL S` erases the registration.
3. **Best-effort `SADD`.** `CacheKeyRegistry.registerGroup` catches and logs
   exceptions. A transient Redis timeout after a successful `PUT` leaves a
   cached key that was never registered.

Before the fix, none of these healed themselves, because cache hits never
re-registered.

## The same timeline after `39ff430`

- **Step 1** no longer runs inside the transaction. `doEvictGroup` and the
  generation/WAL token both run in `afterCommit`. A reader either sees the
  committed row, or the consistency guard refuses to store its value because
  the generation moved or the replica has not replayed the write.
- **Step 5** may still delete `S`, but `doEvictGroup` **also deletes the
  deterministic key** `finance:analytics:v1:group-balances:50`. Eviction no
  longer depends on the registry for the key that matters. Priya's cab write
  therefore removes the stale value; the next read reloads and shows
  `1800 / −600 / −1200`.
- **The token is published before the delete** (`aa33819`). Deleting first
  left a millisecond-wide window: a reader could miss between the `DEL` and
  the generation bump, load the pre-delete row from a lagging replica, and
  have it accepted because the generation it snapshotted was still the
  pre-write one. With the bump first, that concurrent fill fails the guard;
  the delete then clears whatever the cache holds.
- **Hits re-register.** Even if a key survives an eviction, the next read puts
  it back into `S`, so the following write can evict it.
- **Empty `S` is no longer a no-op.** The evictor SCANs
  `group-details:<id>-*`, `group-expenses:<id>-*` and
  `group-activity:<id>-*` as a fallback.

## How the golden test guards it

`requests/run_golden_test.py`, stage 10:

1. Warm `/balances` and wait until the read model lists every posted expense.
2. Take two identical payloads 0.6 s apart as the settled baseline.
3. Post a canary expense; the cached payload must change within
   `--cache-timeout` (45 s).
4. Delete the canary; the cached payload must return **exactly** to the
   baseline.

The budget is 45 s against a 600 s TTL, so a pass proves eviction rather than
TTL expiry. On the old code the canary produced 7/10 "no change at all" and
6/10 "still includes the deleted expense" failures in a single 100-user run;
the first run against the fixed pods is the acceptance check.

## Files touched by the fix

- `command-service/.../cache/QueryCacheEvictor.java` — after-commit eviction,
  unconditional balance-key delete, SCAN fallback.
- `query-service/.../cache/TrackingRedisCache.java` — register on cache hits
  and on an existing `putIfAbsent`.
- `requests/run_golden_test.py` — stage 10 regression guard and the
  settle-before-canary baseline logic.
