use axum::{
    Json,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use chrono::{NaiveDate, NaiveDateTime};
use serde_json::{Value, json};
use sqlx::PgPool;
use std::{collections::HashMap, sync::Arc};
use uuid::Uuid;
pub(crate) type Params = HashMap<String, String>;
#[derive(Clone)]
pub(crate) struct App {
    pub(crate) db: PgPool,
    pub(crate) http: reqwest::Client,
    pub(crate) fx: Arc<tokio::sync::Mutex<HashMap<String, (std::time::Instant, Value)>>>,
    pub(crate) fx_locks: Arc<tokio::sync::Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    pub(crate) metrics: metrics_exporter_prometheus::PrometheusHandle,
    pub(crate) redis: redis::aio::ConnectionManager,
    pub(crate) advice: Arc<tokio::sync::Mutex<HashMap<String, (std::time::Instant, Value)>>>,
}
#[derive(Debug)]
pub(crate) struct Error(pub(crate) StatusCode, pub(crate) String);
pub(crate) type Result<T> = std::result::Result<T, Error>;
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(json!({"error":self.0.canonical_reason(),"message":self.1})),
        )
            .into_response()
    }
}
impl From<sqlx::Error> for Error {
    fn from(e: sqlx::Error) -> Self {
        tracing::error!(error=%e,"query failed");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
pub(crate) fn bad(m: &str) -> Error {
    Error(StatusCode::BAD_REQUEST, m.into())
}
pub(crate) fn env(k: &str, d: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| d.into())
}
pub(crate) fn user(h: &HeaderMap, p: &Params) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|s| s.to_str().ok())
        .ok_or_else(|| Error(StatusCode::UNAUTHORIZED, "Missing user context".into()))?;
    // Match Spring's SecurityInterceptor: a supplied userId that differs from
    // the header is rejected as forbidden before its format is even considered.
    if let Some(other) = p.get("userId")
        && !other.eq_ignore_ascii_case(raw)
    {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "User ID mismatch".into(),
        ));
    }
    Uuid::parse_str(raw).map_err(|_| bad("Invalid user ID"))
}
pub(crate) fn required(p: &Params, keys: &[&str]) -> Result<()> {
    for key in keys {
        if !p.contains_key(*key) {
            return Err(bad(&format!(
                "Required request parameter '{key}' is missing"
            )));
        }
    }
    Ok(())
}
// Spring's Pageable resolver rejects these rather than clamping, so clients
// see the same 400s against either implementation.
pub(crate) fn paging(p: &Params, default_size: i64) -> Result<(i64, i64)> {
    let page = integer(p, "page", 0)?;
    let size = integer(p, "size", default_size)?;
    if page < 0 {
        return Err(bad("Page index must not be less than zero"));
    }
    if size < 1 {
        return Err(bad("Page size must not be less than one"));
    }
    Ok((page, size))
}
pub(crate) fn integer(p: &Params, k: &str, d: i64) -> Result<i64> {
    p.get(k)
        .map(|v| v.parse().map_err(|_| bad("Invalid integer")))
        .unwrap_or(Ok(d))
}
pub(crate) fn camel(v: Value) -> Value {
    match v {
        Value::Array(v) => Value::Array(v.into_iter().map(camel).collect()),
        Value::Object(o) => Value::Object(
            o.into_iter()
                .map(|(k, v)| {
                    let mut next = false;
                    let name: String = k
                        .chars()
                        .filter_map(|c| {
                            if c == '_' {
                                next = true;
                                None
                            } else if next {
                                next = false;
                                Some(c.to_ascii_uppercase())
                            } else {
                                Some(c)
                            }
                        })
                        .collect();
                    {
                        let mut value = camel(v);
                        if [
                            "createdAt",
                            "updatedAt",
                            "joinedAt",
                            "nextRunDate",
                            "expenseDate",
                            "settledAt",
                            "completedAt",
                            "deletedAt",
                            "detectedAt",
                            "lastSeenAt",
                        ]
                        .contains(&name.as_str())
                            && let Some(text) = value.as_str()
                            && text.contains('T')
                            && !text.ends_with('Z')
                        {
                            value = json!(format!("{text}Z"));
                        }
                        (name, value)
                    }
                })
                .collect(),
        ),
        v => v,
    }
}
pub(crate) async fn list(db: &PgPool, sql: &str, id: Uuid) -> Result<Vec<Value>> {
    Ok(sqlx::query_scalar::<_, Value>(sql)
        .bind(id)
        .fetch_all(db)
        .await?
        .into_iter()
        .map(camel)
        .collect())
}
pub(crate) fn page(rows: Vec<Value>, count: i64, number: i64, size: i64) -> Value {
    let pages = (count + size - 1) / size;
    json!({"number":number,"size":size,"totalElements":count,"totalPages":pages,"first":number==0,"last":number+1>=pages,"numberOfElements":rows.len(),"empty":rows.is_empty(),"content":rows})
}
pub(crate) fn dates(p: &Params, shift: bool) -> Result<(Option<NaiveDateTime>, Option<NaiveDateTime>)> {
    let offset = if shift {
        integer(p, "tzOffsetMinutes", 0)?.clamp(-1080, 1080)
    } else {
        0
    };
    let parse = |k: &str, end: bool| -> Result<Option<NaiveDateTime>> {
        match p.get(k) {
            None => Ok(None),
            Some(v) => {
                let t = if v.len() == 10 {
                    NaiveDate::parse_from_str(v, "%Y-%m-%d")
                        .map_err(|_| bad("Invalid date"))?
                        .and_hms_opt(
                            if end { 23 } else { 0 },
                            if end { 59 } else { 0 },
                            if end { 59 } else { 0 },
                        )
                        .unwrap()
                } else {
                    NaiveDateTime::parse_from_str(v, "%Y-%m-%dT%H:%M:%S%.f")
                        .map_err(|_| bad("Invalid date"))?
                };
                Ok(Some(t - chrono::Duration::minutes(offset)))
            }
        }
    };
    Ok((parse("startDate", false)?, parse("endDate", true)?))
}
