//! Request logging and latency metrics.

use axum::{extract::Request, middleware::Next, response::Response};
use std::time::Instant;

pub async fn observe(req: Request, next: Next) -> Response {
    let start = Instant::now();
    let route = req
        .extensions()
        .get::<axum::extract::MatchedPath>()
        .map(|p| p.as_str().to_owned())
        .unwrap_or_else(|| "unmatched".into());
    let method = req.method().to_string();
    let response = next.run(req).await;
    let status = response.status().as_u16();
    let elapsed_ms = start.elapsed().as_millis() as u64;
    // Log the matched route only: paths can contain ids but never credentials,
    // and request bodies/headers (tokens, passwords) are deliberately omitted.
    if status >= 500 {
        tracing::error!(method = %method, uri = %route, status, elapsed_ms, "request failed");
    } else {
        tracing::info!(method = %method, uri = %route, status, elapsed_ms, "request");
    }
    metrics::histogram!("http_server_requests_seconds", "uri" => route, "method" => method, "status" => status.to_string()).record(start.elapsed().as_secs_f64());
    response
}
