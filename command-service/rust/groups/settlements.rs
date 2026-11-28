use super::balance::{balances, currency_of, minimize_debts};
use super::expenses::{ExpenseOutcome, add_expense_core, apply_outcome};
use super::helpers::{Notice, actor, q_uuid, require_member};
use super::models::{ExpenseRequest, SplitDetail};
use crate::{App, Params, Result};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::HeaderMap,
    response::{IntoResponse, Response},
};
use rust_decimal::Decimal;
use serde_json::json;
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

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
