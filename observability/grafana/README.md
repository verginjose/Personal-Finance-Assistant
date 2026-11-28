# Grafana dashboards

Start with **Finance Assistant — Production Overview**: backend scrape health,
incoming gateway traffic, gateway errors and latency, then service trends.
The four expected backend targets are edge-router, auth-service, command-service
and query-service. Storage handler metrics are included in Command. Scrape health indicates metric endpoint reachability, not
end-to-end application availability. No traffic or missing metrics can display
“No data”; this is not a healthy zero.

The overview separates **Traefik-to-service p99** from **application handler
p95/p99**. The former is measured by Traefik while processing the backend
request; the latter is recorded inside the service handler. The gateway value
can be higher when time is spent outside the handler, so a low service p99 does
not imply the gateway request completed equally quickly. Traefik p99 is hidden
below 1 request/second because a few samples make a noisy percentile.

Use the navigation links for investigation:

- **Pods**: CPU, memory, and restarts across `pfa` and `pfa-system`, grouped by
  workload (`pfa-apps`, `ci`, or `infra`) using pod names. Current CPU and memory
  tables are sorted highest first; percentage graphs compare each pod to its
  resource limit.
- **Logs**: service options come from logs in the selected time range. Level and
  Search narrow matching logs; error and warning totals deliberately ignore Level.
- **Data**: current database and cache health, with deeper diagnostics collapsed.
- **Postgres / Redis**: service-scoped logs and resource pressure; expand the
  runbook when needed. There is no service wildcard on these pages.

Dashboard JSON files in `dashboards/` are the source. Docker Compose mounts them;
`k8s/apply.sh` generates the cluster ConfigMap from the same directory. Keep
`k8s/config/infra/grafana-dashboards.yaml` synchronized for manifest consumers.
Existing dashboard UIDs are preserved so saved links remain valid.

These changes take effect after the normal deployment/provisioning cycle.
Prometheus scraping is configured separately and is unchanged by dashboard edits.
