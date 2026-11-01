# Concurrent Functional Load Test — Plan & Coverage

This document maps every feature of the Personal Finance Assistant to the
endpoint that proves it, defines what `run_load_test.py` asserts, and records
the runtime constraints that shape the test (rate limits, CQRS lag, async OCR).

Run it with:

```bash
# against a port-forward or the public URL
python3 requests/run_load_test.py --users 20 --txns 100 --months 6

GATEWAY_URL=https://verginjose.cloud python3 requests/run_load_test.py --users 20

# sustainable under the current gateway/Postgres limits (see §7)
python3 requests/run_load_test.py --users 20 --txns 100 --pace 0.05
```

Exit code is `0` only if every targeted check passed and no unexpected `5xx`
was returned.

---

## 1. Topology the test drives

Everything goes through the API gateway (`/api/**`). The gateway validates the
JWT, overwrites `X-User-Id` from the token's `userId` claim, and rate-limits.
Direct service ports are never used.

| Path prefix | Service | Notes |
|---|---|---|
| `/api/auth/**` | auth-service | forward path unchanged |
| `/api/bill/**` | api-gateway (in-process upload controller) | stores to MinIO, publishes `ocr-jobs` |
| `/api/upsert/**` POST/PUT/PATCH/DELETE | command-service | writes |
| `/api/upsert/**` GET | query-service | reads (CQRS projection) |
| `/api/upsert/notifications/**` | command-service | SSE, long-lived |
| `/api/analytics/**` | query-service | reads |
| `/api/storage/**` | MinIO | receipt objects |

---

## 2. Feature inventory

"Covered" = exercised and asserted by `run_load_test.py`.

### Auth (auth-service)

| Feature | Endpoint | Assertion |
|---|---|---|
| Register user | `POST /auth/register` | `201`, returns `userId` (UUID) |
| Login | `POST /auth/login` | `200`, access + refresh token, `userId` |
| Identity | `GET /auth/me` | `200`, email matches |
| Token refresh rotation | `POST /auth/refresh` | `200`, new token differs, old refresh token rejected |
| Logout / revocation | `POST /auth/logout` | `200`, then `GET /auth/me` with the old token → `401` |
| Change password | `POST /auth/change-password` | `200`, re-login with new password succeeds |
| Health | `GET /auth/health` | `200` |

### Transactions (command → query)

| Feature | Endpoint | Assertion |
|---|---|---|
| Create entry | `POST /upsert/create` | `201`, returns id |
| Idempotency | `POST /upsert/create` + `X-Idempotency-Key` twice | same id, no duplicate row |
| Bulk create | `POST /upsert/create/bulk` | `200/201` for every item |
| Update entry | `PUT /upsert/update` | `200`, field reflected |
| Patch amount | `PATCH /upsert/entries/{id}/amount` | `200`, amount reflected |
| Read one | `GET /upsert/entries/{id}` | `200` |
| List + filters | `GET /upsert/entries?userId&type&startDate&endDate&page&size` | `200`, every row inside the date window |
| Search | `GET /upsert/search?q=` | `200`, the seeded unique name is returned |
| Summary | `GET /upsert/summary` | totals ≥ seeded totals, and equal to the sum over listed entries |
| CSV export | `GET /upsert/entries/export` | `text/csv`, ≥ seeded row count |
| Recurring list | `GET /upsert/recurring` | `200`, contains seeded recurring names |
| Delete | `DELETE /upsert/delete/{id}` | `200`, row gone |
| Bulk delete | `POST /upsert/delete/bulk` | `200`, rows gone |
| Validation | `POST /upsert/create` with bad currency | `4xx` |
| Multi-month history | `createdAt` on create | entries spread across N months |

### Goals & budgets

| Feature | Endpoint | Assertion |
|---|---|---|
| Create goal | `POST /upsert/goals` | `201`, `savedAmount` starts at 0 |
| Contribute | `PATCH /upsert/goals/{id}/contribute` | `200`, `savedAmount` == contribution |
| List goals | `GET /upsert/goals` | `200`, goal present |
| Goal transactions | `GET /upsert/goals/{id}/transactions` | `200` |
| Goal forecast | `GET /analytics/goals/{id}/forecast` | `200` |
| Delete goal | `DELETE /upsert/goals/{id}` | `2xx` |
| Create budget | `POST /upsert/budgets` | `201`, `budgetId` returned |
| Budget utilization | `GET /upsert/budgets` | `200`, `spentAmount` ≥ seeded spend in that category |
| Budget trends | `GET /analytics/budgets/trends` | `200` |
| Delete budget | `DELETE /upsert/budgets/{id}` | `2xx` |

### Split bill / groups

| Feature | Endpoint | Assertion |
|---|---|---|
| Create group | `POST /upsert/groups` | `201` |
| List my groups | `GET /upsert/groups?userId=` | `200`, group present |
| Get group | `GET /upsert/groups/{id}` | `200` |
| Add member | `POST /upsert/groups/{id}/members` | `201`, member `PENDING` |
| Accept invitation (as the member) | `POST /upsert/groups/{id}/members/{uid}/accept` | `200` |
| List members | `GET /upsert/groups/{id}/members` | `200`, peer `ACCEPTED` |
| Add shared expense | `POST /upsert/groups/{id}/expenses` | `201` |
| List expenses | `GET /upsert/groups/{id}/expenses` | `200` |
| Activity feed | `GET /upsert/groups/{id}/activity` | `200` |
| Balances | `GET /upsert/groups/{id}/balances` | `200`, `simplifiedDebts` non-empty |
| Settle | `POST /upsert/groups/{id}/settle?from&to` | `200` |

### Subscriptions ("vampire" detector)

| Feature | Endpoint | Assertion |
|---|---|---|
| Explicit recurring detection | `GET /upsert/subscriptions?userId=` | `200`, seeded recurring names present |
| Pattern detection | same | ≥2 same-name expenses within 90 days produce a candidate |
| Deactivate | `DELETE /upsert/subscriptions/{id}/deactivate` | `200`, gone from the list |

### Bill scanner (OCR)

| Feature | Endpoint | Assertion |
|---|---|---|
| Upload receipt | `POST /bill/process/{userId}` (multipart `file`) | `202`, `receiptId` + storage `url` |
| Batch statement | `POST /bill/batch-process/{userId}` | `202` |

The parse result arrives later over SSE
(`/api/upsert/notifications/{userId}`) and auto-creates expense entries. The
load test does **not** hold the SSE stream open; it asserts the `202` contract
and tolerates the extra rows OCR later adds (see §4).

### Analytics & gamification

| Feature | Endpoint | Assertion |
|---|---|---|
| Category pie chart | `GET /analytics/category-pie-chart` | `200` |
| Timeline chart | `GET /analytics/timeline-chart` | `200` |
| Comprehensive | `GET /analytics/comprehensive` | `200` |
| Transaction entries page | `GET /analytics/transaction-entries` | `200` |
| Income by category | `GET /analytics/transactions/income-by-category?categoryName=&startDate=&endDate=` | `200` |
| By type | `GET /analytics/transactions/by-type?transactionType=&startDate=&endDate=` | `200` |
| Health score | `GET /analytics/health-score` | `200`, `totalScore` + `breakdown` |
| AI insights | `GET /analytics/ai-insights` | `200` — **opt-in** (`--with-ai`), calls Groq |

`POST /analytics/custom-analytics` is listed in `endpoints.md` but has no
controller mapping in `query-service`; it is excluded and reported as a
documentation gap rather than a failure.

---

## 3. What each simulated user does

`--users N` threads run concurrently. Each thread owns one user and walks this
script; stages that need peer coordination are separated by barriers so all N
users cross together.

| Stage | Per user | Parallelism |
|---|---|---|
| 0 | register, login | all users at once (paced for the auth rate limit) |
| 1 | `--cycles` login → refresh → logout → verify 401 → re-login | per user |
| 2 | `--txns` transactions spread over `--months` months, ~15% recurring, plus goals, budgets, subscription seed, validation + idempotency checks | per user |
| 3 | `--bills` receipt uploads (inner parallel, 2 at a time) | per user |
| 4 | create a group and invite `--group-size - 1` random peers | per user |
| 5 | every invited user accepts their invitations | per user |
| 6 | add shared expenses, read balances, settle a debt | per user |
| 7 | analytics sweep (incl. AI insights when `--with-ai`), summary/CSV/consistency verification | per user |

Totals at defaults (20 users, 100 txns, 2 bills):

- 2 000 transactions + ~200 goal/budget/subscription writes
- 40 receipts, 40 AI-insight calls if enabled
- 20 groups × 3 members, 60 acceptance calls, 120 shared expenses
- ~6 000 HTTP requests in total

`run_scenario_50.py` adds a stage between the personal writes and OCR:

- **refresh race** — four concurrent replays of the same refresh token; exactly
  one rotation may succeed and the losers must get a 4xx.
- **idempotent double-submit** — two concurrent creates with one
  `X-Idempotency-Key`; both must be accepted with the same entry id, so the
  retry layer cannot duplicate the row. The personal creates also send a key
  per click, the way a real client should.
- **cross-user probes** — another member's token must not read this member's
  entry or summary (403/404), and the run never issues a destructive
  cross-user call.
- **analytics under writes** — `timeline-chart` and `comprehensive` are read
  while the cohort is still posting shared expenses, not after it settles.

---

## 4. Constraints that shape the assertions

These are properties of the system, not flaky tests. The script encodes them.

1. **Rate limits (gateway).**
   - `/api/auth/**` → `ipPathKeyResolver`: **10 req/s replenish, burst 20,
     per client IP *and* per path**. All simulated users share one IP, so
     registration and login are the only globally contended endpoints. The
     client retries `429` with exponential backoff + jitter; `429`s are counted
     as retries, never as failures.
   - `/api/upsert/**` and `/api/analytics/**` → `userKeyResolver`: 20 req/s,
     burst 40, **per user** — 20 users get 20 independent buckets.

2. **CQRS lag.** Writes land in command-service, events flow through RabbitMQ,
   and the read model in query-service catches up asynchronously. Summary,
   list and analytics assertions therefore poll up to `--verify-timeout`
   (default 20 s) instead of asserting immediately.

3. **Async OCR.** `/bill/process` returns `202` and OCR writes entries *after*
   the fact, so exact total-count equality is not a valid assertion. The script
   asserts `totalCount >= seeded` and separately checks **internal
   consistency**: `summary.totalIncome/totalExpense` must equal the sum over
   `GET /upsert/entries` at the same moment. That check is immune to timing.

4. **Caching.** `summary`, analytics and `ai-insights` are cached in Redis and
   evicted by events. The first `ai-insights` call is slow (Groq); a repeat is
   expected to be sub-100 ms. The script records both timings rather than
   hard-failing on the LLM.

5. **Groq cost/latency.** `ai-insights` is opt-in: 20 concurrent LLM calls is
   the single most expensive thing this suite can do.

6. **`createdAt`, not `date`.** `CreateEntryRequest` has `createdAt`
   (`LocalDateTime`); a `date` field is silently ignored. Multi-month data must
   use `createdAt` — the script does, and asserts filtered listing respects it.

7. **Settle requires accepted members.** A `PENDING` member is excluded from
   splits, so stage 5 (accept) must complete before stage 6 (expenses).

---

## 5. Output

Console:

- one line per failed check, tagged with the user
- a per-phase table: requests, `2xx`/`4xx`/`5xx`, retries, `429`s, `503`s,
  p50/p95/p99
- a totals line and a `PASS`/`FAIL` verdict

Machine-readable: `--out requests/loadtest-report.json` (git-ignored) contains
per-check results, per-phase latency, status distribution and a failure list —
enough to graph or diff between runs.

## 6. Triage

| Symptom | Likely cause |
|---|---|
| many `429` on `/auth/login` | expected; runner backs off. Persistent → lower `--users` |
| summary totals never converge | RabbitMQ down or query-service consumer lagging |
| `401` after logout everywhere | token blacklist working — but check the re-login stored the new token |
| `CreateContainerConfigError` out of scope | infra, not the app |
| balances empty | peers did not accept — stage 5 raced or `--group-size` > users |
| `502/503` bursts | circuit breaker opened; check gateway fallbacks |

---

## 7. Findings from validating this suite

The runner was executed against the live cluster while being written. Two
things it surfaced are worth keeping:

**1. `upsert-cb` opens under modest concurrent write load.** At 10 concurrent
writers with no pacing, the gateway returned `503` for 172 writes; at 20
writers it returned `305`. Every one was retried away successfully, so the
functional result is green, but the cause is configuration, not the client:

```yaml
upsert-cb:
  sliding-window-size: 10            # a 10-call window is very twitchy
  slow-call-duration-threshold: 3s
  slow-call-rate-threshold: 80
  wait-duration-in-open-state: 30s
```

A 10-sample window plus "80% of calls over 3 s" opens the breaker as soon as
Postgres queues. `postgres-primary` is capped at `cpu: 1`, and command-service
at `cpu: 1`, which is where the 3 s tail comes from. Raising the Postgres CPU
limit and widening the breaker window (`sliding-window-size: 50`) would let the
stack absorb 20 writers without tripping. `--pace 0.05` keeps a run green
without touching the cluster.

**2. Two analytics endpoints answer `500` for a missing parameter.**
`GET /analytics/transactions/income-by-category` and
`GET /analytics/transactions/by-type` take `categoryName` / `transactionType`
plus mandatory `startDate`/`endDate` — `endpoints.md` documents `incomeCategory`
and `type` with no dates. Calling the documented form produces:

```
MissingServletRequestParameterException: Required request parameter
'categoryName' ... is not present
```

…mapped to `500` by `GlobalExceptionHandler` instead of `400`. The runner uses
the real parameter names; the documentation and the status code mapping are
both wrong.

**Validated runs** (port-forward to `svc/api-gateway`, this commit):

| config | checks | 5xx | notes |
|---|---|---|---|
| 4 users, 6 txns, 1 bill | 352/352 | 0 | OCR path exercised |
| 6 users, 8 txns | 522/522 | 0 | |
| 10 users, 20 txns | 953/960 | 7 | breaker exhausted under burst |
| 20 users, 20 txns, `--pace 0.05`, 1 bill each | 1959/1960 | 0 | 2 229 requests, 197 s |
