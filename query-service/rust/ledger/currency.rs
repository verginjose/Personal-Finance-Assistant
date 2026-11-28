use crate::app::App;
use crate::config::env;
use crate::error::Result;
use crate::request::Params;
use rust_decimal::Decimal;
use serde_json::Value;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;
use uuid::Uuid;

pub async fn decorate(a: &App, id: Uuid, p: &Params, rows: &mut [Value]) -> Result<()> {
    let target = if let Some(t) = p.get("targetCurrency") {
        Some(t.trim().to_uppercase())
    } else {
        sqlx::query_scalar::<_, String>("SELECT default_currency FROM auth.users WHERE id=$1")
            .bind(id)
            .fetch_optional(&a.db)
            .await?
    };
    for row in rows {
        row["amountDefault"] = Value::Null;
        let Some(target) = target.as_ref() else {
            continue;
        };
        let source = row["currency"].as_str().unwrap_or("").to_uppercase();
        if let Some(amount) = decimal(&row["amount"]) {
            if source == *target {
                row["amountDefault"] = money(amount)
            } else if let Some(rate) = rate(a, &source, target).await {
                row["amountDefault"] = money((amount * rate).round_dp_with_strategy(
                    2,
                    rust_decimal::RoundingStrategy::MidpointAwayFromZero,
                ));
            }
        }
    }
    Ok(())
}
pub fn decimal(v: &Value) -> Option<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).ok()
}
pub fn money(d: Decimal) -> Value {
    serde_json::from_str(&d.to_string()).unwrap_or(Value::Null)
}
async fn rate(a: &App, from: &str, to: &str) -> Option<Decimal> {
    if ![from, to]
        .iter()
        .all(|s| s.len() == 3 && s.bytes().all(|b| b.is_ascii_uppercase()))
    {
        return None;
    }
    let gate = {
        let mut gates = a.fx_locks.lock().await;
        if gates.len() >= 256 && !gates.contains_key(from) {
            gates.clear();
        }
        gates
            .entry(from.into())
            .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
            .clone()
    };
    let _guard = gate.lock().await;
    let cached = a.fx.lock().await.get(from).cloned();
    if let Some((at, v)) = &cached
        && at.elapsed() < Duration::from_secs(21600)
    {
        return decimal(&v["rates"][to]);
    }
    let value = match a
        .http
        .get(env("FX_RATES_URL", "https://api.frankfurter.dev/v1/latest"))
        .query(&[("base", from)])
        .timeout(Duration::from_secs(5))
        .send()
        .await
    {
        Ok(r) if r.status().is_success() => r.json::<Value>().await.ok(),
        _ => None,
    };
    let (at, value) = if let Some(value) = value {
        (std::time::Instant::now(), value)
    } else {
        (
            std::time::Instant::now() - Duration::from_secs(21540),
            cached.map(|(_, v)| v).unwrap_or(Value::Null),
        )
    };
    let result = decimal(&value["rates"][to]);
    let mut cache = a.fx.lock().await;
    if cache.len() >= 256 && !cache.contains_key(from) {
        cache.clear();
    }
    cache.insert(from.into(), (at, value));
    result
}
