use super::EXPENSE_COLS;
use super::SPLIT_COLS;
use super::helpers::members;
use super::models::{ExpenseRow, SplitRow};
use crate::Result;
use rust_decimal::Decimal;
use sqlx::{Postgres, Transaction};
use std::collections::HashMap;
use uuid::Uuid;

pub(crate) async fn group_name(tx: &mut Transaction<'_, Postgres>, group: i64) -> Result<String> {
    Ok(
        sqlx::query_scalar("SELECT name FROM groups.expense_groups WHERE id=$1")
            .bind(group)
            .fetch_optional(&mut **tx)
            .await?
            .unwrap_or_else(|| "the group".into()),
    )
}

pub(crate) async fn currency_of(tx: &mut Transaction<'_, Postgres>, group: i64) -> Result<String> {
    Ok(
        sqlx::query_scalar("SELECT currency FROM groups.expense_groups WHERE id=$1")
            .bind(group)
            .fetch_optional(&mut **tx)
            .await?
            .unwrap_or_else(|| "INR".into()),
    )
}

#[derive(Clone)]
pub(crate) struct Balance {
    pub(crate) user_id: Uuid,
    pub(crate) user_name: String,
    pub(crate) net: Decimal,
}

#[derive(Clone)]
pub(crate) struct Suggestion {
    pub(crate) from: Uuid,
    pub(crate) to: Uuid,
    pub(crate) amount: Decimal,
}

pub(crate) async fn balances(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
) -> Result<Vec<Balance>> {
    let members = members(tx, group).await?;
    let expenses = sqlx::query_as::<_, ExpenseRow>(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE group_id=$1 ORDER BY created_at DESC"
    ))
    .bind(group)
    .fetch_all(&mut **tx)
    .await?;
    let ids: Vec<i64> = expenses.iter().map(|e| e.id).collect();
    let splits = if ids.is_empty() {
        Vec::new()
    } else {
        sqlx::query_as::<_, SplitRow>(&format!(
            "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id = ANY($1)"
        ))
        .bind(&ids)
        .fetch_all(&mut **tx)
        .await?
    };
    let mut paid: HashMap<Uuid, Decimal> = HashMap::new();
    for e in &expenses {
        *paid.entry(e.paid_by).or_default() += e.amount;
    }
    let mut owed: HashMap<Uuid, Decimal> = HashMap::new();
    for s in &splits {
        *owed.entry(s.user_id).or_default() += s.amount;
    }
    Ok(members
        .into_iter()
        .map(|m| {
            let p = paid.get(&m.user_id).copied().unwrap_or(Decimal::ZERO);
            let o = owed.get(&m.user_id).copied().unwrap_or(Decimal::ZERO);
            Balance {
                user_id: m.user_id,
                user_name: m.name,
                net: (p - o).round_dp(2),
            }
        })
        .collect())
}

pub(crate) fn minimize_debts(balances: &[Balance], currency: &str) -> Vec<Suggestion> {
    let mut debt: Vec<(Uuid, Decimal)> = balances
        .iter()
        .filter(|b| b.net < Decimal::ZERO)
        .map(|b| (b.user_id, -b.net))
        .collect();
    let mut credit: Vec<(Uuid, Decimal)> = balances
        .iter()
        .filter(|b| b.net > Decimal::ZERO)
        .map(|b| (b.user_id, b.net))
        .collect();
    debt.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    credit.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let mut out = Vec::new();
    let (mut di, mut ci) = (0, 0);
    while di < debt.len() && ci < credit.len() {
        let amount = debt[di].1.min(credit[ci].1).round_dp(2);
        if amount <= Decimal::ZERO {
            if debt[di].1 <= Decimal::ZERO {
                di += 1;
            }
            if credit[ci].1 <= Decimal::ZERO {
                ci += 1;
            }
            continue;
        }
        out.push(Suggestion {
            from: debt[di].0,
            to: credit[ci].0,
            amount,
        });
        debt[di].1 -= amount;
        credit[ci].1 -= amount;
        if debt[di].1 <= Decimal::ZERO {
            di += 1;
        }
        if credit[ci].1 <= Decimal::ZERO {
            ci += 1;
        }
    }
    let _ = currency;
    out
}
