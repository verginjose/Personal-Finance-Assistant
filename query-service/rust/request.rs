use crate::error::{Error, Result, bad};
use axum::http::{HeaderMap, StatusCode};
use chrono::{NaiveDate, NaiveDateTime};
use std::collections::HashMap;
use uuid::Uuid;

pub type Params = HashMap<String, String>;

pub fn user(h: &HeaderMap, p: &Params) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|s| s.to_str().ok())
        .ok_or_else(|| Error(StatusCode::UNAUTHORIZED, "Missing user context".into()))?;
    // Match Spring's SecurityInterceptor: a supplied userId that differs from
    // the header is rejected as forbidden before its format is even considered.
    if let Some(other) = p.get("userId")
        && !other.eq_ignore_ascii_case(raw)
    {
        return Err(Error(StatusCode::FORBIDDEN, "User ID mismatch".into()));
    }
    Uuid::parse_str(raw).map_err(|_| bad("Invalid user ID"))
}
pub fn required(p: &Params, keys: &[&str]) -> Result<()> {
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
pub fn paging(p: &Params, default_size: i64) -> Result<(i64, i64)> {
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
pub fn integer(p: &Params, k: &str, d: i64) -> Result<i64> {
    p.get(k)
        .map(|v| v.parse().map_err(|_| bad("Invalid integer")))
        .unwrap_or(Ok(d))
}
pub fn dates(p: &Params, shift: bool) -> Result<(Option<NaiveDateTime>, Option<NaiveDateTime>)> {
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
