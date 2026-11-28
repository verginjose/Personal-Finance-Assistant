//! Request logging and latency metrics.

use axum::{extract::Request, middleware::Next, response::Response};

pub async fn observe(req: Request, next: Next) -> Response {
    let now = std::time::Instant::now();
    let path = req.uri().path();
    let route = if path.starts_with("/upsert/groups/") {
        "/upsert/groups/{id}"
    } else if path.starts_with("/upsert/entries/") {
        "/upsert/entries/{id}"
    } else if path.starts_with("/analytics/goals/") {
        "/analytics/goals/{id}/forecast"
    } else {
        path
    }
    .to_string();
    let method = req.method().to_string();
    let r = next.run(req).await;
    let status = r.status().as_u16();
    let elapsed_ms = now.elapsed().as_millis() as u64;
    if route.starts_with("/actuator/") {
        // Probes and scrapes are constant background noise; keep them at debug.
        tracing::debug!(method = %method, uri = %route, status, elapsed_ms, "request");
    } else if status >= 500 {
        tracing::error!(method = %method, uri = %route, status, elapsed_ms, "request failed");
    } else {
        tracing::info!(method = %method, uri = %route, status, elapsed_ms, "request");
    }
    metrics::histogram!("http_server_requests_seconds","uri"=>route,"method"=>method,"status"=>status.to_string()).record(now.elapsed().as_secs_f64());
    r
}
