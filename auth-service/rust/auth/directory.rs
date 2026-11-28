//! Authenticated user search and lookup for sharing features.

use super::db;
use super::models::User;
use super::tokens::identity;
use crate::app::App;
use crate::error::{Error, Result};
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::{
    extract::{Path, Query},
    http::HeaderMap,
};
use serde_json::Value;
use serde_json::json;
use uuid::Uuid;

pub(crate) fn user_result(user: User, email: &str) -> Value {
    json!({"userId":user.id,"username":user.username,"email":if user.email.eq_ignore_ascii_case(email) {Some(user.email)} else {None},"profilePicture":user.profile_picture})
}
pub(crate) async fn search(
    State(app): State<App>,
    headers: HeaderMap,
    Query(q): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let query = q.get("q").map(|v| v.trim()).unwrap_or("");
    if query.is_empty() {
        return Ok(Json(json!([])));
    }
    let limit = q
        .get("limit")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(10)
        .clamp(1, 20);
    let page = q
        .get("page")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(0)
        .max(0);
    let users = sqlx::query_as::<_,User>("SELECT * FROM auth.users WHERE username ILIKE $1 OR email ILIKE $1 ORDER BY id LIMIT $2 OFFSET $3").bind(format!("%{query}%")).bind(limit).bind(page.saturating_mul(limit)).fetch_all(db(&app)).await.map_err(Error::internal)?;
    Ok(Json(Value::Array(
        users.into_iter().map(|u| user_result(u, &c.sub)).collect(),
    )))
}
pub(crate) async fn get_user(
    State(app): State<App>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let u = sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE id=$1")
        .bind(id)
        .fetch_optional(db(&app))
        .await
        .map_err(Error::internal)?
        .ok_or_else(|| Error(StatusCode::NOT_FOUND, "User not found".into()))?;
    Ok(Json(user_result(u, &c.sub)))
}
pub(crate) async fn bulk(
    State(app): State<App>,
    headers: HeaderMap,
    Json(ids): Json<Vec<Uuid>>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    if ids.len() > 1000 {
        return Err(Error::bad("Too many users"));
    }
    let users = sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE id=ANY($1)")
        .bind(ids)
        .fetch_all(db(&app))
        .await
        .map_err(Error::internal)?;
    Ok(Json(Value::Array(
        users.into_iter().map(|u| user_result(u, &c.sub)).collect(),
    )))
}
