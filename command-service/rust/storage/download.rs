use super::{
    object_store::digest,
    storage,
    validation::{content_type, safe_filename},
};
use crate::{App, Error, Result, request::header_user};
use axum::{
    body::Body,
    extract::{Path, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use futures_util::TryStreamExt;

pub(super) async fn download(
    State(app): State<App>,
    headers: HeaderMap,
    Path((bucket, name)): Path<(String, String)>,
) -> Result<Response> {
    let s = storage(&app);
    if bucket != s.receipts && bucket != s.profiles {
        return Err(Error(StatusCode::NOT_FOUND, "Not found".into()));
    }
    if name.is_empty() || name.contains("..") || name.contains('\\') {
        return Err(Error::bad("Invalid object name"));
    }
    if bucket == s.receipts {
        let user_id = header_user(&headers)?;
        let url = format!("/api/storage/{bucket}/{name}");
        if !super::access::can_read_receipt(&app.db, user_id, &url).await? {
            return Err(Error(StatusCode::NOT_FOUND, "Not found".into()));
        }
    }
    let object = s
        .request(
            reqwest::Method::GET,
            &bucket,
            &name,
            false,
            &digest(b""),
            None,
        )
        .await?;
    if !object.status().is_success() {
        return Err(Error(StatusCode::NOT_FOUND, "Not found".into()));
    }
    let kind = content_type(&name);
    let length = object.content_length();
    let body = Body::from_stream(object.bytes_stream().map_err(std::io::Error::other));
    let mut response = body.into_response();
    let h = response.headers_mut();
    h.insert("content-type", HeaderValue::from_static(kind));
    h.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    h.insert(
        "content-security-policy",
        HeaderValue::from_static("default-src 'none'; sandbox"),
    );
    h.insert("x-frame-options", HeaderValue::from_static("DENY"));
    h.insert(
        "cache-control",
        HeaderValue::from_static("private, max-age=300"),
    );
    h.insert(
        "content-disposition",
        HeaderValue::from_str(&format!(
            "{}; filename=\"{}\"",
            if kind.starts_with("image/") {
                "inline"
            } else {
                "attachment"
            },
            safe_filename(&name)
        ))
        .map_err(Error::internal)?,
    );
    if let Some(length) = length {
        h.insert(
            "content-length",
            HeaderValue::from_str(&length.to_string()).unwrap(),
        );
    }
    Ok(response)
}
