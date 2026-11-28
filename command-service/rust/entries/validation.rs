use super::ENTRY_COLS;
use super::categories::{CATEGORIES, INCOME_CATEGORIES, KINDS, MOVEMENT_CATEGORIES, PERIODS};
use super::models::{EntryRequest, EntryRow, Validated};
use crate::{Result, bad, forbidden};
use rust_decimal::Decimal;
use serde_json::Value;
use sqlx::{Postgres, Transaction};
use std::str::FromStr;
use uuid::Uuid;

pub fn valid_category(category: &str) -> bool {
    CATEGORIES.contains(&category)
}

pub(crate) fn is_recovery(kind: &str) -> bool {
    kind == "REFUND" || kind == "REIMBURSEMENT"
}

pub fn reportable(kind: &str, category: Option<&str>) -> bool {
    kind != "TRANSFER"
        && kind != "INVESTMENT"
        && !MOVEMENT_CATEGORIES.contains(&category.unwrap_or("OTHERS"))
}

pub(crate) fn parse_amount(v: &Value) -> Result<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).map_err(|_| bad("Invalid amount format"))
}

pub(crate) fn require_owned(url: Option<&str>, user: Uuid) -> Result<()> {
    if let Some(url) = url
        && !url.is_empty()
    {
        let prefix = format!("/api/storage/receipts/{user}");
        if !url.starts_with(&format!("{prefix}/")) && !url.starts_with(&format!("{prefix}-batch-"))
        {
            return Err(forbidden("Receipt was not uploaded by this user"));
        }
    }
    Ok(())
}

pub(crate) fn basic(req: &EntryRequest) -> Result<Validated> {
    let name = req.name.clone().unwrap_or_default().trim().to_string();
    if name.is_empty() {
        return Err(bad("Name is required"));
    }
    if name.chars().count() > 100 {
        return Err(bad("Name cannot exceed 100 characters"));
    }
    let amount = parse_amount(
        req.amount
            .as_ref()
            .ok_or_else(|| bad("Amount is required"))?,
    )?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    if amount.scale() > 2 || amount.abs().trunc().to_string().len() > 10 {
        return Err(bad("Invalid amount format"));
    }
    let r#type = req.r#type.clone().unwrap_or_default();
    if !["INCOME", "EXPENSE"].contains(&r#type.as_str()) {
        return Err(bad("Transaction type is required"));
    }
    let kind = req
        .transaction_kind
        .clone()
        .unwrap_or_else(|| "STANDARD".into());
    if !KINDS.contains(&kind.as_str()) {
        return Err(bad("Invalid transaction kind"));
    }
    let category = match &req.category {
        Some(c) if !CATEGORIES.contains(&c.as_str()) => return Err(bad("Invalid category")),
        other => other.clone(),
    };
    let currency = req.currency.clone().unwrap_or_default();
    if currency.len() != 3 {
        return Err(bad("Currency must be 3 characters"));
    }
    let description = req.description.clone().filter(|d| !d.is_empty());
    if description
        .as_ref()
        .is_some_and(|d| d.chars().count() > 500)
    {
        return Err(bad("Description cannot exceed 500 characters"));
    }
    let subcategory = req.subcategory.clone().map(|s| s.trim().to_string());
    if subcategory.as_ref().is_some_and(|s| s.chars().count() > 80) {
        return Err(bad("Subcategory cannot exceed 80 characters"));
    }
    let subcategory = subcategory.filter(|s| !s.is_empty());
    let period = req.recurring_period.clone();
    if period
        .as_ref()
        .is_some_and(|p| !PERIODS.contains(&p.as_str()))
    {
        return Err(bad("Invalid recurring period"));
    }
    Ok(Validated {
        name,
        amount,
        r#type,
        kind,
        category,
        currency,
        description,
        recurring: req.recurring.unwrap_or(false),
        period,
        created_at: match req.created_at.as_deref() {
            None => None,
            Some(value) if value.trim().is_empty() => None,
            Some(value) => Some(crate::datetime(value)?),
        },
        receipt_url: req.receipt_url.clone().filter(|r| !r.is_empty()),
        original_id: req.original_transaction_id,
        subcategory,
    })
}

pub(crate) fn rules(v: &Validated, self_id: Option<i64>) -> Result<()> {
    if !is_recovery(&v.kind) {
        let category = v
            .category
            .as_ref()
            .ok_or_else(|| bad("Category is required"))?;
        if v.original_id.is_some() {
            return Err(bad(
                "Only refunds and reimbursements can link to an expense",
            ));
        }
        if v.kind == "STANDARD"
            && !MOVEMENT_CATEGORIES.contains(&category.as_str())
            && ((v.r#type == "INCOME") != INCOME_CATEGORIES.contains(&category.as_str()))
        {
            return Err(bad("Category does not match the transaction direction"));
        }
        return Ok(());
    }
    if v.r#type != "INCOME"
        || v.recurring
        || v.original_id.is_none()
        || (self_id.is_some() && v.original_id == self_id)
    {
        return Err(bad(
            "A refund or reimbursement must be a non-recurring incoming payment linked to an expense",
        ));
    }
    Ok(())
}

pub(crate) async fn validate_recovery(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    v: &mut Validated,
    self_id: Option<i64>,
) -> Result<()> {
    let original_id = v.original_id.unwrap();
    let original = sqlx::query_as::<_, EntryRow>(&format!(
        "SELECT {ENTRY_COLS} FROM finance.transaction_entries \
         WHERE id=$1 AND user_id=$2 AND deleted_at IS NULL FOR UPDATE"
    ))
    .bind(original_id)
    .bind(user)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| bad("Original expense not found"))?;
    let original_kind = original
        .transaction_kind
        .clone()
        .unwrap_or_else(|| "STANDARD".into());
    if original.r#type != "EXPENSE"
        || !reportable(&original_kind, original.category.as_deref())
        || is_recovery(&original_kind)
        || !original.currency.eq_ignore_ascii_case(&v.currency)
    {
        return Err(bad(
            "The original transaction must be an expense in the same currency",
        ));
    }
    if group_link_exists(tx, original.id).await? {
        return Err(bad("For a shared expense, adjust the expense in its group"));
    }
    let recovered: Decimal = sqlx::query_scalar(
        "SELECT COALESCE(SUM(amount),0) FROM finance.transaction_entries \
         WHERE original_transaction_id=$1 AND deleted_at IS NULL \
           AND ($2::bigint IS NULL OR id<>$2) \
           AND transaction_kind IN ('REFUND','REIMBURSEMENT')",
    )
    .bind(original.id)
    .bind(self_id)
    .fetch_one(&mut **tx)
    .await?;
    if recovered + v.amount > original.amount {
        return Err(bad(
            "Refunds and reimbursements cannot exceed the original expense",
        ));
    }
    v.category = original.category;
    v.subcategory = original.subcategory;
    Ok(())
}

pub(crate) async fn validate(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    req: &EntryRequest,
    self_id: Option<i64>,
) -> Result<Validated> {
    let mut v = basic(req)?;
    rules(&v, self_id)?;
    require_owned(v.receipt_url.as_deref(), user)?;
    if is_recovery(&v.kind) {
        validate_recovery(tx, user, &mut v, self_id).await?;
    }
    Ok(v)
}

/// Re-run the accounting rules after an amount-only change; editable fields are
/// unchanged, but a refund must still not exceed its original expense.
pub(crate) async fn validate_patched(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    row: &EntryRow,
    amount: Decimal,
) -> Result<()> {
    let mut v = Validated {
        name: row.name.clone(),
        amount,
        r#type: row.r#type.clone(),
        kind: row
            .transaction_kind
            .clone()
            .unwrap_or_else(|| "STANDARD".into()),
        category: row.category.clone(),
        currency: row.currency.clone(),
        description: row.description.clone(),
        recurring: row.recurring,
        period: row.recurring_period.clone(),
        created_at: Some(row.created_at),
        receipt_url: row.receipt_url.clone(),
        original_id: row.original_transaction_id,
        subcategory: row.subcategory.clone(),
    };
    rules(&v, Some(row.id))?;
    if is_recovery(&v.kind) {
        validate_recovery(tx, user, &mut v, Some(row.id)).await?;
    }
    Ok(())
}

pub(crate) async fn group_link_exists(tx: &mut Transaction<'_, Postgres>, id: i64) -> Result<bool> {
    Ok(sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM groups.expense_transaction_links WHERE transaction_entry_id=$1)",
    )
    .bind(id)
    .fetch_one(&mut **tx)
    .await?)
}

pub(crate) async fn outbox(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    event: &str,
    entity: i64,
) -> Result<()> {
    sqlx::query(
        "INSERT INTO finance.outbox_events (user_id, event_type, entity_id, status, created_at) \
         VALUES ($1,$2,$3,'PENDING',now())",
    )
    .bind(user)
    .bind(event)
    .bind(entity)
    .execute(&mut **tx)
    .await?;
    Ok(())
}
