use super::EXPENSE_COLS;
use super::SPLIT_COLS;
use super::models::{ExpenseRow, SplitRow};
use crate::not_found;
use crate::{App, Result};
use rust_decimal::Decimal;
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

struct NewPersonalEntry<'a> {
    user: Uuid,
    name: &'a str,
    amount: Decimal,
    transaction_type: &'a str,
    category: &'a str,
    currency: &'a str,
    description: &'a str,
}

async fn insert_entry(
    tx: &mut Transaction<'_, Postgres>,
    entry: NewPersonalEntry<'_>,
) -> Result<i64> {
    Ok(sqlx::query_scalar::<_, i64>(
        "INSERT INTO finance.transaction_entries \
         (user_id,name,amount,type,category,currency,description,created_at,updated_at,\
          recurring,transaction_kind) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,now(),now(),false,'STANDARD') RETURNING id",
    )
    .bind(entry.user)
    .bind(entry.name)
    .bind(entry.amount)
    .bind(entry.transaction_type)
    .bind(entry.category)
    .bind(entry.currency)
    .bind(entry.description)
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
                NewPersonalEntry {
                    user,
                    name: &name,
                    amount,
                    transaction_type: r#type,
                    category: &category,
                    currency: &expense.currency,
                    description: &description,
                },
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
