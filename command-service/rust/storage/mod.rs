//! Uploads and private object downloads, owned by Command.

use crate::App;
use axum::{
    Json, Router,
    routing::{get, post},
};
use serde_json::json;

pub mod access;
mod backend;
pub mod backpressure;
pub mod broker;
mod download;
mod object_store;
mod upload;
pub mod validation;

pub(crate) use backend::Storage;

fn storage(app: &App) -> &Storage {
    &app.storage
}
pub(crate) fn routes() -> Router<App> {
    Router::new()
        .route("/upsert/storage-access", get(access::storage_access))
        .route("/api/receipt/upload/{id}", post(upload::upload))
        .route("/api/profile/upload", post(upload::upload))
        .route("/api/bill/process/{id}", post(upload::upload))
        .route("/api/bill/batch-process/{id}", post(upload::upload))
        .route("/api/storage/{bucket}/{*name}", get(download::download))
        .route(
            "/api/bill/health",
            get(|| async { Json(json!({"status":"UP"})) }),
        )
}
