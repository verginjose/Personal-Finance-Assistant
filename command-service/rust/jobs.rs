use super::App;
use crate::{cache, entries, notifications, subscriptions};
use chrono::{NaiveDateTime, Utc};
use rust_decimal::Decimal;
use std::collections::HashSet;
use std::time::Duration;
use uuid::Uuid;

const MOVEMENT: &str = "'SETTLEMENT','CREDIT_CARD_PAYMENT','MUTUAL_FUNDS_SIP',\
'STOCKS_AND_TRADING','CRYPTO','EMERGENCY_FUND','FIXED_DEPOSIT','GOAL',\
'OWN_ACCOUNT_TRANSFER','BORROWING','LENDING','LOAN_PRINCIPAL','INVESTMENT_WITHDRAWAL'";

pub fn spawn_all(a: App) {
    tokio::spawn(recurring(a.clone()));
    tokio::spawn(outbox(a.clone()));
    tokio::spawn(maintenance(a.clone()));
    tokio::spawn(subscription_refresh(a));
}

#[derive(Debug, sqlx::FromRow)]
struct RecurringRow {
    id: i64,
    user_id: Uuid,
    name: String,
    amount: Decimal,
    #[sqlx(rename = "type")]
    r#type: String,
    category: Option<String>,
    currency: String,
    description: Option<String>,
    recurring_period: Option<String>,
    transaction_kind: Option<String>,
    subcategory: Option<String>,
    next_run_date: Option<NaiveDateTime>,
}

async fn recurring(a: App) {
    let mut tick = tokio::time::interval(Duration::from_secs(3600));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tick.tick().await;
        if let Err(e) = process_recurring(&a).await {
            tracing::error!(error = %e, "recurring transaction pass failed");
        }
    }
}

async fn process_recurring(a: &App) -> sqlx::Result<()> {
    let mut batches = 0;
    loop {
        let rows = sqlx::query_as::<_, RecurringRow>(
            "SELECT id,user_id,name,amount,type::text AS type,category::text AS category,\
             currency,description,recurring_period::text AS recurring_period,\
             transaction_kind::text AS transaction_kind,subcategory,next_run_date \
             FROM finance.transaction_entries \
             WHERE recurring AND deleted_at IS NULL AND next_run_date <= now() \
             ORDER BY id LIMIT 500",
        )
        .fetch_all(&a.db)
        .await?;
        if rows.is_empty() {
            break;
        }
        for row in rows {
            if let Err(e) = process_one(a, &row).await {
                tracing::error!(error = %e, id = row.id, "recurring transaction failed");
            }
        }
        batches += 1;
        if batches >= 100 {
            break;
        }
    }
    Ok(())
}

async fn process_one(a: &App, row: &RecurringRow) -> sqlx::Result<()> {
    let Some(period) = row.recurring_period.clone() else {
        tracing::warn!(id = row.id, "recurring transaction has no period; skipping");
        return Ok(());
    };
    let mut tx = a.db.begin().await?;
    let current = sqlx::query_as::<_, RecurringRow>(
        "SELECT id,user_id,name,amount,type::text AS type,category::text AS category,\
         currency,description,recurring_period::text AS recurring_period,\
         transaction_kind::text AS transaction_kind,subcategory,next_run_date \
         FROM finance.transaction_entries WHERE id=$1 AND recurring AND deleted_at IS NULL FOR UPDATE",
    )
    .bind(row.id)
    .fetch_optional(&mut *tx)
    .await?;
    let Some(current) = current else {
        tx.commit().await?;
        return Ok(());
    };
    let now = Utc::now().naive_utc();
    let Some(mut temp) = current.next_run_date else {
        tx.commit().await?;
        return Ok(());
    };
    if temp > now {
        tx.commit().await?;
        return Ok(());
    }
    let mut missed = 0;
    while temp <= now {
        missed += 1;
        match entries::next_run(Some(temp), &period) {
            Some(next) => temp = next,
            None => break,
        }
        if missed > 1000 {
            tracing::warn!(id = current.id, "excessive missed periods; stopping");
            break;
        }
    }
    let aggregated = current.amount * Decimal::from(missed);
    let note = current.description.clone().unwrap_or_default().trim().to_string();
    let description = if missed > 1 {
        format!(
            "Auto-generated (aggregated {missed} missed periods){}",
            if note.is_empty() { String::new() } else { format!(": {note}") }
        )
    } else {
        format!(
            "Auto-generated{}",
            if note.is_empty() { String::new() } else { format!(": {note}") }
        )
    };
    let entry_id: i64 = sqlx::query_scalar(
        "INSERT INTO finance.transaction_entries \
         (user_id,name,amount,type,category,currency,description,created_at,updated_at,\
          recurring,transaction_kind,subcategory) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,now(),false,$9,$10) RETURNING id",
    )
    .bind(current.user_id)
    .bind(&current.name)
    .bind(aggregated)
    .bind(&current.r#type)
    .bind(&current.category)
    .bind(&current.currency)
    .bind(&description)
    .bind(now)
    .bind(&current.transaction_kind)
    .bind(&current.subcategory)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO finance.outbox_events (user_id,event_type,entity_id,status,created_at) \
         VALUES ($1,'CREATE',$2,'PENDING',now())",
    )
    .bind(current.user_id)
    .bind(entry_id)
    .execute(&mut *tx)
    .await?;
    sqlx::query(
        "UPDATE finance.transaction_entries SET next_run_date=$2, updated_at=now() WHERE id=$1",
    )
    .bind(current.id)
    .bind(temp)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;

    let message = format!(
        "Recurring transaction '{}' of ₹{aggregated} was logged automatically.",
        current.name
    );
    notifications::send_fields(
        a,
        current.user_id,
        "INFO",
        Some("recurring-processed"),
        &message,
        None,
        serde_json::json!({
            "status":"INFO","event":"recurring-processed","message":message,
        }),
    )
    .await;
    tracing::info!(id = current.id, missed, next = %temp, "recurring transaction processed");
    Ok(())
}

async fn outbox(a: App) {
    loop {
        tokio::time::sleep(Duration::from_secs(1)).await;
        let events: Vec<(i64, Uuid, String, i64)> = match sqlx::query_as(
            "SELECT id, user_id, event_type, entity_id FROM finance.outbox_events \
             WHERE status='PENDING' ORDER BY created_at LIMIT 200",
        )
        .fetch_all(&a.db)
        .await
        {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(error = %e, "failed to read outbox events");
                continue;
            }
        };
        for (id, user, kind, entity) in events {
            let mut ok = cache::evict_user_keys(&a, &HashSet::from([user])).await;
            // Deferred per-member fan-out for group expenses (see add_expense_core).
            if ok && kind == "FANOUT" {
                if let Err(e) = crate::groups::fanout_expense(&a, entity).await {
                    tracing::warn!(error = ?e, event = id, "expense fan-out failed; will retry");
                    ok = false;
                }
            }
            if ok {
                let _ = sqlx::query("UPDATE finance.outbox_events SET status='PROCESSED' WHERE id=$1")
                    .bind(id)
                    .execute(&a.db)
                    .await;
            } else {
                tracing::warn!(id, %user, "outbox delivery failed; will retry");
            }
        }
    }
}

async fn maintenance(a: App) {
    loop {
        if let Err(e) = sqlx::query(
            "DELETE FROM finance.idempotency_records WHERE created_at < now() - interval '24 hours'",
        )
        .execute(&a.db)
        .await
        {
            tracing::warn!(error = %e, "idempotency cleanup failed");
        }
        if let Err(e) = sqlx::query(
            "DELETE FROM finance.notifications WHERE created_at < now() - interval '30 days'",
        )
        .execute(&a.db)
        .await
        {
            tracing::warn!(error = %e, "notification prune failed");
        }
        tokio::time::sleep(Duration::from_secs(24 * 3600)).await;
    }
}

async fn subscription_refresh(a: App) {
    if let Err(e) = refresh_subscriptions(&a).await {
        tracing::warn!(error = %e, "startup subscription refresh failed");
    }
    loop {
        tokio::time::sleep(Duration::from_secs(24 * 3600)).await;
        if let Err(e) = refresh_subscriptions(&a).await {
            tracing::warn!(error = %e, "daily subscription refresh failed");
        }
    }
}

async fn refresh_subscriptions(a: &App) -> sqlx::Result<()> {
    let sql = format!(
        "SELECT user_id,name,amount,currency,coalesce(recurring_period::text,'MONTHLY'),created_at \
         FROM finance.transaction_entries WHERE recurring AND deleted_at IS NULL AND type='EXPENSE' \
         AND coalesce(transaction_kind,'STANDARD') NOT IN ('TRANSFER','INVESTMENT') \
         AND category NOT IN ({MOVEMENT})"
    );
    let rows: Vec<(Uuid, String, Decimal, String, String, NaiveDateTime)> =
        sqlx::query_as(&sql).fetch_all(&a.db).await?;
    for (user, name, amount, currency, period, created) in rows {
        if let Err(e) = subscriptions::upsert_for_created(
            &a.db,
            user,
            &name,
            amount,
            &currency,
            &period,
            created.date(),
        )
        .await
        {
            tracing::warn!(error = %e, %user, "subscription upsert failed");
        }
    }
    Ok(())
}
