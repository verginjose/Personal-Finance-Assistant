//! Assemble public API routes and private management routes.

use crate::{
    App, JsonBody, Params, Result, entries, forbidden, groups,
    health::{health, health_service, live, metrics_handler},
    notifications,
    observability::observe,
    planning,
    request::{header_user, query_user, same_user},
    storage,
};
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    middleware,
    response::{IntoResponse, Response},
    routing as rt,
};
use serde_json::{Value, json};

async fn create(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<entries::EntryRequest>,
) -> Result<Response> {
    let uid = same_user(&h, body.user_id)?;
    let key = h.get("x-idempotency-key").and_then(|v| v.to_str().ok());
    let value = entries::create(&a, uid, body, key).await?;
    Ok((StatusCode::CREATED, Json(value)).into_response())
}

async fn create_bulk(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<Vec<entries::EntryRequest>>,
) -> Result<Response> {
    let uid = header_user(&h)?;
    if body.is_empty() {
        return Ok(Json(Vec::<Value>::new()).into_response());
    }
    for item in &body {
        if item.user_id != Some(uid) {
            return Err(forbidden("Access denied: User ID mismatch"));
        }
    }
    let value = entries::create_bulk(&a, uid, body).await?;
    Ok((StatusCode::CREATED, Json(value)).into_response())
}

async fn update(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<entries::EntryRequest>,
) -> Result<Response> {
    let uid = same_user(&h, body.user_id)?;
    Ok(Json(entries::update(&a, uid, body).await?).into_response())
}

#[derive(serde::Deserialize)]
struct PatchAmount {
    amount: Value,
}

async fn patch_amount(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
    JsonBody(body): JsonBody<PatchAmount>,
) -> Result<Response> {
    let uid = query_user(&h, &q)?;
    Ok(Json(entries::patch_amount(&a, id, uid, body.amount).await?).into_response())
}

async fn delete_entry(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let uid = query_user(&h, &q)?;
    entries::delete(&a, id, uid).await?;
    Ok(Json(json!({"message":"Entry deleted successfully"})).into_response())
}

async fn bulk_delete(
    State(a): State<App>,
    h: HeaderMap,
    Query(q): Query<Params>,
    JsonBody(ids): JsonBody<Vec<i64>>,
) -> Result<Response> {
    let uid = query_user(&h, &q)?;
    for id in &ids {
        entries::delete(&a, *id, uid).await?;
    }
    Ok(Json(json!({"message":"Entries deleted successfully","count":ids.len()})).into_response())
}

pub(crate) fn routers(app: App) -> (Router, Router) {
    let router = Router::new()
        .merge(storage::routes().layer(axum::extract::DefaultBodyLimit::max(12 * 1024 * 1024)))
        .merge(groups::routes())
        .merge(planning::routes())
        .merge(notifications::routes())
        .route("/upsert/create", rt::post(create))
        .route("/upsert/create/bulk", rt::post(create_bulk))
        .route("/upsert/update", rt::put(update))
        .route("/upsert/entries/{id}/amount", rt::patch(patch_amount))
        .route("/upsert/delete/{id}", rt::delete(delete_entry))
        .route("/upsert/delete/bulk", rt::post(bulk_delete))
        .route("/upsert/health", rt::get(health_service))
        .route("/actuator/health", rt::get(health))
        .route("/actuator/health/readiness", rt::get(health))
        .route("/actuator/health/liveness", rt::get(live))
        .route("/actuator/prometheus", rt::get(metrics_handler))
        .layer(middleware::from_fn(observe))
        .layer(tower::limit::ConcurrencyLimitLayer::new(200))
        .with_state(app.clone());

    let management = Router::new()
        .route("/actuator/health", rt::get(health))
        .route("/actuator/health/readiness", rt::get(health))
        .route("/actuator/health/liveness", rt::get(live))
        .route("/actuator/prometheus", rt::get(metrics_handler))
        .with_state(app);

    (router, management)
}
