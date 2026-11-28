//! Ledger read routes. Entry reads, reporting, currency conversion and CSV export are separate modules.

use crate::app::App;
use crate::error::{Error, Result, bad};
use crate::request::Params;
use axum::Json;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use uuid::Uuid;
mod currency;
mod entries;
mod export;
mod reporting;

pub(crate) use currency::{decimal, money};
pub(crate) use entries::{analytics_entries, entries};
use export::export;
pub(crate) use reporting::{reporting, summary};

pub async fn handle(a: &App, id: Uuid, path: &str, p: &Params) -> Result<Response> {
    let v = if path == "/upsert/summary" {
        summary(a, id, p).await?
    } else if path == "/upsert/entries" || path == "/upsert/search" {
        entries(a, id, p, None, false, None).await?
    } else if path == "/upsert/recurring" {
        entries(a, id, p, None, true, None).await?
    } else if path == "/upsert/entries/export" {
        return Ok(export(a, id));
    } else if let Some(v) = path.strip_prefix("/upsert/entries/") {
        entries(
            a,
            id,
            p,
            Some(v.parse().map_err(|_| bad("Invalid entry ID"))?),
            false,
            None,
        )
        .await?
    } else {
        return Err(Error(StatusCode::NOT_FOUND, "Not found".into()));
    };
    Ok(Json(v).into_response())
}
