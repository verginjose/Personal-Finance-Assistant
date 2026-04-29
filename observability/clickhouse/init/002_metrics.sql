-- ─────────────────────────────────────────────
-- Service Request Metrics Table
-- Populated by Fluent Bit or application logs
-- ─────────────────────────────────────────────
CREATE DATABASE IF NOT EXISTS observability_logs;

CREATE TABLE IF NOT EXISTS observability_logs.service_metrics
(
    timestamp    DateTime64(3),
    service_name LowCardinality(String),
    method       LowCardinality(String),
    path         String,
    status_code  UInt16,
    duration_ms  UInt32,
    user_id      String DEFAULT '',
    error_msg    String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY toDate(timestamp)
ORDER BY (service_name, timestamp)
TTL toDate(timestamp) + INTERVAL 30 DAY;

-- ─────────────────────────────────────────────
-- Error Events Table
-- Parsed from container_logs where log contains error keywords
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS observability_logs.error_events
(
    timestamp  DateTime64(3),
    service    LowCardinality(String),
    level      LowCardinality(String),
    message    String,
    stack      String DEFAULT '',
    container_id String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY toDate(timestamp)
ORDER BY (service, timestamp)
TTL toDate(timestamp) + INTERVAL 14 DAY;

-- ─────────────────────────────────────────────
-- Materialized view: auto-populate error_events
-- from container_logs where log looks like an error
-- ─────────────────────────────────────────────
CREATE MATERIALIZED VIEW IF NOT EXISTS observability_logs.mv_error_events
TO observability_logs.error_events
AS
SELECT
    timestamp,
    source AS service,
    multiIf(
        positionCaseInsensitive(log, 'FATAL') > 0, 'FATAL',
        positionCaseInsensitive(log, 'ERROR') > 0, 'ERROR',
        positionCaseInsensitive(log, 'WARN')  > 0, 'WARN',
        'INFO'
    ) AS level,
    log     AS message,
    ''      AS stack,
    container_id
FROM observability_logs.container_logs
WHERE positionCaseInsensitive(log, 'ERROR') > 0
   OR positionCaseInsensitive(log, 'FATAL') > 0
   OR positionCaseInsensitive(log, 'EXCEPTION') > 0;

-- ─────────────────────────────────────────────
-- Hourly rollup: logs per service (for charts)
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS observability_logs.hourly_log_counts
(
    hour         DateTime,
    service      LowCardinality(String),
    total_logs   UInt64,
    error_logs   UInt64
)
ENGINE = SummingMergeTree((total_logs, error_logs))
PARTITION BY toDate(hour)
ORDER BY (service, hour);

CREATE MATERIALIZED VIEW IF NOT EXISTS observability_logs.mv_hourly_log_counts
TO observability_logs.hourly_log_counts
AS
SELECT
    toStartOfHour(timestamp) AS hour,
    source AS service,
    1      AS total_logs,
    multiIf(
        positionCaseInsensitive(log, 'ERROR') > 0 OR
        positionCaseInsensitive(log, 'FATAL') > 0, 1, 0
    ) AS error_logs
FROM observability_logs.container_logs;
