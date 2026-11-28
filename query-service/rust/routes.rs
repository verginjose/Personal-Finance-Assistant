//! Assemble public API routes and private management routes.

use crate::app::App;
use crate::error::{Result, bad};
use crate::request::{Params, required, user};
use crate::{analytics, groups, ledger, planning};
use crate::{
    categories::CATEGORIES, health::health, observability::observe, replica::wait_replica,
};
use axum::response::IntoResponse;
use axum::response::Response;
use axum::{Json, Router};
use axum::{
    extract::{OriginalUri, Query, State},
    http::HeaderMap,
    middleware,
    routing::get,
};
use serde_json::json;

async fn handle(
    State(a): State<App>,
    h: HeaderMap,
    OriginalUri(uri): OriginalUri,
    Query(mut p): Query<Params>,
) -> Result<Response> {
    let path = uri.path();
    if path == "/upsert/groups/members" {
        let ids: Vec<_> = url::form_urlencoded::parse(uri.query().unwrap_or("").as_bytes())
            .filter(|(k, _)| k == "groupIds")
            .map(|(_, v)| v.into_owned())
            .collect();
        if !ids.is_empty() {
            p.insert("groupIds".into(), ids.join(","));
        }
    }

    if path.ends_with("/health") {
        return Ok(Json(json!({"status":"UP","service":"query-service"})).into_response());
    }
    let id = user(&h, &p)?;
    // Spring requires ?userId on every ledger/planning read, plus these
    // analytics reads; group reads are header-only. Mirror that contract so a
    // caller that omits the parameter gets the same 400 in both services.
    let needs_user_param = (path.starts_with("/upsert/") && !path.starts_with("/upsert/groups"))
        || path == "/analytics/transaction-entries"
        || path.starts_with("/analytics/transactions/")
        || path == "/analytics/budgets/trends"
        || path == "/analytics/ai-insights"
        || (path.starts_with("/analytics/goals/") && path.ends_with("/forecast"));
    if needs_user_param {
        required(&p, &["userId"])?;
    }
    if path == "/upsert/search" {
        required(&p, &["q"])?;
    }
    if path == "/analytics/transactions/income-by-category" {
        required(&p, &["categoryName", "startDate", "endDate"])?;
    }
    if path == "/analytics/transactions/by-type" {
        required(&p, &["transactionType", "startDate", "endDate"])?;
    }
    for key in ["type", "transactionType", "transactionFilter"] {
        if let Some(value) = p.get(key)
            && !["INCOME", "EXPENSE"].contains(&value.as_str())
        {
            return Err(bad("Invalid transaction type"));
        }
    }
    for key in ["category", "categoryName"] {
        if let Some(value) = p.get(key)
            && !CATEGORIES.contains(&value.as_str())
        {
            return Err(bad("Invalid category"));
        }
    }
    let mut scopes = vec![format!("user:{id}")];
    if let Some(group) = path
        .strip_prefix("/upsert/groups/")
        .and_then(|s| s.split('/').next())
        .and_then(|v| v.parse::<i64>().ok())
    {
        scopes.push(format!("group:{group}"));
    }
    if path == "/upsert/groups/members"
        && let Some(ids) = p.get("groupIds")
    {
        for id in ids.split(',').take(50) {
            if let Ok(id) = id.parse::<i64>() {
                scopes.push(format!("group:{id}"));
            }
        }
    }
    wait_replica(&a, &scopes).await?;
    let result = if path.starts_with("/upsert/groups") {
        groups::handle(&a, id, path, &p).await?
    } else if path.starts_with("/analytics") {
        analytics::handle(&a, id, path, &p).await?
    } else if path.starts_with("/upsert/goals")
        || path == "/upsert/budgets"
        || path == "/upsert/subscriptions"
    {
        planning::handle(&a, id, path, &p).await?
    } else {
        return ledger::handle(&a, id, path, &p).await;
    };
    Ok(Json(result).into_response())
}
pub(crate) fn routers(app: App) -> (Router, Router) {
    // The deployed manifest probes and scrapes the service port (8084), as the
    // Spring service did; keep the management listener for direct access too.
    let router = Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/health/readiness", get(health))
        .route(
            "/actuator/health/liveness",
            get(|| async { Json(json!({"status":"UP"})) }),
        )
        .route(
            "/actuator/prometheus",
            get(|State(a): State<App>| async move { a.metrics.render() }),
        )
        .route("/{*path}", get(handle))
        .layer(middleware::from_fn(observe))
        .layer(tower::limit::ConcurrencyLimitLayer::new(200))
        .with_state(app.clone());
    let management = Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/health/readiness", get(health))
        .route(
            "/actuator/health/liveness",
            get(|| async { Json(json!({"status":"UP"})) }),
        )
        .route(
            "/actuator/prometheus",
            get(|State(a): State<App>| async move { a.metrics.render() }),
        )
        .with_state(app);
    (router, management)
}
