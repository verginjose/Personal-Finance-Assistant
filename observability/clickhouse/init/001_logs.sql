CREATE DATABASE IF NOT EXISTS observability_logs;

CREATE TABLE IF NOT EXISTS observability_logs.container_logs
(
    timestamp DateTime64(3) DEFAULT now64(),
    log       String,
    source    String
)
ENGINE = MergeTree
PARTITION BY toDate(timestamp)
ORDER BY (timestamp, source);
