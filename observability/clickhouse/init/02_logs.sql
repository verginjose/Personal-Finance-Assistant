CREATE TABLE IF NOT EXISTS logs (
    timestamp DateTime64(3, 'UTC'),
    service LowCardinality(String),
    level LowCardinality(String),
    trace_id String,
    span_id String,
    message String,
    stack_trace String,
    container String,
    compose_project LowCardinality(String),

    INDEX idx_message message TYPE tokenbf_v1(32768, 3, 0) GRANULARITY 1,
    INDEX idx_trace trace_id TYPE minmax GRANULARITY 1
    )
    ENGINE = MergeTree()
    ORDER BY (service, level, timestamp)