# Grafana dashboards

Start with **Finance Assistant — Production Overview**: backend scrape health,
incoming gateway traffic, gateway errors and latency, then service trends.
The four expected backend targets are api-gateway, auth-service, command-service
and query-service. Scrape health indicates metric endpoint reachability, not
end-to-end application availability. No traffic or missing metrics can display
“No data”; this is not a healthy zero.

Use the navigation links for investigation:

- **Pods**: application namespace (`pfa`) resources, with an optional pod filter.
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
