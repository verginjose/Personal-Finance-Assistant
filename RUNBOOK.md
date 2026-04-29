# Personal Finance Assistant Runbook

## Rebuild and Boot

```bash
docker compose down --remove-orphans
docker compose up -d --build
docker compose ps -a
```

## Smoke Verification

### Core Services

```bash
curl http://localhost:8082/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8084/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8080/actuator/health
```

### Observability

```bash
curl http://localhost:9090/-/healthy
curl http://localhost:3000/api/health
curl http://localhost:8123/ping
curl "http://localhost:8123/?user=default&password=clickhouse" \
  --data-binary "SELECT count() FROM observability_logs.container_logs"
```

## Common Issues

- `Bind for 0.0.0.0:5432 failed`
  - Another local Postgres container is still running.
  - Fix: `docker compose down --remove-orphans` then `docker compose up -d --build`.

- `upsert-service` or `analytics-service` exits on startup
  - Usually DB startup race.
  - Fix: restart services after Postgres is healthy:
    `docker compose restart upsert-service analytics-service api-gateway`

- Fluent Bit cannot write to ClickHouse
  - Confirm ClickHouse credentials in compose and fluent-bit config.
  - Check with:
    `docker compose logs --tail=80 fluent-bit clickhouse`

## UI Acceptance Checklist

- Transactions tab supports:
  - advanced amount/date filters
  - saved search presets
  - bulk select/delete
- Operations tab shows service status cards and observability links.
- Destructive actions use the custom confirmation modal.
