use super::{parse_amount, query_user, same_user};
use crate::{App, JsonBody, Params, Result, bad, forbidden, money, not_found};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use chrono::{NaiveDate, NaiveDateTime, Utc};
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde::Deserialize;
use serde_json::{Value, json};
use uuid::Uuid;

use super::PERIODS;
use chrono::{Datelike, Days};

const BUDGET_COLS: &str = "id,user_id,expense_category::text AS expense_category,\
budget_amount,period::text AS period,currency,active,carry_forward,custom_start_date,\
custom_end_date,created_at";
const REPORTABLE: &str = "coalesce(transaction_kind,'STANDARD') NOT IN ('TRANSFER','INVESTMENT') \
AND category NOT IN ('SETTLEMENT','CREDIT_CARD_PAYMENT','MUTUAL_FUNDS_SIP','STOCKS_AND_TRADING',\
'CRYPTO','EMERGENCY_FUND','FIXED_DEPOSIT','GOAL','OWN_ACCOUNT_TRANSFER','BORROWING','LENDING',\
'LOAN_PRINCIPAL','INVESTMENT_WITHDRAWAL')";

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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct BudgetRequest {
    user_id: Option<Uuid>,
    expense_category: Option<String>,
    budget_amount: Option<Value>,
    period: Option<String>,
    currency: Option<String>,
    carry_forward: Option<bool>,
    custom_start_date: Option<String>,
    custom_end_date: Option<String>,
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

pub(super) async fn create_budget(
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

pub(super) async fn delete_budget(
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
