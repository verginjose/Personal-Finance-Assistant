# Golden test — plan & coverage

`run_golden_test.py` is the single end-to-end + load test for the Personal
Finance Assistant. It drives the whole product through the API gateway with a
cohort of simulated users, asserts behaviour at every step, and exits non-zero
if any check fails or any unexpected `5xx` is returned.

```bash
# local port-forward
python3 requests/run_golden_test.py --users 50

# the full cohort the soak uses
python3 requests/run_golden_test.py --users 100 --groups 10 \
    --bills 10 --think-time 1.0

# public URL
GATEWAY_URL=https://verginjose.cloud python3 requests/run_golden_test.py --users 50
```

`golden_soak.sh` runs the 100-user scenario once every 10 minutes for five
hours, keeping the `kubectl port-forward` alive between runs.

## Default vs. realistic mode

By default the test is a **stress envelope**, not a traffic model: every stage
releases all users at the same instant, so each stage hits one endpoint class
in a single burst, and every user fires the 4-way refresh race. That is
deliberate — it finds contention bugs — but its latency percentiles are
worst-case bounds (e.g. auth p99 is dominated by ~400 synchronized refreshes
and 100 simultaneous BCrypt logins).

`--realistic` spreads arrivals and trims the synthetic bursts so the
percentiles describe something a product could actually see:

```bash
python3 requests/run_golden_test.py --users 100 --groups 10 --think-time 1.0 \
    --realistic --base-url http://localhost:18090
```

| Flag | Default | Effect |
|---|---|---|
| `--realistic` | off | enable the behaviours below |
| `--ramp-seconds` | `20` | each stage's users start over this window instead of together |
| `--refresh-race-fraction` | `0.05` | only this share of users runs the 4-way refresh race; the rest skip it (a real client refreshes once per 15-minute token lifetime) |

Correctness is unchanged — all assertions still run and the verdict is still
`PASS`/`FAIL`; only arrival timing and the size of the refresh burst change.

## What one run does

| Stage | Coverage | Assertions |
|---|---|---|
| 1. Identity | register, login | token + userId returned; realistic names/emails |
| 2. Personal history | 20 transactions/user over 6 months, realistic merchants and matching categories/amounts | rows created, totals tracked |
| 2b. Subscriptions | **daily**, **weekly** and two **monthly** recurring expenses, backdated so `nextRunDate` is already past | created; the hourly recurring scheduler must aggregate missed periods and emit outbox events |
| 2c. Goal + budget | savings goal, contribution, monthly budget | created, contribution accepted |
| 3. Security | refresh-token rotation race (4 parallel replays), idempotent double submit, cross-user read probes | exactly one rotation wins; one row per key; foreign reads are 403/404 |
| 4. Rich reads | subscriptions detection, recurring listing, read-model replication of a recurring entry, bulk create/delete, date filters, every analytics endpoint | 2xx and expected payloads |
| 5. Cache eviction (per user) | warm `/upsert/summary`, patch an amount, delete the row, six parallel identical searches | cached summary shows the update, drops the delete, the deleted entry is unreadable, all parallel reads agree; convergence latency is reported |
| 6. OCR | ~10 receipts/user sampled at random from the 200-image `large-receipt-image-dataset-SRD` set | every upload is `202` with a `receiptId`; ~60% become personal transactions with the returned `receiptUrl`, the rest are attached to shared expenses |
| 7–8. Groups | leaders create groups, peers accept their own invitations | 201/200 |
| 9. Shared expenses | 3/member with realistic descriptions and categories, split **EQUAL / PERCENTAGE / EXACT** | 201; receipts attached to a random subset |
| 10. Group cache eviction | warm cached balances, post a canary expense, delete it | cached balances change, then return exactly to the baseline |
| 11. Settle / archive | half of each group settles a debt, half archives | 200 |
| 12. Deletes | five expenses per group | 200 |
| 13. Verification | summary vs listing vs analytics totals, seeded totals as a floor, recurring/subscription visibility, balances sum to zero and settle cleanly, every member sees the same balances, archived count, group expense count | all checks |

## Why these checks matter

- **Outbox / recurring scheduler.** Backdated daily/weekly/monthly rows make
  the hourly `RecurringTransactionScheduler` aggregate missed periods, insert
  new entries and emit `OutboxEvent`s. The test also asserts that a freshly
  created recurring row reaches the read model, which is the observable end of
  the outbox/CDC path.
- **Cache eviction.** Reads are cached in Redis. A write that fails to evict
  keeps serving the stale value for the whole TTL. Stage 5 warms the cache and
  then proves convergence within `--cache-timeout` (default 25 s); the same
  pattern is repeated for group balances in stage 10. Convergence latency is
  printed per run, so a slow eviction is visible rather than averaged away.
- **Deleted rows must not reappear.** Stage 5 deletes a just-updated entry and
  checks both the cached summary and the single-entry read: this is the
  regression guard for the stale-read-after-write bug.
- **Read-model consistency.** The summary, the listing and the analytics
  projection are compared with each other, not only with the writer's intent.
  OCR and scheduler rows may legitimately appear during the run, so seeded
  totals are asserted as a floor while the three read paths must agree exactly.

## Findings from the first runs (2026-11-02)

- **`group-balances` could serve a stale payload for its full 10-minute TTL
  after a write.** Eviction depended on every filled key being present in the
  Redis registry set `finance:query:v1:group-keys:<groupId>`. A controlled
  probe showed the balance key cached with a fresh 600 s TTL while its registry
  set was empty; the following `POST .../expenses` therefore deleted nothing,
  and the cached balances kept reporting the pre-expense nets. Once the key had
  been registered (after another read), deleting an expense *did* evict it.
  Stage 10 is the regression guard.
  **Fixed** in `39ff430`: `QueryCacheEvictor` now always deletes the
  deterministic `group-balances:<id>` key and SCANs the other group prefixes
  when the registry set is empty; group eviction plus its write tokens run in
  `afterCommit` (not inside the transaction, which left a refill window); and
  `TrackingRedisCache` re-registers a key on a cache hit, not only on a fill.
  A worked timeline of the failure and the fix is in
  [`GROUP-BALANCES-STALE-CACHE.md`](GROUP-BALANCES-STALE-CACHE.md).
- **Group expense count must be asserted exactly.** The earlier scenario used
  `listed >= posted - deleted`, which is trivially true when a delete is not
  reflected. The golden test now polls for the exact regular-expense count
  (settlement rows excluded).
- **Seeded recurring rows must not be deleted.** The random delete step
  sampled every created row, so ~25% of users lost the daily/monthly fixtures
  the recurrence checks assert on. Deletes now skip `recurring_ids`.
- **Cerebras rate-limits OCR completions.** The OCR service now
  spaces calls and retries 429s; see the section above.
- The user-level cache paths (`summary`, single-entry, search) converge in
  ~0.5–2.5 s and pass consistently; the daily/monthly recurring seeds are
  detected and replicated to the read model on every run.

## Cerebras OCR rate limit

The OCR service calls Cerebras once per unique receipt text. The extractor
spaces calls by `60 / CEREBRAS_RPM` (default 450, the account's limit) and
retries `429`s with `Retry-After` (or a 2 s floor) up to `CEREBRAS_MAX_ATTEMPTS`
(default 5) instead of acking the job as failed. At 450 RPM the
200-image fixture fills the OCR/LLM caches in roughly half a minute the
first time a pod starts; afterwards both caches are hit and the queue drains
immediately. The golden test itself never asserts Cerebras-derived rows — uploads
assert `202` + `receiptId` — so the limit cannot turn the run red; the
OCR→transaction half of the stage is created synchronously by the test.

## Operational notes

- Everything goes through the gateway (`/api/**`); direct service ports are
  never used. The gateway owns JWT validation, `X-User-Id` replacement and
  per-user rate limiting.
- `--pace` (default 0.06 s between writes per simulated user) keeps the cohort
  under the gateway's per-user write limit instead of relying on 429 retries.
- `--think-time` jitters each step, so users desynchronise into a steady
  stream rather than a burst.
- Credentials for the run are written to `golden-users.csv`; the JSON report
  goes to `golden-report.json` (both ignored by git unless a path is given).
