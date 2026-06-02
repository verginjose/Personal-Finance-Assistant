CREATE TABLE IF NOT EXISTS logs (
                                    timestamp DateTime64(3, 'UTC'),
    service LowCardinality(String),
    level LowCardinality(String),
    message String,
    container String,
    compose_project LowCardinality(String),

    INDEX idx_message message TYPE tokenbf_v1(32768, 3, 0) GRANULARITY 1
    )
    ENGINE = MergeTree()
    ORDER BY (service, level, timestamp)