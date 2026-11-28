//! Goal, budget and subscription write routes, with shared request validation.

use crate::{App, Params, Result, bad, forbidden};
use axum::{Router, http::HeaderMap, routing as rt};
use rust_decimal::Decimal;
use serde_json::Value;
use std::str::FromStr;
use uuid::Uuid;

mod budgets;
mod goals;
mod subscriptions;

const PERIODS: &[&str] = &["DAILY", "WEEKLY", "MONTHLY", "YEARLY"];

pub fn routes() -> Router<App> {
    Router::new()
        .route("/upsert/goals", rt::post(goals::create_goal))
        .route(
            "/upsert/goals/{id}/contribute",
            rt::patch(goals::contribute),
        )
        .route("/upsert/goals/{id}", rt::delete(goals::delete_goal))
        .route("/upsert/budgets", rt::post(budgets::create_budget))
        .route("/upsert/budgets/{id}", rt::delete(budgets::delete_budget))
        .route(
            "/upsert/subscriptions/{id}",
            rt::put(subscriptions::update_subscription),
        )
        .route(
            "/upsert/subscriptions/{id}/deactivate",
            rt::delete(subscriptions::deactivate_subscription),
        )
}

fn actor(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("Invalid user context"))?;
    Uuid::parse_str(raw).map_err(|_| bad("Invalid user context"))
}

fn same_user(h: &HeaderMap, body_user: Option<Uuid>) -> Result<Uuid> {
    let uid = body_user.ok_or_else(|| bad("userId is required"))?;
    if actor(h)? != uid {
        return Err(forbidden("User ID mismatch"));
    }
    Ok(uid)
}

fn query_user(h: &HeaderMap, q: &Params) -> Result<Uuid> {
    let uid = actor(h)?;
    let raw = q
        .get("userId")
        .ok_or_else(|| bad("Required request parameter 'userId' is missing"))?;
    let parsed = Uuid::parse_str(raw).map_err(|_| bad("Invalid userId"))?;
    if parsed != uid {
        return Err(forbidden("User ID mismatch"));
    }
    Ok(uid)
}

fn parse_amount(v: &Value) -> Result<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).map_err(|_| bad("Invalid amount"))
}
