//! Shared application state cloned cheaply into request handlers.

use serde_json::Value;
use sqlx::PgPool;
use std::{collections::HashMap, sync::Arc};

#[derive(Clone)]
pub(crate) struct App {
    pub(crate) db: PgPool,
    pub(crate) http: reqwest::Client,
    pub(crate) fx: Arc<tokio::sync::Mutex<HashMap<String, (std::time::Instant, Value)>>>,
    pub(crate) fx_locks: Arc<tokio::sync::Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    pub(crate) metrics: metrics_exporter_prometheus::PrometheusHandle,
    pub(crate) redis: redis::aio::ConnectionManager,
    pub(crate) advice: Arc<tokio::sync::Mutex<HashMap<String, (std::time::Instant, Value)>>>,
}
