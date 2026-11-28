use crate::{Result, bad};
use chrono::{NaiveDate, NaiveDateTime};
use serde_json::Value;

pub fn money(d: rust_decimal::Decimal) -> Value {
    serde_json::from_str(&d.to_string()).unwrap_or(Value::Null)
}
pub fn ts(v: &chrono::NaiveDateTime) -> String {
    v.to_string().replace(' ', "T")
}

/// Accept both naive timestamps and the browser's `toISOString()` form
/// (RFC 3339 with `Z`/offset), normalising offsets to UTC.
pub fn datetime(value: &str) -> Result<NaiveDateTime> {
    let value = value.trim();
    if value.is_empty() {
        return Err(bad("Invalid date"));
    }
    if let Ok(parsed) = chrono::DateTime::parse_from_rfc3339(value) {
        return Ok(parsed.naive_utc());
    }
    for format in [
        "%Y-%m-%dT%H:%M:%S%.f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S%.f",
        "%Y-%m-%dT%H:%M",
    ] {
        if let Ok(parsed) = NaiveDateTime::parse_from_str(value, format) {
            return Ok(parsed);
        }
    }
    if let Ok(parsed) = NaiveDate::parse_from_str(value, "%Y-%m-%d")
        && let Some(datetime) = parsed.and_hms_opt(0, 0, 0)
    {
        return Ok(datetime);
    }
    Err(bad("Invalid date"))
}

pub fn date(value: &str) -> Result<NaiveDate> {
    Ok(datetime(value)?.date())
}
