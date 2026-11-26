# Traefik API edge

The frontend is the same-origin entry point and forwards `/api/` to
`edge-router:8080`. Traefik's file provider loads `routes.yaml`; it does not
need Kubernetes API access or a service-account token. The cluster's existing
Traefik ingress continues to route the public hostname to the frontend.

Middleware order is deliberate: strip untrusted identity/forwarding headers,
call Rust ForwardAuth (including shared Redis rate limits), then rewrite the
path and proxy it. Never move authentication after path rewriting: auth must
see the original URL for stream-ticket restrictions and per-route limits.
`X-Real-IP` is overwritten by frontend nginx, and NetworkPolicy allows only the
frontend to call the edge application port. Backends accept calls only from
this edge or explicitly authorized internal services.

Writes retain the 15-second response-header timeout, analytics 45 seconds, and
notifications 30 minutes with immediate flushing and no write deadline. No
middleware retries mutations. Traefik network-error circuit breakers return
503; their sampling semantics differ from the retired Resilience4j breakers.
Health and Prometheus are exposed only on the private management port 9090.

Build with `docker build -t finance/edge-router:dev edge-router` from the repo
root. Docker Compose uses the same packaged routing configuration.
