//! Liveness, dependency readiness and metrics endpoints.

use crate::{App, Result};
use axum::{Json, extract::State};
use serde_json::{Value, json};

pub(crate) async fn health_service() -> Json<Value> {
    Json(json!({"status":"UP","service":"upsert-service"}))
}
pub(crate) async fn health(State(a): State<App>) -> Result<Json<Value>> {
    sqlx::query("SELECT 1").execute(&a.db).await?;
    a.storage.ready().await?;
    Ok(Json(json!({"status":"UP"})))
}
pub(crate) async fn live() -> Json<Value> {
    Json(json!({"status":"UP"}))
}
pub(crate) async fn metrics_handler(State(a): State<App>) -> String {
    a.metrics.render()
}
