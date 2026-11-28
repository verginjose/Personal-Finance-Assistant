//! Shared application state cloned cheaply into request handlers.

use crate::{notifications, storage::Storage};
use sqlx::PgPool;
use std::sync::Arc;

#[derive(Clone)]
pub struct App {
    pub storage: Arc<Storage>,
    pub db: PgPool,
    pub redis: redis::aio::ConnectionManager,
    pub metrics: metrics_exporter_prometheus::PrometheusHandle,
    pub events: notifications::Events,
}
