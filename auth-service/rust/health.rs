//! Liveness, dependency readiness and metrics endpoints.

use crate::{App, Error, Result};
use axum::{Json, extract::State};
use serde_json::json;

pub(crate) async fn liveness() -> Json<serde_json::Value> {
    Json(json!({"status":"UP"}))
}
pub(crate) async fn readiness(State(app): State<App>) -> Result<Json<serde_json::Value>> {
    redis::cmd("PING")
        .query_async::<String>(&mut app.redis.clone())
        .await
        .map_err(Error::internal)?;
    sqlx::query("SELECT 1")
        .execute(&app.db)
        .await
        .map_err(Error::internal)?;
    Ok(Json(json!({"status":"UP"})))
}
pub(crate) async fn metrics_handler(State(app): State<App>) -> String {
    app.metrics.render()
}
