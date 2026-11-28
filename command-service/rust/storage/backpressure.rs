//! Bounded multipart draining and retryable upload rejections.

use crate::Error;
use axum::{
    extract::Multipart,
    http::{HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use std::time::Duration;

pub fn upload_busy_response() -> Response {
    let mut response = Error(
        StatusCode::TOO_MANY_REQUESTS,
        "Too many uploads in progress".into(),
    )
    .into_response();
    // The multipart body has not been consumed. Hyper closes this HTTP/1
    // connection after the early response; tell the proxy not to pool it.
    // Otherwise a later POST can race that close and become a 500/502.
    response
        .headers_mut()
        .insert("connection", HeaderValue::from_static("close"));
    response
        .headers_mut()
        .insert("retry-after", HeaderValue::from_static("1"));
    response
}
pub async fn reject_busy_upload(multipart: Multipart) -> Response {
    drain_rejected_upload(multipart, upload_busy_response()).await
}
pub(super) async fn drain_rejected_upload(
    mut multipart: Multipart,
    mut response: Response,
) -> Response {
    // An early close can interrupt the proxy's in-flight POST body and turn
    // this 429 into a broken-pipe 502. Discard the already bounded body without
    // allocating a spool or holding an upload slot. Slow clients cannot hold
    // this rejection indefinitely.
    let _ = tokio::time::timeout(Duration::from_secs(5), async {
        while let Some(mut field) = multipart.next_field().await? {
            while field.chunk().await?.is_some() {}
        }
        Ok::<(), axum::extract::multipart::MultipartError>(())
    })
    .await;
    response
        .headers_mut()
        .insert("connection", HeaderValue::from_static("close"));
    response
}
