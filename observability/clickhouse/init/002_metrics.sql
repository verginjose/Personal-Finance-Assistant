-- ─────────────────────────────────────────────
-- Service Request Metrics Table
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
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS observability_logs.error_events
(
    timestamp  DateTime64(3),
    service    LowCardinality(String),
    level      LowCardinality(String),
    message    String,
    stack      String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY toDate(timestamp)
ORDER BY (service, timestamp)
TTL toDate(timestamp) + INTERVAL 14 DAY;
