use super::models::{ExpenseRequest, ExpenseRow, MemberRow, SplitRow};
use crate::Result;
use crate::{bad, forbidden};
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde_json::Value;
use sqlx::{Postgres, Transaction};
use std::collections::HashSet;
use std::str::FromStr;
use uuid::Uuid;

pub(crate) fn parse_amount(v: &Value) -> Result<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).map_err(|_| bad("Invalid amount"))
}

pub(crate) fn receipt_owned(url: Option<&str>, user: Uuid) -> Result<()> {
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

pub(crate) fn split_equally(amount: Decimal, count: usize) -> Vec<Decimal> {
    if count == 0 {
        return Vec::new();
    }
    let total = (amount.round_dp(2) * Decimal::from(100))
        .to_i64()
        .unwrap_or(0);
    let base = total / count as i64;
    let rem = total % count as i64;
    (0..count)
        .map(|i| Decimal::new(base + if (i as i64) < rem { 1 } else { 0 }, 2))
        .collect()
}

pub(crate) fn split_by_percentage(amount: Decimal, percentages: &[Decimal]) -> Vec<Decimal> {
    let count = percentages.len();
    if count == 0 {
        return Vec::new();
    }
    let rounded = amount.round_dp(2);
    let total = (rounded * Decimal::from(100)).to_i64().unwrap_or(0);
    let mut cents = vec![0i64; count];
    let mut fracs = vec![Decimal::ZERO; count];
    let mut allocated = 0i64;
    for (i, pct) in percentages.iter().enumerate() {
        let raw = rounded * *pct;
        let floored = raw.trunc();
        cents[i] = floored.to_i64().unwrap_or(0);
        fracs[i] = raw - floored;
        allocated += cents[i];
    }
    let mut order: Vec<usize> = (0..count).collect();
    order.sort_by(|a, b| fracs[*b].cmp(&fracs[*a]).then(a.cmp(b)));
    let leftover = (total - allocated).max(0) as usize;
    for i in 0..leftover.min(count) {
        cents[order[i]] += 1;
    }
    cents.iter().map(|c| Decimal::new(*c, 2)).collect()
}

pub(crate) async fn insert_splits(
    tx: &mut Transaction<'_, Postgres>,
    expense: &ExpenseRow,
    members: &[MemberRow],
    request: &ExpenseRequest,
) -> Result<Vec<SplitRow>> {
    let accepted: Vec<&MemberRow> = members.iter().filter(|m| m.status == "ACCEPTED").collect();
    if accepted.is_empty() {
        return Ok(Vec::new());
    }
    let details = request.split_details.clone().unwrap_or_default();
    let member_ids: HashSet<Uuid> = accepted.iter().map(|m| m.user_id).collect();
    let detail_value = |user: Uuid| -> Option<Decimal> {
        details
            .iter()
            .find(|d| d.user_id == Some(user))
            .and_then(|d| d.value.as_ref())
            .and_then(|v| parse_amount(v).ok())
    };
    let amounts: Vec<Decimal> = match expense.split_type.as_str() {
        "PERCENTAGE" if !details.is_empty() => {
            let total: Decimal = details
                .iter()
                .filter(|d| d.user_id.is_some_and(|u| member_ids.contains(&u)))
                .filter_map(|d| d.value.as_ref())
                .filter_map(|v| parse_amount(v).ok())
                .sum();
            if (total - Decimal::from(100)).abs() >= Decimal::new(1, 2) {
                return Err(bad(&format!(
                    "Percentage splits must add up to 100 (got {total})"
                )));
            }
            let pcts: Vec<Decimal> = accepted
                .iter()
                .map(|m| detail_value(m.user_id).unwrap_or(Decimal::ZERO))
                .collect();
            split_by_percentage(expense.amount, &pcts)
        }
        "EXACT" | "SETTLEMENT" if !details.is_empty() => {
            let total: Decimal = details
                .iter()
                .filter(|d| d.user_id.is_some_and(|u| member_ids.contains(&u)))
                .filter_map(|d| d.value.as_ref())
                .filter_map(|v| parse_amount(v).ok())
                .sum();
            if total.round_dp(2) != expense.amount.round_dp(2) {
                return Err(bad(&format!(
                    "Exact splits must add up to the expense amount {} (got {total})",
                    expense.amount.normalize()
                )));
            }
            accepted
                .iter()
                .map(|m| detail_value(m.user_id).unwrap_or(Decimal::ZERO))
                .collect()
        }
        _ => split_equally(expense.amount, accepted.len()),
    };
    // One multi-row INSERT instead of one statement per member.
    let user_ids: Vec<Uuid> = accepted.iter().map(|m| m.user_id).collect();
    let names: Vec<String> = accepted.iter().map(|m| m.name.clone()).collect();
    let amts: Vec<Decimal> = (0..accepted.len())
        .map(|i| amounts.get(i).copied().unwrap_or(Decimal::ZERO))
        .collect();
    let settled: Vec<bool> = accepted
        .iter()
        .map(|m| m.user_id == expense.paid_by)
        .collect();
    sqlx::query(
        "INSERT INTO groups.expense_splits (shared_expense_id,user_id,user_name,amount,is_settled) \
         SELECT $1, u, n, a, s \
         FROM unnest($2::uuid[],$3::text[],$4::numeric[],$5::bool[]) AS t(u,n,a,s)",
    )
    .bind(expense.id)
    .bind(&user_ids)
    .bind(&names)
    .bind(&amts)
    .bind(&settled)
    .execute(&mut **tx)
    .await?;
    let rows: Vec<SplitRow> = (0..accepted.len())
        .map(|i| SplitRow {
            id: 0,
            shared_expense_id: expense.id,
            user_id: accepted[i].user_id,
            user_name: Some(accepted[i].name.clone()),
            amount: amounts.get(i).copied().unwrap_or(Decimal::ZERO),
            is_settled: accepted[i].user_id == expense.paid_by,
            settled_at: None,
        })
        .collect();
    Ok(rows)
}
