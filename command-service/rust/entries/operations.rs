use super::ENTRY_COLS;
use super::models::{EntryRequest, EntryRow, Validated};
use super::validation::{
    group_link_exists, outbox, parse_amount, reportable, validate, validate_patched,
};
use crate::{App, Result, bad, forbidden, illegal_state, money, not_found, server_error, ts};
use crate::{cache, subscriptions};
use chrono::{NaiveDateTime, Utc};
use rust_decimal::Decimal;
use serde_json::Value;
use serde_json::json;
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

pub fn next_run(created: Option<NaiveDateTime>, period: &str) -> Option<NaiveDateTime> {
    let base = created.unwrap_or_else(|| Utc::now().naive_utc());
    let next = match period {
        "DAILY" => base + chrono::Days::new(1),
        "WEEKLY" => base + chrono::Days::new(7),
        "MONTHLY" => base
            .checked_add_months(chrono::Months::new(1))
            .unwrap_or(base),
        "YEARLY" => base
            .checked_add_months(chrono::Months::new(12))
            .unwrap_or(base),
        _ => base
            .checked_add_months(chrono::Months::new(1))
            .unwrap_or(base),
    };
    next.date().and_hms_opt(0, 0, 0)
}

pub(crate) async fn insert(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    v: &Validated,
) -> Result<EntryRow> {
    let next = if v.recurring {
        v.period.as_deref().and_then(|p| next_run(v.created_at, p))
    } else {
        None
    };
    let sql = format!(
        "INSERT INTO finance.transaction_entries \
         (user_id,name,amount,type,category,currency,description,created_at,updated_at,\
          recurring,recurring_period,next_run_date,receipt_url,transaction_kind,\
          original_transaction_id,subcategory) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,COALESCE($8::timestamp,now()),now(),$9,$10,$11,$12,$13,$14,$15) \
         RETURNING {ENTRY_COLS}"
    );
    Ok(sqlx::query_as::<_, EntryRow>(&sql)
        .bind(user)
        .bind(&v.name)
        .bind(v.amount)
        .bind(&v.r#type)
        .bind(&v.category)
        .bind(&v.currency)
        .bind(&v.description)
        .bind(v.created_at)
        .bind(v.recurring)
        .bind(&v.period)
        .bind(next)
        .bind(&v.receipt_url)
        .bind(&v.kind)
        .bind(v.original_id)
        .bind(&v.subcategory)
        .fetch_one(&mut **tx)
        .await?)
}

pub async fn create(
    a: &App,
    user: Uuid,
    req: EntryRequest,
    idempotency_key: Option<&str>,
) -> Result<Value> {
    let key = idempotency_key
        .map(str::trim)
        .filter(|k| !k.is_empty())
        .map(str::to_string);
    if let Some(key) = &key
        && key.chars().count() > 120
    {
        return Err(bad("X-Idempotency-Key must be at most 120 characters"));
    }

    let mut tx = a.db.begin().await?;
    if let Some(key) = &key {
        let inserted = sqlx::query(
            "INSERT INTO finance.idempotency_records (user_id,idempotency_key,created_at) \
             VALUES ($1,$2,now()) ON CONFLICT (user_id,idempotency_key) DO NOTHING",
        )
        .bind(user)
        .bind(key)
        .execute(&mut *tx)
        .await?;
        if inserted.rows_affected() == 0 {
            tx.commit().await?;
            return replay(a, user, key).await;
        }
    }
    let v = validate(&mut tx, user, &req, None).await?;
    let row = insert(&mut tx, user, &v).await?;
    if let Some(key) = &key {
        sqlx::query(
            "UPDATE finance.idempotency_records SET transaction_id=$1 \
             WHERE user_id=$2 AND idempotency_key=$3",
        )
        .bind(row.id)
        .bind(user)
        .bind(key)
        .execute(&mut *tx)
        .await?;
    }
    outbox(&mut tx, user, "CREATE", row.id).await?;
    tx.commit().await?;

    if row.recurring && row.r#type == "EXPENSE" && reportable_text(&row) {
        detect_subscription(a, &row).await;
    }
    cache::evict_users(a, &[user]).await;
    metrics::counter!("transactions.create.success").increment(1);
    tracing::info!(id = row.id, user = %user, recurring = row.recurring, "transaction created");
    Ok(entry_response(&row))
}

pub(crate) async fn replay(a: &App, user: Uuid, key: &str) -> Result<Value> {
    let transaction_id: Option<i64> = sqlx::query_scalar(
        "SELECT transaction_id FROM finance.idempotency_records \
         WHERE user_id=$1 AND idempotency_key=$2",
    )
    .bind(user)
    .bind(key)
    .fetch_one(&a.db)
    .await?;
    let id = transaction_id.ok_or_else(|| {
        server_error(&format!(
            "Idempotency key '{key}' is reserved but has no transaction attached"
        ))
    })?;
    let row = sqlx::query_as::<_, EntryRow>(&format!(
        "SELECT {ENTRY_COLS} FROM finance.transaction_entries WHERE id=$1 AND deleted_at IS NULL"
    ))
    .bind(id)
    .fetch_optional(&a.db)
    .await?
    .ok_or_else(|| server_error("Idempotency key points to a missing transaction"))?;
    tracing::info!(key = key, id, "replayed idempotent create");
    Ok(entry_response(&row))
}

pub async fn create_bulk(a: &App, user: Uuid, reqs: Vec<EntryRequest>) -> Result<Vec<Value>> {
    let mut tx = a.db.begin().await?;
    let mut rows = Vec::new();
    for req in &reqs {
        if req.user_id != Some(user) {
            return Err(forbidden("Access denied: User ID mismatch"));
        }
        let v = validate(&mut tx, user, req, None).await?;
        rows.push(insert(&mut tx, user, &v).await?);
    }
    if let Some(first) = rows.first() {
        outbox(&mut tx, user, "CREATE", first.id).await?;
    }
    tx.commit().await?;

    for row in &rows {
        if row.recurring && row.r#type == "EXPENSE" && reportable_text(row) {
            detect_subscription(a, row).await;
        }
    }
    cache::evict_users(a, &[user]).await;
    metrics::counter!("transactions.create.success").increment(rows.len() as u64);
    tracing::info!(count = rows.len(), user = %user, "batch created transaction entries");
    Ok(rows.iter().map(entry_response).collect())
}

pub(crate) async fn detect_subscription(a: &App, row: &EntryRow) {
    let period = row
        .recurring_period
        .clone()
        .unwrap_or_else(|| "MONTHLY".into());
    if let Err(e) = subscriptions::upsert_for_created(
        &a.db,
        row.user_id,
        &row.name,
        row.amount,
        &row.currency,
        &period,
        row.created_at.date(),
    )
    .await
    {
        tracing::warn!(error = %e, id = row.id, "subscription detection failed");
    }
}

pub(crate) fn reportable_text(row: &EntryRow) -> bool {
    reportable(
        row.transaction_kind.as_deref().unwrap_or("STANDARD"),
        row.category.as_deref(),
    )
}

pub async fn update(a: &App, user: Uuid, req: EntryRequest) -> Result<Value> {
    let id = req.id.ok_or_else(|| bad("Transaction ID is required"))?;
    let mut tx = a.db.begin().await?;
    let existing = sqlx::query_as::<_, EntryRow>(&format!(
        "SELECT {ENTRY_COLS} FROM finance.transaction_entries \
         WHERE id=$1 AND deleted_at IS NULL FOR UPDATE"
    ))
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Transaction entry with ID {id} not found")))?;
    if existing.user_id != user {
        return Err(forbidden(
            "User is not authorized to update this transaction",
        ));
    }
    if group_link_exists(&mut tx, id).await? {
        return Err(illegal_state(
            "This transaction is managed by a group expense. Please modify the shared expense instead.",
        ));
    }
    require_no_recoveries(&mut tx, user, &existing).await?;

    let v = validate(&mut tx, user, &req, Some(id)).await?;
    let diff = v.amount - existing.amount;
    let sql = format!(
        "UPDATE finance.transaction_entries SET name=$2,amount=$3,type=$4,category=$5,\
         currency=$6,description=$7,recurring=$8,recurring_period=$9,receipt_url=$10,\
         transaction_kind=$11,original_transaction_id=$12,subcategory=$13,\
         created_at=COALESCE($14::timestamp,created_at),updated_at=now() \
         WHERE id=$1 RETURNING {ENTRY_COLS}"
    );
    let row = sqlx::query_as::<_, EntryRow>(&sql)
        .bind(id)
        .bind(&v.name)
        .bind(v.amount)
        .bind(&v.r#type)
        .bind(&v.category)
        .bind(&v.currency)
        .bind(&v.description)
        .bind(v.recurring)
        .bind(&v.period)
        .bind(&v.receipt_url)
        .bind(&v.kind)
        .bind(v.original_id)
        .bind(&v.subcategory)
        .bind(v.created_at)
        .fetch_one(&mut *tx)
        .await?;

    if diff != Decimal::ZERO {
        adjust_allocation(&mut tx, user, id, diff, true).await?;
    }
    outbox(&mut tx, user, "UPDATE", id).await?;
    tx.commit().await?;
    cache::evict_users(a, &[user]).await;
    tracing::info!(id, "transaction updated");
    Ok(entry_response(&row))
}

pub async fn patch_amount(a: &App, id: i64, user: Uuid, amount: Value) -> Result<Value> {
    let new_amount = parse_amount(&amount)?;
    if new_amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    if new_amount.scale() > 2 || new_amount.abs().trunc().to_string().len() > 10 {
        return Err(bad("Invalid amount format"));
    }
    let mut tx = a.db.begin().await?;
    let existing = sqlx::query_as::<_, EntryRow>(&format!(
        "SELECT {ENTRY_COLS} FROM finance.transaction_entries \
         WHERE id=$1 AND user_id=$2 AND deleted_at IS NULL FOR UPDATE"
    ))
    .bind(id)
    .bind(user)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Transaction entry with ID {id} not found")))?;
    require_no_recoveries(&mut tx, user, &existing).await?;
    validate_patched(&mut tx, user, &existing, new_amount).await?;

    let diff = new_amount - existing.amount;
    let row = sqlx::query_as::<_, EntryRow>(&format!(
        "UPDATE finance.transaction_entries SET amount=$2, updated_at=now() \
         WHERE id=$1 RETURNING {ENTRY_COLS}"
    ))
    .bind(id)
    .bind(new_amount)
    .fetch_one(&mut *tx)
    .await?;

    if diff != Decimal::ZERO {
        adjust_allocation(&mut tx, user, id, diff, true).await?;
    }
    outbox(&mut tx, user, "PATCH", id).await?;
    tx.commit().await?;
    cache::evict_users(a, &[user]).await;
    tracing::info!(id, amount = %new_amount, "transaction amount patched");
    Ok(entry_response(&row))
}

pub async fn delete(a: &App, id: i64, user: Uuid) -> Result<()> {
    if sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM groups.expense_transaction_links WHERE transaction_entry_id=$1)",
    )
    .bind(id)
    .fetch_one(&a.db)
    .await?
    {
        return Err(illegal_state(
            "This transaction is managed by a group expense. Please modify the shared expense instead.",
        ));
    }
    let mut tx = a.db.begin().await?;
    let existing = sqlx::query_as::<_, EntryRow>(&format!(
        "SELECT {ENTRY_COLS} FROM finance.transaction_entries \
         WHERE id=$1 AND deleted_at IS NULL FOR UPDATE"
    ))
    .bind(id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Transaction entry with ID {id} not found")))?;
    if existing.user_id != user {
        return Err(forbidden(
            "User is not authorized to delete this transaction",
        ));
    }
    require_no_recoveries(&mut tx, user, &existing).await?;
    adjust_allocation(&mut tx, user, id, Decimal::ZERO, false).await?;
    sqlx::query(
        "UPDATE finance.transaction_entries SET deleted_at=now(), updated_at=now() WHERE id=$1",
    )
    .bind(id)
    .execute(&mut *tx)
    .await?;
    outbox(&mut tx, user, "DELETE", id).await?;
    tx.commit().await?;
    cache::evict_users(a, &[user]).await;
    metrics::counter!("transactions.delete.success").increment(1);
    tracing::info!(id, user = %user, "transaction deleted");
    Ok(())
}

pub(crate) async fn require_no_recoveries(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    row: &EntryRow,
) -> Result<()> {
    let recovered: Decimal = sqlx::query_scalar(
        "SELECT COALESCE(SUM(amount),0) FROM finance.transaction_entries \
         WHERE original_transaction_id=$1 AND deleted_at IS NULL \
           AND transaction_kind IN ('REFUND','REIMBURSEMENT')",
    )
    .bind(row.id)
    .fetch_one(&mut **tx)
    .await?;
    let _ = user;
    if recovered > Decimal::ZERO {
        return Err(bad(
            "Remove linked refunds or reimbursements before changing or deleting the original expense",
        ));
    }
    Ok(())
}

/// Adjust the goal allocation for an amount change (`change_allocation=true`)
/// or revert the goal's saved amount on delete (`false`).
pub(crate) async fn adjust_allocation(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    transaction_id: i64,
    diff: Decimal,
    change_allocation: bool,
) -> Result<()> {
    let alloc: Option<(i64, i64, Decimal)> = sqlx::query_as(
        "SELECT id, goal_id, amount FROM finance.transaction_goal_allocations WHERE transaction_id=$1",
    )
    .bind(transaction_id)
    .fetch_optional(&mut **tx)
    .await?;
    let Some((allocation_id, goal_id, allocation_amount)) = alloc else {
        return Ok(());
    };
    let delta = if change_allocation {
        diff
    } else {
        -allocation_amount
    };
    if change_allocation {
        sqlx::query("UPDATE finance.transaction_goal_allocations SET amount=$2 WHERE id=$1")
            .bind(allocation_id)
            .bind(allocation_amount + delta)
            .execute(&mut **tx)
            .await?;
    }
    sqlx::query(
        "UPDATE finance.savings_goals SET saved_amount=saved_amount+$2, \
         completed_at=CASE WHEN saved_amount+$2>=target_amount AND completed_at IS NULL \
         THEN now() ELSE completed_at END WHERE id=$1 AND user_id=$3",
    )
    .bind(goal_id)
    .bind(delta)
    .bind(user)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

pub fn entry_response(r: &EntryRow) -> Value {
    json!({
        "id": r.id,
        "userId": r.user_id,
        "name": r.name,
        "amount": money(r.amount),
        "type": r.r#type,
        "category": r.category,
        "currency": r.currency,
        "description": r.description,
        "recurring": r.recurring,
        "recurringPeriod": r.recurring_period,
        "createdAt": ts(&r.created_at),
        "updatedAt": r.updated_at.as_ref().map(ts),
        "receiptUrl": r.receipt_url,
        "transactionKind": r.transaction_kind.clone().unwrap_or_else(|| "STANDARD".into()),
        "originalTransactionId": r.original_transaction_id,
        "subcategory": r.subcategory,
    })
}
