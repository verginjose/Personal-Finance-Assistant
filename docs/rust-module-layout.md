# Rust service module layout

All three backend services (`auth-service`, `command-service`, `query-service`)
follow the same idea: **one folder per domain, split by responsibility**, and
`main.rs` only wires things together and starts the server.

There is no "900-line blob" — the largest file in any service is roughly
600 lines, and most are 100–400.

## Rust module basics (quick primer)

- `mod foo;` tells the compiler "there is a module named `foo`". The code lives
  in `foo.rs` **or** in a folder `foo/` with a `foo/mod.rs` (plus optional
  `foo/bar.rs`).
- `foo/mod.rs` declares the submodules and re-exports what the rest of the crate
  may use. Re-exporting is what lets other files keep writing short names.
- `pub(crate)` = "visible anywhere inside this binary, but not part of a public
  API". We use this a lot so sibling modules can share helpers.
- `use crate::*;` pulls in the crate-root items (`App`, `Error`, `Result`,
  helpers). `use super::*;` pulls in the parent module's items. Submodules use
  these two globs so moved code keeps resolving the same names.
- A module named `groups` whose code used to be `groups.rs` is now a folder
  `groups/`; the public surface (`groups::routes()`, `entries::create()`) is
  unchanged, so nothing else had to move.

## command-service (write side)

```
rust/main.rs            server wiring, App/Error, shared helpers, routes()
rust/groups/            everything about groups (the old 1780-line groups.rs)
    mod.rs              routes() + column constants
    models.rs           row/DTO structs (GroupRow, CreateGroup, ...)
    helpers.rs          JSON mappers, authorization, activity + notifications
    balance.rs          balances() and debt minimization  (pure maths)
    splits.rs           split maths + split persistence
    membership.rs       create/update/delete/archive group, members, invites, admin
    expenses.rs         expenses, settlements, personal transactions
rust/entries/           the entries domain (the old 782-line entries.rs)
    mod.rs              category constants + re-exports
    models.rs           EntryRequest / Validated / EntryRow
    validation.rs       amount, category and recovery/reporting rules
    operations.rs       create/update/delete/patch, outbox, subscription detection
rust/planning.rs        budgets, goals, forecasts
rust/notifications.rs   notification events
rust/subscriptions.rs   recurring subscriptions
rust/jobs.rs            background jobs
rust/cache.rs           Redis cache helpers
```

## auth-service (auth + storage)

```
rust/main.rs            server wiring, App/Error, hash-slot semaphore
rust/auth/              the auth domain (the old 761-line auth.rs)
    mod.rs              routes(), shared db(), unit tests
    models.rs           Claims and the User row
    tokens.rs           key selection, mint/validate JWT, identity, cookies
    password.rs         bcrypt hash/verify, currency validation
    handlers.rs         register/login/refresh/me/profile/change-password/...
    verify.rs           /internal/verify (Traefik ForwardAuth) + rate limits
rust/storage.rs         receipt upload/storage + OCR job publishing
```

## query-service (read side)

```
rust/main.rs            module wiring, bootstrap, generic handle()/health
rust/app.rs             App, Error, Result, Params + query helpers (paging, dates...)
rust/ledger.rs          ledger / transaction reads
rust/groups.rs          group + balance reads
rust/analytics.rs       analytics aggregates
rust/planning.rs        budgets/goals/forecast reads
```

## How to add a new endpoint

1. Decide the domain folder (e.g. `groups/`).
2. Add the handler to the matching file (`membership.rs` for group operations,
   `expenses.rs` for expense/settlement operations).
3. Register it in that domain's `mod.rs` `routes()`.
4. If it is brand-new shared logic (e.g. a maths function), put it in the
   focused file (`balance.rs`, `splits.rs`) so it can be unit-tested on its own.

## Verifying a change

```bash
cd command-service && cargo check && cargo test     # or auth-service / query-service
```

CI builds each service image only when its source changes on `master`.
