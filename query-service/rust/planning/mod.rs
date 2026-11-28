//! Planning read models, with separate forecast and cached insight modules.

use crate::app::App;
use crate::data::list;
use crate::error::{Error, Result, bad};
use crate::ledger;
use crate::request::{Params, integer};
use axum::http::StatusCode;
use serde_json::{Value, json};
use uuid::Uuid;
mod forecasts;
mod insights;
pub(crate) use forecasts::forecast;
pub(crate) use insights::insights;

pub async fn goals(a: &App, id: Uuid) -> Result<Vec<Value>> {
    list(&a.db,"SELECT jsonb_build_object('id',id,'name',name,'targetAmount',target_amount,'savedAmount',saved_amount,'progressPercentage',CASE WHEN target_amount=0 THEN 0 ELSE least(100,100.0*saved_amount/target_amount) END,'currency',currency,'description',description,'deadline',deadline,'completed',saved_amount>=target_amount,'createdAt',created_at,'completedAt',completed_at,'priority',priority) FROM finance.savings_goals WHERE user_id=$1 AND active ORDER BY created_at DESC",id).await
}
pub async fn budgets(a: &App, id: Uuid) -> Result<Value> {
    let sql = format!(
        "{} ,util AS(SELECT b.*,coalesce((SELECT sum(report_amount) FROM reporting r WHERE r.user_id=b.user_id AND report_type='EXPENSE' AND category=b.expense_category AND r.created_at>=coalesce(b.custom_start_date::timestamp,date_trunc(CASE WHEN b.period='WEEKLY' THEN 'week' ELSE 'month' END,current_timestamp)::timestamp) AND r.created_at<=coalesce(b.custom_end_date::timestamp+interval '1 day'-interval '1 microsecond',current_timestamp::timestamp)),0) spent FROM finance.category_budgets b WHERE user_id=$1 AND active), pct AS(SELECT *,CASE WHEN budget_amount>0 THEN round(spent/budget_amount,4)*100 ELSE 0 END percent FROM util) SELECT jsonb_build_object('budgetId',id,'expenseCategory',expense_category,'budgetAmount',budget_amount,'spentAmount',spent,'utilizationPercentage',least(percent,200),'period',period,'currency',currency,'status',CASE WHEN percent>=100 THEN 'EXCEEDED' WHEN percent>=80 THEN 'WARNING' ELSE 'SAFE' END) FROM pct ORDER BY created_at DESC",
        ledger::reporting()
    );
    Ok(Value::Array(
        sqlx::query_scalar(&sql).bind(id).fetch_all(&a.db).await?,
    ))
}
pub async fn trends(a: &App, id: Uuid) -> Result<Value> {
    let sql = format!(
        "{} ,totals AS(SELECT category,coalesce(sum(report_amount) FILTER(WHERE created_at>=date_trunc('month',current_timestamp)),0) current,coalesce(sum(report_amount) FILTER(WHERE created_at<date_trunc('month',current_timestamp)),0) previous FROM reporting WHERE user_id=$1 AND report_type='EXPENSE' AND created_at>=date_trunc('month',current_timestamp)-interval '1 month' AND created_at<=current_timestamp GROUP BY category) SELECT jsonb_build_object('budgetId',b.id,'category',b.expense_category,'currentPeriodSpent',coalesce(t.current,0),'previousPeriodSpent',coalesce(t.previous,0),'percentageChange',CASE WHEN coalesce(t.previous,0)=0 THEN CASE WHEN coalesce(t.current,0)>0 THEN 100 ELSE 0 END ELSE round((t.current-t.previous)/t.previous,4)*100 END,'trend',CASE WHEN coalesce(t.current,0)>coalesce(t.previous,0) THEN 'INCREASED' WHEN coalesce(t.current,0)<coalesce(t.previous,0) THEN 'DECREASED' ELSE 'NO_CHANGE' END) FROM finance.category_budgets b LEFT JOIN totals t ON t.category=b.expense_category WHERE b.user_id=$1 AND b.active ORDER BY b.created_at DESC",
        ledger::reporting()
    );
    Ok(Value::Array(
        sqlx::query_scalar(&sql).bind(id).fetch_all(&a.db).await?,
    ))
}
pub async fn handle(a: &App, id: Uuid, path: &str, p: &Params) -> Result<Value> {
    match path {
        "/upsert/goals" => Ok(Value::Array(goals(a, id).await?)),
        "/upsert/budgets" => budgets(a, id).await,
        "/upsert/subscriptions" => {
            let offset = integer(p, "tzOffsetMinutes", 0)?.clamp(-1080, 1080);
            let rows:Vec<Value>=sqlx::query_scalar("SELECT jsonb_build_object('id',id,'name',name,'amount',amount,'currency',currency,'period',period,'nextChargeDate',next_charge_date,'daysUntilCharge',coalesce(next_charge_date-(current_timestamp+$2*interval '1 minute')::date,0),'active',active) FROM finance.subscriptions WHERE user_id=$1 AND active ORDER BY coalesce(next_charge_date-(current_timestamp+$2*interval '1 minute')::date,0),name").bind(id).bind(offset as i32).fetch_all(&a.db).await?;
            Ok(Value::Array(
                rows.into_iter()
                    .map(|mut r| {
                        let days = r["daysUntilCharge"].as_i64().unwrap_or(0);
                        r["alert"] = json!(if days == 0 {
                            "Charging today!".into()
                        } else if days <= 3 {
                            format!("Charge in {days} day(s)")
                        } else if days <= 7 {
                            format!("Charge in {days} days")
                        } else {
                            format!(
                                "Next charge: {}",
                                r["nextChargeDate"].as_str().unwrap_or("null")
                            )
                        });
                        r
                    })
                    .collect(),
            ))
        }
        _ => {
            let g = path
                .strip_prefix("/upsert/goals/")
                .and_then(|v| v.strip_suffix("/transactions"))
                .ok_or_else(|| Error(StatusCode::NOT_FOUND, "Not found".into()))?
                .parse()
                .map_err(|_| bad("Invalid goal ID"))?;
            let owned = sqlx::query_scalar::<_, bool>(
                "SELECT EXISTS(SELECT 1 FROM finance.savings_goals WHERE user_id=$1 AND id=$2)",
            )
            .bind(id)
            .bind(g)
            .fetch_one(&a.db)
            .await?;
            if !owned {
                return Err(Error(StatusCode::FORBIDDEN, "Not authorized".into()));
            }
            {
                let mut params = p.clone();
                params.entry("size".into()).or_insert_with(|| "8".into());
                ledger::entries(a, id, &params, None, false, Some(g)).await
            }
        }
    }
}
