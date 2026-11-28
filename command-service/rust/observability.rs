//! Request logging and latency metrics.

use axum::{extract::Request, middleware::Next, response::Response};

pub async fn observe(req: Request, next: Next) -> Response {
    let started = std::time::Instant::now();
    let method = req.method().to_string();
    let path = req
        .extensions()
        .get::<axum::extract::MatchedPath>()
        .map(|route| route.as_str().to_owned())
        .unwrap_or_else(|| "unmatched".to_string());
    let response = next.run(req).await;
    let status = response.status().as_u16();
    let elapsed_ms = started.elapsed().as_millis() as u64;
    if path.starts_with("/actuator/") {
        tracing::debug!(method = %method, uri = %path, status, elapsed_ms, "request");
    } else if status >= 500 {
        tracing::error!(method = %method, uri = %path, status, elapsed_ms, "request failed");
    } else {
        tracing::info!(method = %method, uri = %path, status, elapsed_ms, "request");
    }
    metrics::histogram!(
        "http_server_requests_seconds",
        "uri" => path,
        "method" => method,
        "status" => status.to_string()
    )
    .record(started.elapsed().as_secs_f64());
    response
}
