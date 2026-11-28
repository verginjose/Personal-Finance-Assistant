use crate::app::App;
use crate::error::{Error, Result, bad};
use crate::ledger;
use axum::http::StatusCode;
use chrono::NaiveDate;
use chrono::{Datelike, Months, Utc};
use ledger::{decimal, money};
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde_json::{Value, json};
use uuid::Uuid;
fn months_between(start: NaiveDate, end: NaiveDate) -> i64 {
    let n = (end.year() - start.year()) as i64 * 12 + end.month() as i64 - start.month() as i64;
    n - if n > 0 && end.day() < start.day() {
        1
    } else {
        0
    }
}
pub async fn forecast(a: &App, id: Uuid, g: i64) -> Result<Value> {
    let goal: Option<Value> =
        sqlx::query_scalar("SELECT to_jsonb(g) FROM finance.savings_goals g WHERE id=$1")
            .bind(g)
            .fetch_optional(&a.db)
            .await?;
    let goal = goal.ok_or_else(|| bad("Goal not found"))?;
    if goal["user_id"].as_str() != Some(&id.to_string()) {
        return Err(Error(StatusCode::FORBIDDEN, "Not authorized".into()));
    }
    let target = decimal(&goal["target_amount"]).unwrap_or_default();
    let saved = decimal(&goal["saved_amount"]).unwrap_or_default();
    let mut out = json!({"goalId":g,"goalName":goal["name"],"targetAmount":money(target),"savedAmount":money(saved),"monthlyVelocity":null,"requiredMonthly":null,"onTrack":null,"estimatedMonthsRemaining":null,"estimatedCompletionDate":null,"message":null});
    if saved >= target {
        out["message"] = json!("Goal is already completed!");
        out["estimatedMonthsRemaining"] = json!(0);
        return Ok(out);
    }
    if saved.is_zero() {
        out["message"] = json!("No contributions yet. Start saving to see a forecast.");
        out["monthlyVelocity"] = json!(0);
        return Ok(out);
    }
    let today = Utc::now().date_naive();
    let created =
        NaiveDate::parse_from_str(&goal["created_at"].as_str().unwrap_or("")[..10], "%Y-%m-%d")
            .map_err(|_| bad("Invalid goal creation date"))?;
    let active = months_between(created, today).max(1);
    let velocity = (saved / Decimal::from(active))
        .round_dp_with_strategy(2, rust_decimal::RoundingStrategy::MidpointAwayFromZero);
    out["monthlyVelocity"] = money(velocity);
    if velocity <= Decimal::ZERO {
        out["message"] = json!("Not enough positive velocity to forecast.");
        return Ok(out);
    }
    let remaining = target - saved;
    let months = (remaining / velocity)
        .round_dp_with_strategy(2, rust_decimal::RoundingStrategy::ToPositiveInfinity)
        .trunc()
        .to_i64()
        .unwrap_or(0)
        .max(0);
    out["estimatedMonthsRemaining"] = json!(months);
    out["estimatedCompletionDate"] = json!(
        today
            .checked_add_months(Months::new(months.min(120000) as u32))
            .map(|d| d.to_string())
    );
    let rounded = |v: Decimal| {
        format!(
            "₹{}",
            v.round_dp_with_strategy(0, rust_decimal::RoundingStrategy::MidpointAwayFromZero)
        )
    };
    if let Some(deadline) = goal["deadline"].as_str() {
        let deadline =
            NaiveDate::parse_from_str(deadline, "%Y-%m-%d").map_err(|_| bad("Invalid deadline"))?;
        let left = months_between(today, deadline).max(1);
        let required = (remaining / Decimal::from(left))
            .round_dp_with_strategy(2, rust_decimal::RoundingStrategy::ToPositiveInfinity);
        let on_track = velocity >= required;
        out["requiredMonthly"] = money(required);
        out["onTrack"] = json!(on_track);
        out["message"] = json!(format!(
            "{}needs {}/mo, saving {}/mo for the deadline in {} month{}.",
            if on_track { "On track: " } else { "Behind: " },
            rounded(required),
            rounded(velocity),
            left,
            if left == 1 { "" } else { "s" }
        ));
    } else {
        out["message"] = json!(format!(
            "At {}/mo, this finishes in {months} months.",
            rounded(velocity)
        ));
    }
    Ok(out)
}
