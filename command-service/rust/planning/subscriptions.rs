use super::{parse_amount, query_user};
use crate::{App, JsonBody, Params, Result, bad, forbidden, money, not_found};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use chrono::{NaiveDate, Utc};
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::{Value, json};
use uuid::Uuid;

use super::PERIODS;
use crate::subscriptions;

const SUB_COLS: &str = "id,user_id,name,amount,currency,period::text AS period,\
next_charge_date,days_until_charge,active,period_locked";
#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
struct SubscriptionRow {
    id: i64,
    user_id: Uuid,
    name: String,
    amount: Decimal,
    currency: String,
    period: String,
    next_charge_date: Option<NaiveDate>,
    days_until_charge: i32,
    active: bool,
    period_locked: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SubscriptionUpdate {
    name: Option<String>,
    amount: Option<Value>,
    period: Option<String>,
}

fn subscription_json(s: &SubscriptionRow) -> Value {
    let alert = if !s.active {
        "Inactive".to_string()
    } else if s.days_until_charge == 0 {
        "⚠️ Charging today!".to_string()
    } else if s.days_until_charge <= 3 {
        format!("⚠️ Charge in {} day(s)", s.days_until_charge)
    } else if s.days_until_charge <= 7 {
        format!("📅 Charge in {} days", s.days_until_charge)
    } else {
        format!(
            "Next charge: {}",
            s.next_charge_date
                .map(|d| d.to_string())
                .unwrap_or_default()
        )
    };
    json!({
        "id": s.id,
        "name": s.name,
        "amount": money(s.amount),
        "currency": s.currency,
        "period": s.period,
        "nextChargeDate": s.next_charge_date.map(|d| d.to_string()),
        "daysUntilCharge": s.days_until_charge,
        "active": s.active,
        "alert": alert,
    })
}

pub(super) async fn update_subscription(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
    JsonBody(body): JsonBody<SubscriptionUpdate>,
) -> Result<Response> {
    let user = query_user(&h, &q)?;
    let name = body.name.clone().unwrap_or_default().trim().to_string();
    if name.is_empty() {
        return Err(bad("Name is required"));
    }
    if name.chars().count() > 100 {
        return Err(bad("Name cannot exceed 100 characters"));
    }
    let amount = parse_amount(
        body.amount
            .as_ref()
            .ok_or_else(|| bad("Amount is required"))?,
    )?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than 0"));
    }
    let period = body
        .period
        .clone()
        .map(|p| p.to_uppercase())
        .ok_or_else(|| bad("Recurring period is required"))?;
    if !PERIODS.contains(&period.as_str()) {
        return Err(bad("Invalid period"));
    }
    let sub = sqlx::query_as::<_, SubscriptionRow>(&format!(
        "SELECT {SUB_COLS} FROM finance.subscriptions WHERE id=$1"
    ))
    .bind(id)
    .fetch_optional(&a.db)
    .await?
    .ok_or_else(|| not_found(&format!("Subscription not found: {id}")))?;
    if sub.user_id != user {
        return Err(forbidden("Not authorized to modify this subscription"));
    }
    let mut next = sub.next_charge_date;
    let mut days = sub.days_until_charge;
    let mut locked = sub.period_locked;
    if sub.period != period {
        let today = Utc::now().date_naive();
        let last = sub
            .next_charge_date
            .map(|n| subscriptions::previous_charge_date(n, &sub.period))
            .unwrap_or(today);
        next = Some(subscriptions::calculate_next_charge(last, &period));
        days = next
            .map(|n| (n - today).num_days().max(0) as i32)
            .unwrap_or(0);
        locked = true;
    }
    let updated = sqlx::query_as::<_, SubscriptionRow>(&format!(
        "UPDATE finance.subscriptions SET name=$2, amount=$3, period=$4, \
         next_charge_date=$5, days_until_charge=$6, period_locked=$7 \
         WHERE id=$1 RETURNING {SUB_COLS}"
    ))
    .bind(id)
    .bind(&name)
    .bind(amount)
    .bind(&period)
    .bind(next)
    .bind(days)
    .bind(locked)
    .fetch_one(&a.db)
    .await?;
    tracing::info!(id, %user, "subscription updated");
    Ok(Json(subscription_json(&updated)).into_response())
}

pub(super) async fn deactivate_subscription(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = query_user(&h, &q)?;
    let owner: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM finance.subscriptions WHERE id=$1")
            .bind(id)
            .fetch_optional(&a.db)
            .await?;
    let owner = owner.ok_or_else(|| not_found(&format!("Subscription not found: {id}")))?;
    if owner != user {
        return Err(forbidden("Not authorized to modify this subscription"));
    }
    sqlx::query("UPDATE finance.subscriptions SET active=false WHERE id=$1")
        .bind(id)
        .execute(&a.db)
        .await?;
    tracing::info!(id, %user, "subscription deactivated");
    Ok(StatusCode::OK.into_response())
}
