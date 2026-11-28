# Native auth

The Rust/Axum auth service owns `/api/auth/**` and Traefik's private
`/internal/verify` endpoint. Storage is now a module in Command; Auth has no
MinIO or RabbitMQ client. Auth verifies signed JWTs and checks Redis
revocation/session versions. The edge strips client identity headers before
forwarding authenticated requests to Command and Query.

The original PostgreSQL `auth.users` table, Spring BCrypt hashes, JWT HMAC key
selection, token claims and Redis keys are retained. Refresh cookies remain
HttpOnly, SameSite=Strict, `/api/auth`, and Secure in Kubernetes. Existing access
and refresh sessions survive migration. Four concurrent refresh requests receive
one newly issued session pair through an atomic Redis script and a 60-second
grace record. Revoked/expired sessions cannot recover through that grace record.

Password hashing uses a bounded blocking pool. Login holds a shared user-row
lock and password changes hold an exclusive lock while updating credentials and
revoking sessions, preventing an old-password login racing a password change
from issuing a token with the new session version.

## Development

```sh
cargo test --locked
cargo clippy --locked -- -D warnings
cargo fmt --check
docker build -t finance/auth-service:dev .
```

Runtime credentials are declared in `env/auth-service.env`; non-secret settings
live in the auth ConfigMap. Required credentials must be provided explicitly.
Auth reads `DB_HOST` (default `postgres-primary`), `DB_PORT` (5432), `DB_NAME`
(pfa), `DB_USER` and `DB_PASSWORD`. It listens on 8082 and exposes liveness,
dependency readiness and Prometheus histograms on private port 9090.

For the module map and test-crate layout, see
[the Rust code guide](../docs/rust-code-guide.md).

Run `python3 requests/test_native_migration.py --base-url http://localhost:3001`
from the repository root for live auth, storage, routing, SSE and cross-account
security checks. It creates disposable test accounts. The broader
`requests/run_golden_test.py` also exercises OCR, groups and cache consistency.

Command and Query were also replaced by Rust services; the retired Spring code
and deployment can be recovered from Git history; database schema and existing
user/session formats require no rollback migration.
