//! Shared application state cloned cheaply into request handlers.

use redis::aio::ConnectionManager;
use std::sync::Arc;
use tokio::sync::Semaphore;

#[derive(Clone)]
pub(crate) struct App {
    pub(crate) redis: ConnectionManager,
    pub(crate) db: sqlx::PgPool,
    pub(crate) key: Vec<u8>,
    pub(crate) hash_slots: Arc<Semaphore>,
    pub(crate) metrics: metrics_exporter_prometheus::PrometheusHandle,
    // Load-test switch: when set, the per-IP/per-user token buckets in
    // `/internal/verify` are skipped so a capacity test measures the services
    // rather than the limiter. Off unless RATE_LIMIT_DISABLED=true.
    pub(crate) rate_limit_disabled: bool,
}
