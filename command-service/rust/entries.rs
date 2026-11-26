use super::*;
use crate::{cache, subscriptions};
use chrono::{NaiveDateTime, Utc};
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::Value;
use sqlx::{Postgres, Transaction};
use std::str::FromStr;
use uuid::Uuid;

const INCOME_CATEGORIES: &[&str] = &[
    "SALARY", "COMMISSION", "CAPITAL_GAINS", "FREELANCE", "BUSINESS", "INVESTMENTS",
    "RENTAL_INCOME", "DIVIDENDS", "INTEREST", "BONUS", "PENSION", "GOVT_BENEFITS",
    "CASHBACK_REWARDS", "GIFTS_RECEIVED", "TAX_REFUND", "SIDE_HUSTLE", "OTHER_INCOME",
];
const MOVEMENT_CATEGORIES: &[&str] = &[
    "SETTLEMENT", "CREDIT_CARD_PAYMENT", "MUTUAL_FUNDS_SIP", "STOCKS_AND_TRADING", "CRYPTO",
    "EMERGENCY_FUND", "FIXED_DEPOSIT", "GOAL", "OWN_ACCOUNT_TRANSFER", "BORROWING", "LENDING",
    "LOAN_PRINCIPAL", "INVESTMENT_WITHDRAWAL",
];
const KINDS: &[&str] = &["STANDARD", "TRANSFER", "INVESTMENT", "REFUND", "REIMBURSEMENT"];
const PERIODS: &[&str] = &["DAILY", "WEEKLY", "MONTHLY", "YEARLY"];
const CATEGORIES: &[&str] = &[
    "SALARY", "COMMISSION", "CAPITAL_GAINS", "FREELANCE", "BUSINESS", "INVESTMENTS",
    "RENTAL_INCOME", "DIVIDENDS", "INTEREST", "BONUS", "PENSION", "GOVT_BENEFITS",
    "CASHBACK_REWARDS", "GIFTS_RECEIVED", "TAX_REFUND", "SIDE_HUSTLE", "OTHER_INCOME", "RENT",
    "HOME_LOAN_EMI", "FURNITURE", "HOUSEHOLD_SUPPLIES", "DOMESTIC_HELP", "SOCIETY_MAINTENANCE",
    "HOME_MAINTENANCE", "PROPERTY_TAX", "ELECTRICITY", "WATER", "GAS", "INTERNET", "MOBILE_PHONE",
    "OTT_SUBSCRIPTIONS", "GROCERIES", "RESTAURANTS", "FOOD_DELIVERY", "COFFEE_AND_SNACKS", "FUEL",
    "PUBLIC_TRANSPORT", "CAB_AND_AUTO", "VEHICLE_EMI", "VEHICLE_MAINTENANCE", "PARKING_AND_TOLLS",
    "FLIGHT_AND_TRAIN", "DOCTOR_AND_CLINIC", "MEDICINES", "HEALTH_INSURANCE", "GYM_AND_FITNESS",
    "MENTAL_WELLNESS", "TUITION_AND_FEES", "BOOKS_AND_COURSES", "COACHING", "STUDENT_LOAN_EMI",
    "CLOTHING", "ELECTRONICS", "HOME_APPLIANCES", "PERSONAL_CARE", "GIFTS_GIVEN",
    "MOVIES_AND_EVENTS", "GAMING", "SPORTS_AND_HOBBIES", "BOOKS_AND_MAGAZINES",
    "TRAVEL_VACATION", "HOTEL_AND_STAYS", "BANK_CHARGES", "CARD_FEES", "LOAN_INTEREST",
    "SOFTWARE_SUBSCRIPTIONS", "CLOUD_STORAGE", "PROFESSIONAL_MEMBERSHIPS", "LIFE_INSURANCE",
    "VEHICLE_INSURANCE", "CREDIT_CARD_PAYMENT", "LOAN_REPAYMENT", "MUTUAL_FUNDS_SIP",
    "STOCKS_AND_TRADING", "CRYPTO", "EMERGENCY_FUND", "FIXED_DEPOSIT", "CHARITY_AND_DONATIONS",
    "TAXES", "FINES_AND_PENALTIES", "PETS", "CHILDCARE", "ELDER_CARE", "GOAL", "SETTLEMENT",
    "OTHERS", "OWN_ACCOUNT_TRANSFER", "BORROWING", "LENDING", "LOAN_PRINCIPAL",
    "INVESTMENT_WITHDRAWAL",
];

const ENTRY_COLS: &str = "id,user_id,name,amount,type::text AS type,category::text AS category,\
currency,description,created_at,updated_at,recurring,recurring_period::text AS recurring_period,\
next_run_date,deleted_at,receipt_url,transaction_kind::text AS transaction_kind,\
original_transaction_id,subcategory";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EntryRequest {
    pub id: Option<i64>,
    pub user_id: Option<Uuid>,
    pub name: Option<String>,
    pub amount: Option<Value>,
    pub r#type: Option<String>,
    pub category: Option<String>,
    pub currency: Option<String>,
    pub description: Option<String>,
    pub recurring: Option<bool>,
    pub recurring_period: Option<String>,
    pub created_at: Option<String>,
    pub receipt_url: Option<String>,
    pub transaction_kind: Option<String>,
    pub original_transaction_id: Option<i64>,
    pub subcategory: Option<String>,
}

#[derive(Clone)]
struct Validated {
    name: String,
    amount: Decimal,
    r#type: String,
    kind: String,
    category: Option<String>,
    currency: String,
    description: Option<String>,
    recurring: bool,
    period: Option<String>,
    created_at: Option<NaiveDateTime>,
    receipt_url: Option<String>,
    original_id: Option<i64>,
    subcategory: Option<String>,
}

#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
pub struct EntryRow {
    pub id: i64,
    pub user_id: Uuid,
    pub name: String,
    pub amount: Decimal,
    #[sqlx(rename = "type")]
    pub r#type: String,
    pub category: Option<String>,
    pub currency: String,
    pub description: Option<String>,
    pub created_at: NaiveDateTime,
    pub updated_at: Option<NaiveDateTime>,
    pub recurring: bool,
    pub recurring_period: Option<String>,
    pub next_run_date: Option<NaiveDateTime>,
    pub deleted_at: Option<NaiveDateTime>,
    pub receipt_url: Option<String>,
    pub transaction_kind: Option<String>,
    pub original_transaction_id: Option<i64>,
    pub subcategory: Option<String>,
}

pub fn valid_category(category: &str) -> bool {
    CATEGORIES.contains(&category)
}

fn is_recovery(kind: &str) -> bool {
    kind == "REFUND" || kind == "REIMBURSEMENT"
}

pub fn reportable(kind: &str, category: Option<&str>) -> bool {
    kind != "TRANSFER"
        && kind != "INVESTMENT"
        && !MOVEMENT_CATEGORIES.contains(&category.unwrap_or("OTHERS"))
}

fn parse_amount(v: &Value) -> Result<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).map_err(|_| bad("Invalid amount format"))
}

fn require_owned(url: Option<&str>, user: Uuid) -> Result<()> {
    if let Some(url) = url
        && !url.is_empty()
    {
        let prefix = format!("/api/storage/receipts/{user}");
        if !url.starts_with(&format!("{prefix}/")) && !url.starts_with(&format!("{prefix}-batch-")) {
            return Err(forbidden("Receipt was not uploaded by this user"));
        }
    }
    Ok(())
}

fn basic(req: &EntryRequest) -> Result<Validated> {
    let name = req.name.clone().unwrap_or_default().trim().to_string();
    if name.is_empty() {
        return Err(bad("Name is required"));
    }
    if name.chars().count() > 100 {
        return Err(bad("Name cannot exceed 100 characters"));
    }
    let amount = parse_amount(req.amount.as_ref().ok_or_else(|| bad("Amount is required"))?)?;
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
    if description.as_ref().is_some_and(|d| d.chars().count() > 500) {
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

fn rules(v: &Validated, self_id: Option<i64>) -> Result<()> {
    if !is_recovery(&v.kind) {
        let category = v
            .category
            .as_ref()
            .ok_or_else(|| bad("Category is required"))?;
        if v.original_id.is_some() {
            return Err(bad("Only refunds and reimbursements can link to an expense"));
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

async fn validate_recovery(
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

async fn validate(
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
async fn validate_patched(
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

async fn group_link_exists(
    tx: &mut Transaction<'_, Postgres>,
    id: i64,
) -> Result<bool> {
    Ok(sqlx::query_scalar::<_, bool>(
        "SELECT EXISTS(SELECT 1 FROM groups.expense_transaction_links WHERE transaction_entry_id=$1)",
    )
    .bind(id)
    .fetch_one(&mut **tx)
    .await?)
}

async fn outbox(
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

pub fn next_run(created: Option<NaiveDateTime>, period: &str) -> Option<NaiveDateTime> {
    let base = created.unwrap_or_else(|| Utc::now().naive_utc());
    let next = match period {
        "DAILY" => base + chrono::Days::new(1),
        "WEEKLY" => base + chrono::Days::new(7),
        "MONTHLY" => base.checked_add_months(chrono::Months::new(1)).unwrap_or(base),
        "YEARLY" => base.checked_add_months(chrono::Months::new(12)).unwrap_or(base),
        _ => base.checked_add_months(chrono::Months::new(1)).unwrap_or(base),
    };
    next.date().and_hms_opt(0, 0, 0)
}

async fn insert(
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

async fn replay(a: &App, user: Uuid, key: &str) -> Result<Value> {
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

pub async fn create_bulk(
    a: &App,
    user: Uuid,
    reqs: Vec<EntryRequest>,
) -> Result<Vec<Value>> {
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

async fn detect_subscription(a: &App, row: &EntryRow) {
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

fn reportable_text(row: &EntryRow) -> bool {
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
        return Err(forbidden("User is not authorized to update this transaction"));
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

pub async fn patch_amount(
    a: &App,
    id: i64,
    user: Uuid,
    amount: Value,
) -> Result<Value> {
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
        return Err(forbidden("User is not authorized to delete this transaction"));
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

async fn require_no_recoveries(
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
async fn adjust_allocation(
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
    let delta = if change_allocation { diff } else { -allocation_amount };
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
