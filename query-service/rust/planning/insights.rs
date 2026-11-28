use super::{forecast, goals, trends};
use crate::app::App;
use crate::config::env;
use crate::error::Result;
use crate::ledger;
use crate::request::Params;
use chrono::Utc;
use ledger::decimal;
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde_json::{Value, json};
use std::time::Duration;
use uuid::Uuid;
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
