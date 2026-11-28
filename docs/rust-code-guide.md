# Reading the Rust services

Start with a service's `rust/main.rs`. It starts Tokio and calls the library's
`run()` function. Every service follows the same layout:

| File | Responsibility |
| --- | --- |
| `main.rs` | Executable entry point; no business logic |
| `lib.rs` | Module index and the small API used by integration tests |
| `startup.rs` | Connect databases/backends, build state, start listeners |
| `app.rs` | Shared database pools, clients and other application state |
| `config.rs` | Environment settings |
| `routes.rs` | Connect URL paths to handlers; apply HTTP middleware |
| `request.rs` | Request parsing and identity/query validation |
| `error.rs` | Convert service errors into HTTP responses |
| `health.rs` | Liveness, readiness and metrics endpoints |
| `observability.rs` | Request logs and latency metrics |

Auth does not need `request.rs`; its token/cookie helpers live in `auth/`.
Command uses `format.rs` for dates and decimal amounts. Query uses
`serialization.rs` for JSON field names and pagination responses.

## Follow one request

For a bill upload, follow this path:

1. `edge-router/routes.yaml` authenticates the request through Auth, then sends
   the original `/api/bill/process/{id}` path to Command on port 8081.
2. `command-service/rust/routes.rs` includes `storage::routes()`.
3. `storage/mod.rs` selects the handler in `storage/upload.rs`.
4. `storage/access.rs` checks the trusted identity against the URL's user ID.
5. `storage/validation.rs` checks the filename, media type and file signature.
6. `storage/object_store.rs` signs the MinIO request.
7. `storage/broker.rs` publishes a durable OCR job and requires confirmation.
8. OCR later calls the handlers in `notifications/ocr.rs`; `notifications/events.rs`
   persists and broadcasts the completion, and `notifications/inbox.rs` serves it.

The edge strips client-supplied identity headers before ForwardAuth. Storage,
like other Command handlers, uses that trusted identity. Receipt downloads call
the same ownership/group-access function as `/upsert/storage-access`, directly
inside Command. Auth continues to own JWT verification and session revocation.

## Find a feature

| Service | Folder/module | What to look for |
| --- | --- | --- |
| Auth | `auth/accounts.rs` | Registration, profile, password changes |
| Auth | `auth/sessions.rs` | Login, refresh, logout, stream tickets |
| Auth | `auth/directory.rs` | User search and lookup |
| Auth | `auth/tokens.rs`, `auth/password.rs` | JWT/cookie policy and password helpers |
| Command | `entries/` | Transaction models, validation, write operations |
| Command | `groups/lifecycle.rs` | Create, update, archive, delete groups |
| Command | `groups/invitations.rs`, `groups/members.rs` | Invitations and membership changes |
| Command | `groups/expenses.rs`, `groups/settlements.rs` | Shared expenses and debt settlement |
| Command | `groups/personal_entries.rs` | Project shared expenses into personal ledgers |
| Command | `planning/` | Goals, budgets and subscription updates |
| Command | `notifications/` | Persisted inbox, SSE and OCR callbacks |
| Command | `storage/` | Uploads, private downloads, MinIO and OCR publishing |
| Command | `jobs.rs`, `cache.rs` | Background jobs and cache invalidation |
| Query | `ledger/entries.rs` | Ledger/search/entity reads |
| Query | `ledger/reporting.rs`, `ledger/currency.rs` | Reporting rules and currency conversion |
| Query | `ledger/export.rs` | Bounded streaming CSV export |
| Query | `groups.rs`, `analytics.rs` | Group reads and chart responses |
| Query | `planning/` | Planning reads, forecasts and cached insights |
| Query | `replica.rs` | Read-your-writes WAL wait on the read replica |

A Rust **module** groups related code inside a crate. `mod storage;` in
`lib.rs` loads `storage/mod.rs`, whose declarations load the files in that
folder. The `mod.rs` file is the feature's index; it registers routes or exposes
selected operations. Explicit `use` statements show where a function or type
comes from. Keep a new handler with its feature instead of adding it to startup
or collecting unrelated helpers in the library root.

## Tests are separate crates

Each service is a Cargo package with a library and a small executable. Each
file directly inside its `tests/` directory is compiled by Cargo as a separate
integration-test crate. These tests import the library through `pfa_native`,
`pfa_command` or `pfa_query`; they do not include production source files by path.
There is no extra test package or workspace lockfile to maintain.

From the repository root, run the following for each service:

```sh
cargo test --locked --all-targets --manifest-path command-service/Cargo.toml
cargo clippy --locked --all-targets --manifest-path command-service/Cargo.toml -- -D warnings
cargo fmt --manifest-path command-service/Cargo.toml --check
```

Replace `command-service` with `auth-service` or `query-service` as needed.
The upload and metrics regressions bind ephemeral localhost TCP ports. The
other tests need no running database, Redis, MinIO or RabbitMQ. CI validates
all three packages; their Dockerfiles copy `tests/` and run tests before the
release build. Python deployment-contract tests live under `k8s/infrastructure/tests/`.

## Storage consolidation and rollout

Storage is a Command module, served on port 8081. Public URLs and the gateway's
longer upload/download timeout remain the same. The separate storage Deployment,
Service, ConfigMap, NetworkPolicy and scrape target are removed. Command retains
its 512 MiB limit, and uploads retain a two-slot spool guard, a 12 MiB body limit,
file signature checks, private buckets and confirmed publishing. Command readiness
now includes MinIO and RabbitMQ in addition to PostgreSQL. Auth has no storage
clients or storage credentials.

The existing `storage-service-secrets` name is retained as a credential bundle:
Sealed Secrets binds its ciphertext to that name. Command selects only its four
MinIO/RabbitMQ keys with `secretKeyRef`; it does not receive the legacy bundle's
JWT or Redis credentials. Keep `env/storage-service.env` and its sealing entry
until the bundle is deliberately migrated. No credential values are changed by
this refactor.

The manifest changes describe the final deployment. For the first rollout,
build the new Command and edge images and pin their tags/digests **before**
applying these manifests. Promote Command with its storage credentials/network
access first; check readiness and the storage endpoints before switching the
edge and retiring the old storage deployment. The old Command image cannot
serve the new storage routes, and the old edge image still targets the former
storage Service. Do not let Argo prune those resources ahead of this transition.
A source merge alone does not prove that the replacement images are live.

The local laptop guard uses the user-requested 90C CPU/SSD stop ceiling and
85C start gate. GPU, memory, CPU utilization, swap, freshness and runtime limits
are separate and remain enforced. Run the golden test through
`requests/laptop_guard.py run --local-sensors`; do not set its guard flag by hand.

## Local test traffic without rate limits

Traefik has a loopback-only test listener on `127.0.0.1:8085` inside its pod.
Forward it to the laptop with:

```sh
kubectl -n pfa port-forward deployment/edge-router 18085:8085 --address 127.0.0.1
```

Use `--base-url http://127.0.0.1:18085` for local tests. These routes still run
JWT/session validation and the same handlers, while bypassing Auth's token
buckets. Traefik strips the private test marker from public requests and adds
it only on this listener. It is not a Service or public ingress port. Public
traffic retains rate limits; supplying a loopback address in a header does
not enable the exemption.
