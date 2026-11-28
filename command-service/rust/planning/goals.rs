use super::{parse_amount, query_user, same_user};
use crate::{App, JsonBody, Params, Result, bad, cache, forbidden, money, not_found, ts};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use chrono::{NaiveDate, NaiveDateTime};
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde::Deserialize;
use serde_json::{Value, json};
use uuid::Uuid;

const GOAL_COLS: &str = "id,user_id,name,target_amount,saved_amount,currency,description,deadline,\
created_at,completed_at,active,priority::text AS priority";
const PRIORITIES: &[&str] = &["CRITICAL", "HIGH", "MEDIUM", "LOW"];

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

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct GoalRequest {
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
pub(super) struct ContributionRequest {
    amount: Option<Value>,
    description: Option<String>,
    created_at: Option<String>,
    currency: Option<String>,
}

fn goal_json(g: &GoalRow) -> Value {
    let progress = if g.target_amount == Decimal::ZERO {
        0.0
    } else {
        (g.saved_amount.to_f64().unwrap_or(0.0) / g.target_amount.to_f64().unwrap_or(1.0) * 100.0)
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

pub(super) async fn create_goal(
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

pub(super) async fn contribute(
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
    let currency = body
        .currency
        .clone()
        .unwrap_or_else(|| goal.currency.clone());
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

pub(super) async fn delete_goal(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = query_user(&h, &q)?;
    let owner: Option<Uuid> =
        sqlx::query_scalar("SELECT user_id FROM finance.savings_goals WHERE id=$1")
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
