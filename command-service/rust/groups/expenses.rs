use super::EXPENSE_COLS;
use super::SPLIT_COLS;
use super::helpers::{Notice, actor, expense_json, group, log_activity, members, send_notices};
use super::models::{ExpenseRequest, ExpenseRow, SplitRow};
use super::personal_entries::{create_personal_transactions, delete_personal_transactions};
use super::splits::{insert_splits, parse_amount, receipt_owned};
use crate::{App, JsonBody, Result};
use crate::{bad, cache, forbidden, not_found};
use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use chrono::NaiveDateTime;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use sqlx::{Postgres, Transaction};
use std::collections::HashSet;
use uuid::Uuid;

struct NewExpense<'a> {
    group: i64,
    description: &'a str,
    amount: Decimal,
    currency: &'a str,
    paid_by: Uuid,
    split_type: &'a str,
    category: Option<&'a str>,
    date: Option<NaiveDateTime>,
    receipt: Option<&'a str>,
}

async fn insert_expense_row(
    tx: &mut Transaction<'_, Postgres>,
    expense: NewExpense<'_>,
) -> Result<ExpenseRow> {
    Ok(sqlx::query_as::<_, ExpenseRow>(&format!(
        "INSERT INTO groups.shared_expenses \
         (group_id,description,amount,currency,paid_by,split_type,expense_category,\
          expense_date,created_at,receipt_url) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,COALESCE($8::timestamp,now()),now(),$9) \
         RETURNING {EXPENSE_COLS}"
    ))
    .bind(expense.group)
    .bind(expense.description)
    .bind(expense.amount)
    .bind(expense.currency)
    .bind(expense.paid_by)
    .bind(expense.split_type)
    .bind(expense.category)
    .bind(expense.date)
    .bind(expense.receipt)
    .fetch_one(&mut **tx)
    .await?)
}

pub(crate) struct ExpenseOutcome {
    pub(crate) json: Value,
    pub(crate) evict_users: Vec<Uuid>,
    pub(crate) affected: Vec<Uuid>,
    pub(crate) notices: Vec<Notice>,
}

pub(crate) async fn add_expense_core(
    tx: &mut Transaction<'_, Postgres>,
    group_id: i64,
    body: &ExpenseRequest,
    user: Uuid,
) -> Result<ExpenseOutcome> {
    let _g = group(tx, group_id).await?;
    let all = members(tx, group_id).await?;
    let actor_row = all
        .iter()
        .find(|m| m.user_id == user)
        .cloned()
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    let paid_by = body.paid_by.ok_or_else(|| bad("paidBy is required"))?;
    if !all.iter().any(|m| m.user_id == paid_by) {
        return Err(forbidden("Payer is not a member of this group"));
    }
    let description = body
        .description
        .clone()
        .unwrap_or_default()
        .trim()
        .to_string();
    if description.is_empty() {
        return Err(bad("Description is required"));
    }
    if description.chars().count() > 100 {
        return Err(bad("Description cannot exceed 100 characters"));
    }
    let amount = parse_amount(
        body.amount
            .as_ref()
            .ok_or_else(|| bad("Amount is required"))?,
    )?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    let split_type = body
        .split_type
        .clone()
        .unwrap_or_else(|| "EQUAL".into())
        .to_uppercase();
    if !["EQUAL", "PERCENTAGE", "EXACT", "SETTLEMENT"].contains(&split_type.as_str()) {
        return Err(bad("Invalid split type"));
    }
    let currency = body.currency.clone().unwrap_or_else(|| "INR".into());
    receipt_owned(body.receipt_url.as_deref(), user)?;
    let category = body
        .expense_category
        .clone()
        .map(|c| c.to_uppercase())
        .filter(|c| crate::entries::valid_category(c));

    let expense = insert_expense_row(
        tx,
        NewExpense {
            group: group_id,
            description: &description,
            amount,
            currency: &currency,
            paid_by,
            split_type: &split_type,
            category: category.as_deref(),
            date: body
                .expense_date
                .as_deref()
                .map(crate::datetime)
                .transpose()?,
            receipt: body.receipt_url.as_deref(),
        },
    )
    .await?;
    let splits = insert_splits(tx, &expense, &all, body).await?;
    // Per-member personal transactions are materialised asynchronously by the
    // outbox worker (fanout_expense) so the request only pays for the shared
    // expense + splits instead of the O(members) fan-out. The event is written
    // in this transaction, so the work is durable if the request later fails.
    crate::entries::outbox(tx, user, "FANOUT", expense.id).await?;

    let (kind, message) = if split_type == "SETTLEMENT" {
        (
            "SETTLEMENT_RECORDED",
            format!("@{} recorded payment: {}", actor_row.name, description),
        )
    } else {
        (
            "EXPENSE_ADDED",
            format!(
                "@{} added expense \"{}\" ({} {})",
                actor_row.name,
                description,
                amount.normalize(),
                currency
            ),
        )
    };
    log_activity(
        tx,
        group_id,
        user,
        &actor_row.name,
        kind,
        &message,
        expense.id,
    )
    .await?;

    let affected: Vec<Uuid> = all.iter().map(|m| m.user_id).collect();
    let mut evict_users: Vec<Uuid> = splits
        .iter()
        .filter(|s| s.amount > Decimal::ZERO)
        .map(|s| s.user_id)
        .collect();
    evict_users.push(paid_by);
    let recipient_message = format!("{} added a new expense: {description}", actor_row.name);
    let notices = splits
        .iter()
        .filter(|s| s.user_id != user && s.amount > Decimal::ZERO)
        .map(|s| s.user_id)
        .collect::<HashSet<_>>()
        .into_iter()
        .map(|u| {
            Notice::new(
                u,
                group_id,
                "expense-added",
                recipient_message.clone(),
                "INFO",
            )
        })
        .collect();
    Ok(ExpenseOutcome {
        json: expense_json(&expense),
        evict_users,
        affected,
        notices,
    })
}

pub(crate) async fn apply_outcome(a: &App, group_id: i64, outcome: ExpenseOutcome) {
    cache::evict_users(a, &outcome.evict_users).await;
    cache::evict_group(a, group_id, &outcome.affected).await;
    send_notices(a, outcome.notices).await;
}

pub(crate) async fn add_expense(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    JsonBody(body): JsonBody<ExpenseRequest>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let outcome = add_expense_core(&mut tx, group_id, &body, user).await?;
    let json = outcome.json.clone();
    tx.commit().await?;
    apply_outcome(&a, group_id, outcome).await;
    Ok((StatusCode::CREATED, Json(json)).into_response())
}

pub(crate) async fn update_expense(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, expense_id)): Path<(i64, i64)>,
    JsonBody(body): JsonBody<ExpenseRequest>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let g = group(&mut tx, group_id).await?;
    let all = members(&mut tx, group_id).await?;
    let actor_row = all
        .iter()
        .find(|m| m.user_id == user)
        .cloned()
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    let old = sqlx::query_as::<_, ExpenseRow>(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE id=$1"
    ))
    .bind(expense_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Expense {expense_id} not found")))?;
    if old.group_id != group_id {
        return Err(bad("Expense does not belong to this group"));
    }
    if old.split_type == "SETTLEMENT" {
        return Err(bad("Settlements cannot be edited"));
    }
    let paid_by = body.paid_by.ok_or_else(|| bad("paidBy is required"))?;
    if !all.iter().any(|m| m.user_id == paid_by) {
        return Err(forbidden("Payer is not a member of this group"));
    }
    let old_splits = sqlx::query_as::<_, SplitRow>(&format!(
        "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id=$1"
    ))
    .bind(expense_id)
    .fetch_all(&mut *tx)
    .await?;
    if old_splits
        .iter()
        .any(|s| s.is_settled && s.user_id != old.paid_by)
    {
        return Err(bad("Cannot edit an expense with recorded settlements"));
    }
    let mut evict_users = delete_personal_transactions(&mut tx, expense_id).await?;
    sqlx::query("DELETE FROM groups.expense_splits WHERE shared_expense_id=$1")
        .bind(expense_id)
        .execute(&mut *tx)
        .await?;

    let description = body
        .description
        .clone()
        .unwrap_or_default()
        .trim()
        .to_string();
    if description.is_empty() {
        return Err(bad("Description is required"));
    }
    if description.chars().count() > 100 {
        return Err(bad("Description cannot exceed 100 characters"));
    }
    let amount = parse_amount(
        body.amount
            .as_ref()
            .ok_or_else(|| bad("Amount is required"))?,
    )?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    let split_type = body
        .split_type
        .clone()
        .unwrap_or_else(|| "EQUAL".into())
        .to_uppercase();
    if !["EQUAL", "PERCENTAGE", "EXACT"].contains(&split_type.as_str()) {
        return Err(bad("Invalid split type"));
    }
    let currency = body.currency.clone().unwrap_or_else(|| "INR".into());
    receipt_owned(body.receipt_url.as_deref(), user)?;
    let category = body
        .expense_category
        .clone()
        .map(|c| c.to_uppercase())
        .filter(|c| crate::entries::valid_category(c))
        .or(old.expense_category.clone());

    let expense = sqlx::query_as::<_, ExpenseRow>(&format!(
        "UPDATE groups.shared_expenses SET description=$2,amount=$3,currency=$4,paid_by=$5,\
         split_type=$6,expense_category=$7,expense_date=COALESCE($8::timestamp,expense_date),\
         receipt_url=$9 WHERE id=$1 RETURNING {EXPENSE_COLS}"
    ))
    .bind(expense_id)
    .bind(&description)
    .bind(amount)
    .bind(&currency)
    .bind(paid_by)
    .bind(&split_type)
    .bind(&category)
    .bind(
        body.expense_date
            .as_deref()
            .map(crate::datetime)
            .transpose()?,
    )
    .bind(&body.receipt_url)
    .fetch_one(&mut *tx)
    .await?;
    let splits = insert_splits(&mut tx, &expense, &all, &body).await?;
    let payer_name = all
        .iter()
        .find(|m| m.user_id == paid_by)
        .map(|m| m.name.clone())
        .unwrap_or_default();
    create_personal_transactions(&mut tx, &expense, &splits, &g.name, &payer_name).await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &actor_row.name,
        "EXPENSE_UPDATED",
        &format!(
            "@{} updated expense \"{}\" ({} {})",
            actor_row.name,
            description,
            amount.normalize(),
            currency
        ),
        expense_id,
    )
    .await?;
    let affected: Vec<Uuid> = all.iter().map(|m| m.user_id).collect();
    let notices: Vec<Notice> = splits
        .iter()
        .filter(|s| s.user_id != user && s.amount > Decimal::ZERO)
        .map(|s| {
            Notice::new(
                s.user_id,
                group_id,
                "expense-updated",
                format!("{} updated an expense: {description}", actor_row.name),
                "INFO",
            )
        })
        .collect();
    tx.commit().await?;
    evict_users.extend(splits.iter().map(|s| s.user_id));
    evict_users.push(paid_by);
    cache::evict_users(&a, &evict_users).await;
    cache::evict_group(&a, group_id, &affected).await;
    send_notices(&a, notices).await;
    Ok(Json(expense_json(&expense)).into_response())
}

pub(crate) async fn delete_expense(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, expense_id)): Path<(i64, i64)>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let _ = group(&mut tx, group_id).await?;
    let all = members(&mut tx, group_id).await?;
    let actor_row = all
        .iter()
        .find(|m| m.user_id == user)
        .cloned()
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    let expense = sqlx::query_as::<_, ExpenseRow>(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE id=$1"
    ))
    .bind(expense_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Expense {expense_id} not found")))?;
    if expense.group_id != group_id {
        return Err(bad("Expense does not belong to this group"));
    }
    let splits = sqlx::query_as::<_, SplitRow>(&format!(
        "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id=$1"
    ))
    .bind(expense_id)
    .fetch_all(&mut *tx)
    .await?;
    if splits
        .iter()
        .any(|s| s.is_settled && s.user_id != expense.paid_by)
    {
        return Err(bad("Cannot delete an expense with recorded settlements"));
    }
    let mut evict_users = delete_personal_transactions(&mut tx, expense_id).await?;
    sqlx::query("DELETE FROM groups.expense_splits WHERE shared_expense_id=$1")
        .bind(expense_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM groups.shared_expenses WHERE id=$1")
        .bind(expense_id)
        .execute(&mut *tx)
        .await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &actor_row.name,
        "EXPENSE_DELETED",
        &format!(
            "@{} deleted expense \"{}\" ({} {})",
            actor_row.name,
            expense.description,
            expense.amount.normalize(),
            expense.currency
        ),
        expense_id,
    )
    .await?;
    let affected: Vec<Uuid> = all.iter().map(|m| m.user_id).collect();
    tx.commit().await?;
    evict_users.extend(splits.iter().map(|s| s.user_id));
    evict_users.push(expense.paid_by);
    cache::evict_users(&a, &evict_users).await;
    cache::evict_group(&a, group_id, &affected).await;
    Ok(Json(json!({"message":"Expense deleted successfully"})).into_response())
}
