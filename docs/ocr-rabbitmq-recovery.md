# OCR RabbitMQ authentication recovery

The 2026-11-09 deployment had matching broker, gateway and OCR Kubernetes
credentials, but RabbitMQ's persisted database contained only the old `guest`
account. The configured service account was missing, causing repeated OCR
`ACCESS_REFUSED` errors and zero consumers on `ocr-jobs`.

Default-user settings initialize a new database; they do not reconcile existing
users on a persistent volume. See RabbitMQ's
[access-control documentation](https://www.rabbitmq.com/docs/access-control).

The live repair created the configured account using the broker pod's existing
credential environment and granted application-vhost permissions. No database
reset, queue deletion, password change, or broker restart was required. Verified
one OCR queue consumer and a temporary-queue publish/consume/acknowledgement
round trip using the OCR pod's credentials.

## Prevent recurrence

`k8s/infrastructure/rabbitmq.yaml` mounts a post-start hook that waits for the
broker, creates a missing configured account or reconciles a stale password,
then ensures access to the application vhost `/`. It preserves existing users,
queues and messages. It uses the existing Secret-backed environment and never
prints passwords. Matching credentials are verified without resetting them.

`ocr-service/app/main.py` exposes `/ready` separately from `/health`. Readiness
returns 503 until the worker has subscribed, and while its connection is down,
closed, or its consumer task has stopped. The image-promotion workflow updates Kubernetes to use `/ready` in the same PR
as the new OCR image tag; startup/liveness keep `/health` so a broker outage does not cause GPU reloads.
The background consumer is cancelled and its connection closed on shutdown.
Connection logs no longer print the credential-bearing AMQP URL.

The live service is repaired immediately. The startup hook becomes active after its source PR is merged. The readiness
probe changes atomically with the image tag in the normal image-promotion PR,
so an older OCR image is never probed at the missing `/ready` route.

## Checks

```bash
python3 -I -m unittest discover -s ocr-service/tests -v
python3 -I -m unittest discover -s k8s/infrastructure/tests -v
```

The infrastructure tests use PyYAML and mock the RabbitMQ CLI. They cover missing
accounts, stale passwords, matching credentials, and permission failures. OCR
readiness tests require only the standard library, with no CUDA imports.
