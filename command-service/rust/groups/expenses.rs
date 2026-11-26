use super::*;
use crate::{bad, cache, forbidden, not_found};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use chrono::NaiveDateTime;
use rust_decimal::Decimal;
use serde_json::{Value, json};
use sqlx::{Postgres, Transaction};
use std::collections::HashSet;
use uuid::Uuid;


pub(crate) async fn insert_entry(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    name: &str,
    amount: Decimal,
    r#type: &str,
    category: &str,
    currency: &str,
    description: &str,
) -> Result<i64> {
    Ok(sqlx::query_scalar::<_, i64>(
        "INSERT INTO finance.transaction_entries \
         (user_id,name,amount,type,category,currency,description,created_at,updated_at,\
          recurring,transaction_kind) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,now(),now(),false,'STANDARD') RETURNING id",
    )
    .bind(user)
    .bind(name)
    .bind(amount)
    .bind(r#type)
    .bind(category)
    .bind(currency)
    .bind(description)
    .fetch_one(&mut **tx)
    .await?)
}

pub(crate) async fn create_personal_transactions(
    tx: &mut Transaction<'_, Postgres>,
    expense: &ExpenseRow,
    splits: &[SplitRow],
    group_name: &str,
    payer_name: &str,
) -> Result<()> {
    let category = if expense.split_type == "SETTLEMENT" {
        "SETTLEMENT".to_string()
    } else {
        expense
            .expense_category
            .clone()
            .unwrap_or_else(|| "OTHERS".into())
    };
    for split in splits.iter().filter(|s| s.amount > Decimal::ZERO) {
        let mut entries: Vec<(Uuid, String, Decimal, &str)> = Vec::new();
        if expense.split_type == "SETTLEMENT" {
            entries.push((
                expense.paid_by,
                format!("Paid {}", split.user_name.clone().unwrap_or_default()),
                split.amount,
                "EXPENSE",
            ));
            entries.push((
                split.user_id,
                format!("Received from {payer_name}"),
                split.amount,
                "INCOME",
            ));
        } else {
            entries.push((
                split.user_id,
                expense.description.clone(),
                split.amount,
                "EXPENSE",
            ));
        }
        for (user, name, amount, r#type) in entries {
            let description = if expense.split_type == "SETTLEMENT" {
                format!("Settlement · {group_name}")
            } else {
                format!("Split expense · {group_name}")
            };
            let entry_id = insert_entry(
                tx,
                user,
                &name,
                amount,
                r#type,
                &category,
                &expense.currency,
                &description,
            )
            .await?;
            sqlx::query(
                "INSERT INTO groups.expense_transaction_links \
                 (shared_expense_id,user_id,transaction_entry_id) VALUES ($1,$2,$3)",
            )
            .bind(expense.id)
            .bind(user)
            .bind(entry_id)
            .execute(&mut **tx)
            .await?;
        }
    }
    Ok(())
}

pub(crate) async fn delete_personal_transactions(
    tx: &mut Transaction<'_, Postgres>,
    expense_id: i64,
) -> Result<Vec<Uuid>> {
    let links: Vec<(i64, Uuid)> = sqlx::query_as(
        "SELECT transaction_entry_id, user_id FROM groups.expense_transaction_links \
         WHERE shared_expense_id=$1",
    )
    .bind(expense_id)
    .fetch_all(&mut **tx)
    .await?;
    let mut users = Vec::new();
    for (entry_id, user) in links {
        sqlx::query(
            "UPDATE finance.transaction_entries SET deleted_at=now(), updated_at=now() \
             WHERE id=$1 AND deleted_at IS NULL",
        )
        .bind(entry_id)
        .execute(&mut **tx)
        .await?;
        sqlx::query(
            "INSERT INTO finance.outbox_events (user_id,event_type,entity_id,status,created_at) \
             VALUES ($1,'DELETE',$2,'PENDING',now())",
        )
        .bind(user)
        .bind(entry_id)
        .execute(&mut **tx)
        .await?;
        users.push(user);
    }
    sqlx::query("DELETE FROM groups.expense_transaction_links WHERE shared_expense_id=$1")
        .bind(expense_id)
        .execute(&mut **tx)
        .await?;
    Ok(users)
}

#[allow(clippy::too_many_arguments)]
pub(crate) async fn insert_expense_row(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    description: &str,
    amount: Decimal,
    currency: &str,
    paid_by: Uuid,
    split_type: &str,
    category: Option<&str>,
    date: Option<NaiveDateTime>,
    receipt: Option<&str>,
) -> Result<ExpenseRow> {
    Ok(sqlx::query_as::<_, ExpenseRow>(&format!(
        "INSERT INTO groups.shared_expenses \
         (group_id,description,amount,currency,paid_by,split_type,expense_category,\
          expense_date,created_at,receipt_url) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,COALESCE($8::timestamp,now()),now(),$9) \
         RETURNING {EXPENSE_COLS}"
    ))
    .bind(group)
    .bind(description)
    .bind(amount)
    .bind(currency)
    .bind(paid_by)
    .bind(split_type)
    .bind(category)
    .bind(date)
    .bind(receipt)
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
    let description = body.description.clone().unwrap_or_default().trim().to_string();
    if description.is_empty() {
        return Err(bad("Description is required"));
    }
    if description.chars().count() > 100 {
        return Err(bad("Description cannot exceed 100 characters"));
    }
    let amount = parse_amount(body.amount.as_ref().ok_or_else(|| bad("Amount is required"))?)?;
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
        group_id,
        &description,
        amount,
        &currency,
        paid_by,
        &split_type,
        category.as_deref(),
        body.expense_date.as_deref().map(crate::datetime).transpose()?,
        body.receipt_url.as_deref(),
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
    log_activity(tx, group_id, user, &actor_row.name, kind, &message, expense.id).await?;

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
        .map(|u| Notice::new(u, group_id, "expense-added", recipient_message.clone(), "INFO"))
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

    let description = body.description.clone().unwrap_or_default().trim().to_string();
    if description.is_empty() {
        return Err(bad("Description is required"));
    }
    if description.chars().count() > 100 {
        return Err(bad("Description cannot exceed 100 characters"));
    }
    let amount = parse_amount(body.amount.as_ref().ok_or_else(|| bad("Amount is required"))?)?;
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
    .bind(body.expense_date.as_deref().map(crate::datetime).transpose()?)
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

pub(crate) async fn settle_debt_core(
    tx: &mut Transaction<'_, Postgres>,
    group_id: i64,
    from: Uuid,
    to: Uuid,
    actor: Uuid,
) -> Result<Option<ExpenseOutcome>> {
    let bals = balances(tx, group_id).await?;
    let currency = currency_of(tx, group_id).await?;
    let total: Decimal = minimize_debts(&bals, &currency)
        .into_iter()
        .filter(|s| s.from == from && s.to == to)
        .map(|s| s.amount)
        .sum();
    if total <= Decimal::ZERO {
        return Ok(None);
    }
    let from_name = bals
        .iter()
        .find(|b| b.user_id == from)
        .map(|b| b.user_name.clone())
        .unwrap_or_else(|| from.to_string());
    let to_name = bals
        .iter()
        .find(|b| b.user_id == to)
        .map(|b| b.user_name.clone())
        .unwrap_or_else(|| to.to_string());
    let body = ExpenseRequest {
        description: Some(format!("Payment from {from_name} to {to_name}")),
        amount: Some(json!(total)),
        currency: Some(currency),
        paid_by: Some(from),
        split_type: Some("SETTLEMENT".into()),
        expense_category: Some("SETTLEMENT".into()),
        expense_date: None,
        split_details: Some(vec![SplitDetail {
            user_id: Some(to),
            value: Some(json!(total)),
        }]),
        receipt_url: None,
    };
    let mut outcome = add_expense_core(tx, group_id, &body, actor).await?;
    let target = if from == actor { to } else { from };
    outcome.notices.push(Notice::new(
        target,
        group_id,
        "debt-settled",
        format!(
            "{from_name} recorded a ₹{} settlement with {to_name}",
            total.round_dp(2)
        ),
        "SUCCESS",
    ));
    Ok(Some(outcome))
}

pub(crate) async fn settle_debt(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = actor(&h)?;
    let from = q_uuid(&q, "fromUserId")?;
    let to = q_uuid(&q, "toUserId")?;
    let mut tx = a.db.begin().await?;
    let _ = require_member(&mut tx, group_id, user).await?;
    let outcome = settle_debt_core(&mut tx, group_id, from, to, user).await?;
    tx.commit().await?;
    if let Some(outcome) = outcome {
        apply_outcome(&a, group_id, outcome).await;
    }
    Ok(Json(json!({"message":"Settlement recorded successfully"})).into_response())
}

/// Materialise the per-member personal transactions for a group expense.
/// Called by the outbox worker, off the request path. Idempotent: the
/// expense_transaction_links rows are the completion marker, and the whole
/// fan-out commits in one transaction, so a retry either redoes nothing or
/// completes the work atomically.
pub(crate) async fn fanout_expense(a: &App, expense_id: i64) -> Result<()> {
    let mut tx = a.db.begin().await?;
    let existing: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM groups.expense_transaction_links WHERE shared_expense_id=$1",
    )
    .bind(expense_id)
    .fetch_one(&mut *tx)
    .await?;
    if existing > 0 {
        return Ok(());
    }
    let expense: ExpenseRow = sqlx::query_as(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE id=$1"
    ))
    .bind(expense_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found("Expense not found"))?;
    let splits: Vec<SplitRow> = sqlx::query_as(&format!(
        "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id=$1"
    ))
    .bind(expense_id)
    .fetch_all(&mut *tx)
    .await?;
    let group_name: String =
        sqlx::query_scalar("SELECT name FROM groups.expense_groups WHERE id=$1")
            .bind(expense.group_id)
            .fetch_one(&mut *tx)
            .await?;
    let payer_name = splits
        .iter()
        .find(|s| s.user_id == expense.paid_by)
        .and_then(|s| s.user_name.clone())
        .unwrap_or_default();
    create_personal_transactions(&mut tx, &expense, &splits, &group_name, &payer_name).await?;
    tx.commit().await?;
    Ok(())
}
