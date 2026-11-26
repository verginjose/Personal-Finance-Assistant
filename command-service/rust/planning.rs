use super::*;
use crate::{bad, cache, forbidden, money, not_found, subscriptions, ts};
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing as rt,
};
use chrono::{Datelike, Days, NaiveDate, NaiveDateTime, Utc};
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde::Deserialize;
use serde_json::{Value, json};
use std::str::FromStr;
use uuid::Uuid;

const GOAL_COLS: &str = "id,user_id,name,target_amount,saved_amount,currency,description,deadline,\
created_at,completed_at,active,priority::text AS priority";
const BUDGET_COLS: &str = "id,user_id,expense_category::text AS expense_category,\
budget_amount,period::text AS period,currency,active,carry_forward,custom_start_date,\
custom_end_date,created_at";
const SUB_COLS: &str = "id,user_id,name,amount,currency,period::text AS period,\
next_charge_date,days_until_charge,active,period_locked";
const PERIODS: &[&str] = &["DAILY", "WEEKLY", "MONTHLY", "YEARLY"];
const PRIORITIES: &[&str] = &["CRITICAL", "HIGH", "MEDIUM", "LOW"];

const REPORTABLE: &str = "coalesce(transaction_kind,'STANDARD') NOT IN ('TRANSFER','INVESTMENT') \
AND category NOT IN ('SETTLEMENT','CREDIT_CARD_PAYMENT','MUTUAL_FUNDS_SIP','STOCKS_AND_TRADING',\
'CRYPTO','EMERGENCY_FUND','FIXED_DEPOSIT','GOAL','OWN_ACCOUNT_TRANSFER','BORROWING','LENDING',\
'LOAN_PRINCIPAL','INVESTMENT_WITHDRAWAL')";

#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
struct GoalRow {
    id: i64,
    user_id: Uuid,
    name: String,
    target_amount: Decimal,
    saved_amount: Decimal,
    currency: String,
    description: Option<String>,
    deadline: Option<NaiveDate>,
    created_at: NaiveDateTime,
    completed_at: Option<NaiveDateTime>,
    active: bool,
    priority: String,
}

#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
struct BudgetRow {
    id: i64,
    user_id: Uuid,
    expense_category: String,
    budget_amount: Decimal,
    period: String,
    currency: String,
    active: bool,
    carry_forward: bool,
    custom_start_date: Option<NaiveDate>,
    custom_end_date: Option<NaiveDate>,
    created_at: NaiveDateTime,
}

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
struct GoalRequest {
    user_id: Option<Uuid>,
    name: Option<String>,
    target_amount: Option<Value>,
    currency: Option<String>,
    description: Option<String>,
    deadline: Option<String>,
    priority: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContributionRequest {
    amount: Option<Value>,
    description: Option<String>,
    created_at: Option<String>,
    currency: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BudgetRequest {
    user_id: Option<Uuid>,
    expense_category: Option<String>,
    budget_amount: Option<Value>,
    period: Option<String>,
    currency: Option<String>,
    carry_forward: Option<bool>,
    custom_start_date: Option<String>,
    custom_end_date: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SubscriptionUpdate {
    name: Option<String>,
    amount: Option<Value>,
    period: Option<String>,
}

pub fn routes() -> Router<App> {
    Router::new()
        .route("/upsert/goals", rt::post(create_goal))
        .route(
            "/upsert/goals/{id}/contribute",
            rt::patch(contribute),
        )
        .route("/upsert/goals/{id}", rt::delete(delete_goal))
        .route("/upsert/budgets", rt::post(create_budget))
        .route("/upsert/budgets/{id}", rt::delete(delete_budget))
        .route("/upsert/subscriptions/{id}", rt::put(update_subscription))
        .route(
            "/upsert/subscriptions/{id}/deactivate",
            rt::delete(deactivate_subscription),
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

fn goal_json(g: &GoalRow) -> Value {
    let progress = if g.target_amount == Decimal::ZERO {
        0.0
    } else {
        (g.saved_amount.to_f64().unwrap_or(0.0) / g.target_amount.to_f64().unwrap_or(1.0)
            * 100.0)
            .min(100.0)
    };
    json!({
        "id": g.id,
        "name": g.name,
        "targetAmount": money(g.target_amount),
        "savedAmount": money(g.saved_amount),
        "progressPercentage": progress,
        "currency": g.currency,
        "description": g.description,
        "deadline": g.deadline.map(|d| d.to_string()),
        "completed": g.saved_amount >= g.target_amount,
        "createdAt": ts(&g.created_at),
        "completedAt": g.completed_at.as_ref().map(ts),
        "priority": g.priority,
    })
}

async fn create_goal(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<GoalRequest>,
) -> Result<Response> {
    let user = same_user(&h, body.user_id)?;
    let name = body.name.clone().unwrap_or_default().trim().to_string();
    if name.is_empty() {
        return Err(bad("Name is required"));
    }
    let target = parse_amount(
        body.target_amount
            .as_ref()
            .ok_or_else(|| bad("targetAmount is required"))?,
    )?;
    if target <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    let currency = body.currency.clone().unwrap_or_default();
    if currency.len() != 3 {
        return Err(bad("Currency must be 3 characters"));
    }
    let priority = body.priority.clone().unwrap_or_else(|| "MEDIUM".into());
    if !PRIORITIES.contains(&priority.as_str()) {
        return Err(bad("Invalid priority"));
    }
    let row = sqlx::query_as::<_, GoalRow>(&format!(
        "INSERT INTO finance.savings_goals \
         (user_id,name,target_amount,saved_amount,currency,description,deadline,created_at,\
          completed_at,active,priority) \
         VALUES ($1,$2,$3,0,$4,$5,$6,now(),NULL,true,$7) RETURNING {GOAL_COLS}"
    ))
    .bind(user)
    .bind(&name)
    .bind(target)
    .bind(&currency)
    .bind(&body.description)
    .bind(
        body.deadline
            .as_deref()
            .filter(|v| !v.trim().is_empty())
            .map(crate::date)
            .transpose()?,
    )
    .bind(&priority)
    .fetch_one(&a.db)
    .await?;
    tracing::info!(id = row.id, %user, "goal created");
    Ok((StatusCode::CREATED, Json(goal_json(&row))).into_response())
}

async fn contribute(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
    JsonBody(body): JsonBody<ContributionRequest>,
) -> Result<Response> {
    let user = query_user(&h, &q)?;
    let amount = parse_amount(
        body.amount
            .as_ref()
            .ok_or_else(|| bad("Amount is required"))?,
    )?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be positive"));
    }
    let mut tx = a.db.begin().await?;
    let goal = sqlx::query_as::<_, GoalRow>(&format!(
        "SELECT {GOAL_COLS} FROM finance.savings_goals WHERE id=$1 FOR UPDATE"
    ))
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Goal not found: {id}")))?;
    if goal.user_id != user {
        return Err(forbidden("Not authorized"));
    }
    let currency = body.currency.clone().unwrap_or_else(|| goal.currency.clone());
    let entry_name = format!("Contribution to {}", goal.name);
    let entry_id: i64 = sqlx::query_scalar(
        "INSERT INTO finance.transaction_entries \
         (user_id,name,amount,type,category,currency,description,created_at,updated_at,\
          recurring,transaction_kind) \
         VALUES ($1,$2,$3,'EXPENSE','GOAL',$4,$5,COALESCE($6::timestamp,now()),now(),false,'STANDARD') \
         RETURNING id",
    )
    .bind(user)
    .bind(&entry_name)
    .bind(amount)
    .bind(&currency)
    .bind(&body.description)
    .bind(
        body.created_at
            .as_deref()
            .filter(|v| !v.trim().is_empty())
            .map(crate::datetime)
            .transpose()?,
    )
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO finance.transaction_goal_allocations \
         (transaction_id,goal_id,amount,created_at) VALUES ($1,$2,$3,now())",
    )
    .bind(entry_id)
    .bind(id)
    .bind(amount)
    .execute(&mut *tx)
    .await?;
    let updated = sqlx::query_as::<_, GoalRow>(&format!(
        "UPDATE finance.savings_goals SET saved_amount=saved_amount+$2, \
         completed_at=CASE WHEN saved_amount+$2>=target_amount AND completed_at IS NULL \
         THEN now() ELSE completed_at END WHERE id=$1 RETURNING {GOAL_COLS}"
    ))
    .bind(id)
    .bind(amount)
    .fetch_one(&mut *tx)
    .await?;
    tx.commit().await?;
    cache::evict_users(&a, &[user]).await;
    tracing::info!(goal = id, %user, "goal contribution");
    Ok(Json(goal_json(&updated)).into_response())
}

async fn delete_goal(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = query_user(&h, &q)?;
    let owner: Option<Uuid> = sqlx::query_scalar("SELECT user_id FROM finance.savings_goals WHERE id=$1")
        .bind(id)
        .fetch_optional(&a.db)
        .await?;
    let owner = owner.ok_or_else(|| not_found(&format!("Goal not found: {id}")))?;
    if owner != user {
        return Err(forbidden("Not authorized"));
    }
    sqlx::query("UPDATE finance.savings_goals SET active=false WHERE id=$1")
        .bind(id)
        .execute(&a.db)
        .await?;
    Ok(StatusCode::OK.into_response())
}

fn period_range(budget: &BudgetRow) -> (NaiveDateTime, NaiveDateTime) {
    if let (Some(start), Some(end)) = (budget.custom_start_date, budget.custom_end_date) {
        return (
            start.and_hms_opt(0, 0, 0).unwrap(),
            end.and_hms_opt(23, 59, 59).unwrap(),
        );
    }
    let today = Utc::now().date_naive();
    let start = if budget.period == "WEEKLY" {
        let offset = today.weekday().num_days_from_monday();
        today - Days::new(offset as u64)
    } else {
        NaiveDate::from_ymd_opt(today.year(), today.month(), 1).unwrap_or(today)
    };
    (start.and_hms_opt(0, 0, 0).unwrap(), Utc::now().naive_utc())
}

async fn spent_for(
    a: &App,
    user: Uuid,
    category: &str,
    start: NaiveDateTime,
    end: NaiveDateTime,
) -> Result<Decimal> {
    let sql = format!(
        "SELECT COALESCE(SUM(CASE WHEN {REPORTABLE} THEN \
         CASE WHEN transaction_kind IN ('REFUND','REIMBURSEMENT') THEN -amount ELSE amount END \
         ELSE 0 END),0) FROM finance.transaction_entries \
         WHERE user_id=$1 AND deleted_at IS NULL AND category=$2 \
           AND (type='EXPENSE' OR transaction_kind IN ('REFUND','REIMBURSEMENT')) \
           AND created_at BETWEEN $3 AND $4"
    );
    Ok(sqlx::query_scalar(&sql)
        .bind(user)
        .bind(category)
        .bind(start)
        .bind(end)
        .fetch_one(&a.db)
        .await?)
}

fn budget_json(b: &BudgetRow, spent: Decimal) -> Value {
    let pct = if b.budget_amount > Decimal::ZERO {
        (spent / b.budget_amount * Decimal::from(100))
            .round_dp(4)
            .to_f64()
            .unwrap_or(0.0)
    } else {
        0.0
    };
    let status = if pct >= 100.0 {
        "EXCEEDED"
    } else if pct >= 80.0 {
        "WARNING"
    } else {
        "SAFE"
    };
    json!({
        "budgetId": b.id,
        "expenseCategory": b.expense_category,
        "budgetAmount": money(b.budget_amount),
        "spentAmount": money(spent),
        "utilizationPercentage": pct.min(200.0),
        "period": b.period,
        "currency": b.currency,
        "status": status,
    })
}

async fn create_budget(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<BudgetRequest>,
) -> Result<Response> {
    let user = same_user(&h, body.user_id)?;
    let category = body
        .expense_category
        .clone()
        .map(|c| c.to_uppercase())
        .ok_or_else(|| bad("expenseCategory is required"))?;
    if !crate::entries::valid_category(&category) {
        return Err(bad("Invalid category"));
    }
    let amount = parse_amount(
        body.budget_amount
            .as_ref()
            .ok_or_else(|| bad("budgetAmount is required"))?,
    )?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    let period = body
        .period
        .clone()
        .map(|p| p.to_uppercase())
        .unwrap_or_else(|| "MONTHLY".into());
    if !PERIODS.contains(&period.as_str()) && period != "CUSTOM" {
        return Err(bad("Invalid period"));
    }
    let currency = body.currency.clone().unwrap_or_default();
    if currency.len() != 3 {
        return Err(bad("Currency must be 3 characters"));
    }
    let custom_start = body
        .custom_start_date
        .as_deref()
        .filter(|v| !v.trim().is_empty())
        .map(crate::date)
        .transpose()?;
    let custom_end = body
        .custom_end_date
        .as_deref()
        .filter(|v| !v.trim().is_empty())
        .map(crate::date)
        .transpose()?;
    // `period` has a CHECK constraint that only allows the four cadences;
    // "CUSTOM" is a frontend-only concept. Persist MONTHLY and let the custom
    // date window drive the utilisation range.
    let period = if period == "CUSTOM" {
        "MONTHLY".to_string()
    } else {
        period
    };
    let row = sqlx::query_as::<_, BudgetRow>(&format!(
        "INSERT INTO finance.category_budgets \
         (user_id,expense_category,budget_amount,period,currency,active,carry_forward,\
          custom_start_date,custom_end_date,created_at) \
         VALUES ($1,$2,$3,$4,$5,true,$6,$7,$8,now()) RETURNING {BUDGET_COLS}"
    ))
    .bind(user)
    .bind(&category)
    .bind(amount)
    .bind(&period)
    .bind(&currency)
    .bind(body.carry_forward.unwrap_or(false))
    .bind(custom_start)
    .bind(custom_end)
    .fetch_one(&a.db)
    .await?;
    let (start, end) = period_range(&row);
    let spent = spent_for(&a, user, &row.expense_category, start, end).await?;
    tracing::info!(id = row.id, %user, "budget created");
    Ok((StatusCode::CREATED, Json(budget_json(&row, spent))).into_response())
}

async fn delete_budget(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = query_user(&h, &q)?;
    let owner: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM finance.category_budgets WHERE id=$1")
            .bind(id)
            .fetch_optional(&a.db)
            .await?;
    let owner = owner.ok_or_else(|| not_found(&format!("Budget not found: {id}")))?;
    if owner != user {
        return Err(forbidden("Not authorized"));
    }
    sqlx::query("UPDATE finance.category_budgets SET active=false WHERE id=$1")
        .bind(id)
        .execute(&a.db)
        .await?;
    Ok(StatusCode::OK.into_response())
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
            s.next_charge_date.map(|d| d.to_string()).unwrap_or_default()
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

async fn update_subscription(
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

async fn deactivate_subscription(
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
