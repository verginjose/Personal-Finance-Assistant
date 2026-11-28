use super::{
    backpressure::{drain_rejected_upload, reject_busy_upload},
    object_store::digest,
    storage,
    validation::{content_type, safe_filename, valid_magic},
};
use crate::{App, Error, Result};
use axum::{
    Json,
    extract::{Multipart, OriginalUri, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use serde_json::json;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use uuid::Uuid;

pub(super) async fn upload(
    State(app): State<App>,
    headers: HeaderMap,
    OriginalUri(uri): OriginalUri,
    Query(query): Query<std::collections::HashMap<String, String>>,
    mut multipart: Multipart,
) -> Result<Response> {
    let user_id = super::access::upload_user(&headers, uri.path())?.to_string();
    let s = storage(&app);
    let _permit = match s.uploads.try_acquire() {
        Ok(permit) => permit,
        Err(_) => return Ok(reject_busy_upload(multipart).await),
    };
    let profile = uri.path() == "/api/profile/upload";
    let batch = uri.path().starts_with("/api/bill/batch-process/");
    let bill = uri.path().starts_with("/api/bill/");
    while let Some(mut field) = multipart
        .next_field()
        .await
        .map_err(|_| Error::bad("Malformed multipart upload"))?
    {
        if field.name() != Some("file") {
            continue;
        }
        let mut filename = safe_filename(field.file_name().unwrap_or("upload"));
        let declared = field
            .content_type()
            .unwrap_or("")
            .split(';')
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        // Verify the declared media type against bytes, then normalize the
        // stored extension. Some phone/dataset images have a .jpg name but PNG
        // bytes; downloads must still use the verified type with nosniff.
        let kind = match declared.as_str() {
            "image/png" => "image/png",
            "image/jpeg" => "image/jpeg",
            "image/gif" => "image/gif",
            "image/webp" => "image/webp",
            "application/pdf" => "application/pdf",
            _ => content_type(&filename),
        };
        if kind != content_type(&filename) {
            let extension = match kind {
                "image/png" => "png",
                "image/jpeg" => "jpg",
                "image/gif" => "gif",
                "image/webp" => "webp",
                "application/pdf" => "pdf",
                _ => "bin",
            };
            filename = format!("{filename}.{extension}");
        }
        let csv = kind.starts_with("text/csv");
        let image = kind.starts_with("image/");
        let allowed = (image || (bill && (kind == "application/pdf" || csv)))
            && (declared == kind
                || declared == "application/octet-stream"
                || (csv && ["text/csv", "application/vnd.ms-excel"].contains(&declared.as_str())));
        if !allowed {
            return Err(Error(
                StatusCode::UNSUPPORTED_MEDIA_TYPE,
                "Unsupported file type".into(),
            ));
        }
        let file = tempfile::NamedTempFile::new().map_err(Error::internal)?;
        let mut writer = tokio::fs::File::from_std(file.reopen().map_err(Error::internal)?);
        let mut hasher = Sha256::new();
        let mut head = Vec::new();
        let mut size = 0;
        while let Some(chunk) = field
            .chunk()
            .await
            .map_err(|_| Error::bad("Invalid upload"))?
        {
            size += chunk.len() as u64;
            if size > 12 * 1024 * 1024 {
                return Err(Error(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "Upload exceeds 12 MiB".into(),
                ));
            }
            if head.len() < 512 {
                head.extend_from_slice(&chunk[..chunk.len().min(512 - head.len())]);
            }
            hasher.update(&chunk);
            writer.write_all(&chunk).await.map_err(Error::internal)?;
        }
        writer.flush().await.map_err(Error::internal)?;
        if !valid_magic(&head, kind) {
            return Err(Error(
                StatusCode::UNSUPPORTED_MEDIA_TYPE,
                "File contents do not match the declared type".into(),
            ));
        }
        let prefix = if profile {
            String::new()
        } else if batch {
            format!("{}-batch-{}/", user_id, Uuid::new_v4())
        } else {
            format!("{}/", user_id)
        };
        let name = format!("{prefix}{}-{filename}", Uuid::new_v4());
        let bucket = if profile { &s.profiles } else { &s.receipts };
        let stream = tokio_util::io::ReaderStream::new(
            tokio::fs::File::open(file.path())
                .await
                .map_err(Error::internal)?,
        );
        let response = s
            .request(
                reqwest::Method::PUT,
                bucket,
                &name,
                false,
                &hex::encode(hasher.finalize()),
                Some((reqwest::Body::wrap_stream(stream), size)),
            )
            .await?;
        if !response.status().is_success() {
            return Err(Error::internal(format!(
                "MinIO upload returned {}",
                response.status()
            )));
        }
        let url = format!("/api/storage/{bucket}/{name}");
        if bill {
            let receipt_id = Uuid::new_v4().to_string();
            let mut event = json!({"userId":user_id,"receiptId":receipt_id,"objectName":name,"bucket":bucket,"isBatch":if batch {"true"} else {"false"}});
            if let Some(c) = query.get("targetCurrency").filter(|v| !v.trim().is_empty()) {
                let c = c.trim().to_ascii_uppercase();
                if c.len() != 3 || !c.bytes().all(|b| b.is_ascii_uppercase()) {
                    return Err(Error::bad("Invalid target currency"));
                }
                event["targetCurrency"] = json!(c);
            }
            if let Err(error) = s.publish(event).await {
                if error.0 == StatusCode::TOO_MANY_REQUESTS {
                    // A confirmed broker rejection means the job was not
                    // queued. Remove its object instead of leaking uploads.
                    let cleanup = s
                        .request(
                            reqwest::Method::DELETE,
                            bucket,
                            &name,
                            false,
                            &digest(b""),
                            None,
                        )
                        .await;
                    if !matches!(cleanup, Ok(ref response) if response.status().is_success()) {
                        tracing::warn!("failed to remove a rejected OCR upload");
                    }
                    // Trailing multipart fields may still be in flight. Use
                    // the same bounded drain as the concurrent-upload guard.
                    drop(field);
                    let mut response =
                        drain_rejected_upload(multipart, error.into_response()).await;
                    response
                        .headers_mut()
                        .insert("retry-after", HeaderValue::from_static("15"));
                    return Ok(response);
                }
                return Err(error);
            }
            return Ok((StatusCode::ACCEPTED,Json(json!({"message":if batch {"Batch statement accepted for processing"} else {"Receipt accepted for OCR processing"},"receiptId":receipt_id,"url":url}))).into_response());
        }
        return Ok(Json(json!({"url":url})).into_response());
    }
    Err(Error::bad("Missing file part"))
}
