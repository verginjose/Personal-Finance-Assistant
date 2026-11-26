# Native auth and storage

One Rust/Axum executable runs two isolated services. `SERVICE_MODE=auth`
(default) owns `/api/auth/**` and Traefik's private `/internal/verify` endpoint.
`SERVICE_MODE=storage` owns receipt/bill/avatar uploads and private downloads.
The storage role has no database credentials. Both roles validate signed JWTs
and check Redis revocation/session versions; backend identity headers are never
accepted from clients.

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

Storage uploads are limited to 12 MiB and eight simultaneous spools, with a
256-request admission bound. Magic bytes must match the accepted media type;
stored extensions are normalized to that type. MinIO buckets stay private.
Receipt reads consult Command's ownership/group checks. OCR jobs use persistent
RabbitMQ messages, mandatory routing and publisher confirms. Connections are
reestablished after broker failure; readiness checks dependencies.

## Development

```sh
cargo test --locked
cargo clippy --locked -- -D warnings
cargo fmt --check
docker build -t finance/auth-service:dev .
```

Runtime variables are declared in `env/auth-service.env`,
`env/storage-service.env` and the Kubernetes ConfigMaps. Required credentials
must be provided explicitly. Auth additionally reads `DB_HOST` (default
`postgres-primary`), `DB_PORT` (5432), `DB_NAME` (pfa), `DB_USER`, and
`DB_PASSWORD`. Auth listens on 8082; storage on 8083. Both expose liveness,
dependency readiness, and Prometheus histograms on private port 9090.

Run `python3 requests/test_native_migration.py --base-url http://localhost:3001`
from the repository root for live auth, storage, routing, SSE and cross-account
security checks. It creates disposable test accounts. The broader
`requests/run_golden_test.py` also exercises OCR, groups and cache consistency.

Command and Query remain Spring services. The retired Spring auth/gateway code
and deployment can be recovered from Git history; database schema and existing
user/session formats require no rollback migration.
