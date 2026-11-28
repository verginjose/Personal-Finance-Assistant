//! Upload identity and receipt ownership checks shared by storage endpoints.

use crate::{App, Params, Result, request::header_user};
use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use sqlx::PgPool;
use uuid::Uuid;

fn belongs_to(url: &str, user: Uuid) -> bool {
    let prefix = format!("/api/storage/receipts/{user}");
    url.starts_with(&format!("{prefix}/")) || url.starts_with(&format!("{prefix}-batch-"))
}

pub(super) async fn storage_access(
    State(app): State<App>,
    headers: HeaderMap,
    Query(params): Query<Params>,
) -> Result<Response> {
    // Retain the legacy access endpoint’s 400 for missing/invalid context.
    let user = header_user(&headers).map_err(|_| crate::bad("Invalid user context"))?;
    let url = params.get("url").map(String::as_str).unwrap_or("");
    let status = if can_read_receipt(&app.db, user, url).await? {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    };
    Ok(status.into_response())
}

pub(super) async fn can_read_receipt(db: &PgPool, user: Uuid, url: &str) -> Result<bool> {
    if belongs_to(url, user) {
        return Ok(true);
    }
    let owned_entry: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM finance.transaction_entries \
         WHERE receipt_url=$1 AND user_id=$2 AND deleted_at IS NULL)",
    )
    .bind(url)
    .bind(user)
    .fetch_one(db)
    .await?;
    if owned_entry {
        return Ok(true);
    }
    let shared: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM groups.shared_expenses e \
         JOIN groups.group_members m ON m.group_id=e.group_id \
         WHERE e.receipt_url=$1 AND m.user_id=$2 AND m.status='ACCEPTED')",
    )
    .bind(url)
    .bind(user)
    .fetch_one(db)
    .await?;
    Ok(shared)
}

/// Use only behind the edge router: it strips client identity headers and
/// replaces them with the identity returned by Auth's ForwardAuth check.
pub fn upload_user(headers: &HeaderMap, path: &str) -> Result<Uuid> {
    let user = header_user(headers)?;
    if path != "/api/profile/upload"
        && path
            .rsplit('/')
            .next()
            .is_none_or(|id| !id.eq_ignore_ascii_case(&user.to_string()))
    {
        return Err(crate::forbidden("Access denied: user mismatch"));
    }
    Ok(user)
}
