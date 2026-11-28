use super::{
    rate_limit::{is_local_test, rate},
    tokens::{bearer, validate},
};
use crate::app::App;
use crate::error::{Error, Result};
use axum::extract::State;
use axum::http::StatusCode;
use axum::http::{HeaderMap, HeaderValue};
use axum::response::IntoResponse;
use axum::response::Response;
use uuid::Uuid;

pub(crate) async fn verify(State(app): State<App>, headers: HeaderMap) -> Result<Response> {
    // This endpoint is reachable only by the edge proxy (NetworkPolicy).
    let skip_rate_limit = is_local_test(&headers);
    let uri = headers
        .get("x-forwarded-uri")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(Error::unauthorized)?;
    let method = headers
        .get("x-forwarded-method")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("GET");
    let url =
        url::Url::parse(&format!("http://edge{uri}")).map_err(|_| Error::bad("Invalid URI"))?;
    let path = url.path();
    for (k, v) in url.query_pairs() {
        if k == "userId" && !v.is_empty() && Uuid::parse_str(&v).is_err() {
            return Err(Error::bad("userId must be a valid UUID"));
        }
    }
    if path.starts_with("/api/auth/") {
        let ip = headers
            .get("x-real-ip")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<std::net::IpAddr>().ok())
            .map(|v| v.to_string())
            .unwrap_or_else(|| "unknown".into());
        let credentials = path == "/api/auth/login" || path == "/api/auth/register";
        if !skip_rate_limit {
            rate(
                &app,
                &format!("ip:{ip}:{path}"),
                if credentials { 5.0 } else { 100.0 },
                if credentials { 20.0 } else { 200.0 },
            )
            .await?;
        }
        return Ok(StatusCode::OK.into_response());
    }
    if path.starts_with("/api/storage/profiles/")
        || [
            "/api/upsert/health",
            "/api/analytics/health",
            "/api/bill/health",
        ]
        .contains(&path)
    {
        return Ok(StatusCode::OK.into_response());
    }
    let stream = path.starts_with("/api/upsert/notifications/stream");
    let query_ticket = url
        .query_pairs()
        .find(|(k, _)| k == "ticket")
        .map(|(_, v)| v.into_owned());
    let token = bearer(&headers)
        .ok()
        .map(str::to_owned)
        .or(if stream { query_ticket } else { None })
        .ok_or_else(Error::unauthorized)?;
    let claims = validate(&app, &token, stream).await?;
    if headers.get("authorization").is_none() && claims.purpose.as_deref() != Some("stream") {
        return Err(Error::unauthorized());
    }
    let policy = if path.starts_with("/api/analytics/") {
        Some(("analytics", 10., 20.))
    } else if path.starts_with("/api/upsert/") && !path.starts_with("/api/upsert/notifications/") {
        if method == "GET" {
            Some(("read", 50., 100.))
        } else {
            Some(("write", 20., 40.))
        }
    } else {
        None
    };
    if let Some((group, r, b)) = policy
        && !skip_rate_limit
    {
        rate(&app, &format!("{group}:{}", claims.user_id), r, b).await?;
    }
    let mut response = StatusCode::OK.into_response();
    response.headers_mut().insert(
        "x-user-id",
        HeaderValue::from_str(&claims.user_id).map_err(Error::internal)?,
    );
    Ok(response)
}
