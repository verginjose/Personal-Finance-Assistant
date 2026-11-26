use super::*;
use chrono::{Datelike, Months, Utc};
use ledger::{decimal, money};
use rust_decimal::{Decimal, prelude::ToPrimitive};
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
async fn generate_insights(a: &App, id: Uuid, p: &Params) -> Result<Value> {
    let totals = ledger::summary(a, id, p).await?;
    let income = decimal(&totals["totalIncome"]).unwrap_or_default();
    let expense = decimal(&totals["totalExpense"]).unwrap_or_default();
    let net = income - expense;
    let goal_list = goals(a, id).await?;
    let mut forecasts = Vec::new();
    for g in goal_list.iter().take(100) {
        if !g["completed"].as_bool().unwrap_or(false) {
            forecasts.push(forecast(a, id, g["id"].as_i64().unwrap_or(0)).await?);
        }
    }
    let trends = trends(a, id).await?;
    let key = env("GROQ_API_KEY", "");
    if !key.is_empty() {
        let prompt = format!(
            "Write precise personal-finance insights as JSON with insights (title,message,type WARNING/TIP/ACHIEVEMENT/INFO,priority 1-5) and summary. Cite only the supplied data; no currency figures in prose, hype or emojis. Totals: {totals}. Goal forecasts: {}. Budget trends: {trends}.",
            json!(forecasts)
        );
        let response=a.http.post(env("GROQ_URL","https://api.groq.com/openai/v1/chat/completions")).bearer_auth(key).json(&json!({"model":env("GROQ_MODEL","llama-3.3-70b-versatile"),"temperature":0.3,"response_format":{"type":"json_object"},"messages":[{"role":"user","content":prompt}]})).send().await;
        if let Ok(response) = response
            && let Ok(body) = response.json::<Value>().await
            && let Some(content) = body["choices"][0]["message"]["content"].as_str()
            && let Ok(mut v) = serde_json::from_str::<Value>(content)
            && let Some(items) = v["insights"].as_array_mut()
        {
            for item in items.iter_mut() {
                let kind = item["type"].as_str().unwrap_or("INFO").to_uppercase();
                item["type"] = json!(if ["WARNING", "TIP", "ACHIEVEMENT", "INFO"]
                    .contains(&kind.as_str())
                {
                    kind
                } else {
                    "INFO".into()
                });
                item["priority"] = json!(item["priority"].as_i64().unwrap_or(3).clamp(1, 5));
                if item["title"].as_str().is_none_or(str::is_empty) {
                    item["title"] = json!("Insight");
                }
                if !item["message"].is_string() {
                    item["message"] = json!("");
                }
            }
            items.sort_by_key(|v| v["priority"].as_i64().unwrap_or(3));
            if !items.is_empty() {
                v["generatedAt"] = json!(Utc::now().naive_utc().to_string().replace(' ', "T"));
                return Ok(v);
            }
        }
    }
    let mut items = vec![];
    if net < Decimal::ZERO {
        items.push(json!({"title":"Spending exceeds income","message":"You've spent more than you earned overall. Consider reviewing your biggest expense categories.","type":"WARNING","priority":1}));
    } else if income > Decimal::ZERO {
        let rate = (net / income * Decimal::from(100)).to_f64().unwrap_or(0.);
        items.push(json!({"title":if rate>=20.{"Great savings rate"}else{"Boost your savings"},"message":format!("Your overall savings rate is {rate:.1}%."),"type":if rate>=20.{"ACHIEVEMENT"}else{"TIP"},"priority":2}));
    }
    for f in forecasts.iter().take(2) {
        let behind = f["onTrack"] == json!(false);
        items.push(json!({"title":format!("{} {}",f["goalName"].as_str().unwrap_or("Goal"),if behind{"is behind"}else if f["monthlyVelocity"].is_null()||f["monthlyVelocity"]==json!(0){"has no momentum"}else{"is on track"}),"message":f["message"],"type":if behind{"WARNING"}else{"TIP"},"priority":if behind{1}else{3}}));
    }
    if items.is_empty() {
        items.push(json!({"title":"Start tracking","message":"Add income and expenses to see insights based on your activity.","type":"TIP","priority":3}));
    }
    Ok(
        json!({"insights":items,"summary":"Here is what your recent activity suggests.","generatedAt":Utc::now().naive_utc().to_string().replace(' ',"T")}),
    )
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

pub async fn insights(a: &App, id: Uuid, p: &Params) -> Result<Value> {
    use redis::AsyncCommands;
    let generation: Option<String> = a
        .redis
        .clone()
        .get(format!("finance:gen:user:{id}"))
        .await
        .unwrap_or(None);
    let key = format!(
        "{}:{}:{}:{}",
        id,
        generation.unwrap_or_default(),
        p.get("startDate").map(String::as_str).unwrap_or("MIN"),
        p.get("endDate").map(String::as_str).unwrap_or("MAX")
    );
    if p.get("refresh").map(String::as_str) != Some("true")
        && let Some((at, value)) = a.advice.lock().await.get(&key)
        && at.elapsed() < Duration::from_secs(3600)
    {
        return Ok(value.clone());
    }
    let value = generate_insights(a, id, p).await?;
    let mut cache = a.advice.lock().await;
    if cache.len() >= 256 {
        cache.clear();
    }
    cache.insert(key, (std::time::Instant::now(), value.clone()));
    Ok(value)
}
