use super::events::send_raw;
use crate::{App, Error, Result, bad, env};
use axum::{
    Json,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use serde_json::{Value, json};
use uuid::Uuid;

async fn internal_authorized(a: &App, h: &HeaderMap) -> Result<()> {
    let configured = env("INTERNAL_API_TOKEN", "");
    if configured.is_empty() {
        tracing::error!("INTERNAL_API_TOKEN is not configured; refusing /internal request");
        return Err(Error(StatusCode::SERVICE_UNAVAILABLE, String::new()));
    }
    let presented = h
        .get("x-internal-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if presented != configured {
        tracing::warn!("rejected /internal request with wrong token");
        return Err(Error(StatusCode::UNAUTHORIZED, String::new()));
    }
    let _ = a;
    Ok(())
}

pub(super) async fn ocr_complete(
    State(a): State<App>,
    h: HeaderMap,
    Json(mut body): Json<Value>,
) -> Result<Response> {
    internal_authorized(&a, &h).await?;
    handle_ocr(&a, &mut body, false).await
}

pub(super) async fn batch_ocr_complete(
    State(a): State<App>,
    h: HeaderMap,
    Json(mut body): Json<Value>,
) -> Result<Response> {
    internal_authorized(&a, &h).await?;
    handle_ocr(&a, &mut body, true).await
}

async fn handle_ocr(a: &App, body: &mut Value, batch: bool) -> Result<Response> {
    let Some(user_str) = body["userId"].as_str() else {
        tracing::warn!("received OCR completion event with no userId");
        return Err(bad("userId is required"));
    };
    let user = Uuid::parse_str(user_str).map_err(|_| bad("Invalid userId"))?;
    if batch {
        body["type"] = json!("batch-ocr-completed");
    }
    let status = body["status"].as_str().unwrap_or("INFO");
    tracing::info!(%user, status, batch, "OCR completion notification");
    send_raw(a, user, body.clone()).await;
    Ok(StatusCode::OK.into_response())
}
