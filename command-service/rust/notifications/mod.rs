//! Notification routes. Inbox reads, SSE delivery and OCR callbacks have separate modules.

use crate::{App, Result, bad};
use axum::{Router, http::HeaderMap, routing as rt};
use uuid::Uuid;

mod events;
mod inbox;
mod ocr;

pub(crate) use events::{BatchNotification, Events, send_batch, send_fields};

pub fn routes() -> Router<App> {
    Router::new()
        .route(
            "/notifications",
            rt::get(inbox::inbox).delete(inbox::clear_all),
        )
        .route(
            "/notifications/stream",
            rt::get(inbox::stream_notifications),
        )
        .route("/notifications/unread-count", rt::get(inbox::unread_count))
        .route("/notifications/{id}/read", rt::post(inbox::mark_read))
        .route("/notifications/read-all", rt::post(inbox::mark_all_read))
        .route("/internal/ocr-complete", rt::post(ocr::ocr_complete))
        .route(
            "/internal/batch-ocr-complete",
            rt::post(ocr::batch_ocr_complete),
        )
}

fn user_header(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("Invalid user context"))?;
    Uuid::parse_str(raw).map_err(|_| bad("Invalid user context"))
}
