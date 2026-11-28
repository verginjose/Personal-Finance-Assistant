//! Liveness, dependency readiness and metrics endpoints.

use crate::{app::App, error::Result};
use axum::{Json, extract::State};
use serde_json::{Value, json};

pub(crate) async fn health(State(a): State<App>) -> Result<Json<Value>> {
    sqlx::query("SELECT 1").execute(&a.db).await?;
    Ok(Json(json!({"status":"UP"})))
}
