use super::*;
use axum::{
    extract::{Multipart, OriginalUri, Path, Query},
    http::{HeaderMap, HeaderValue},
    routing::{get, post},
};
use futures_util::TryStreamExt;
use hmac::{Hmac, Mac};
use lapin::{
    BasicProperties, Channel, Connection, ConnectionProperties, ExchangeKind, options::*,
    types::FieldTable,
};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use uuid::Uuid;

pub struct Storage {
    client: reqwest::Client,
    endpoint: String,
    access: String,
    secret: String,
    receipts: String,
    profiles: String,
    command_url: String,
    rabbit: tokio::sync::Mutex<(Connection, Channel)>,
    uploads: Semaphore,
}
fn hmac(key: &[u8], data: &str) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).unwrap();
    mac.update(data.as_bytes());
    mac.finalize().into_bytes().to_vec()
}
fn digest(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
impl Storage {
    pub async fn new() -> Result<Self> {
        let rabbit = Self::connect_rabbit().await?;
        let storage = Self {
            client: reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(5))
                .timeout(Duration::from_secs(120))
                .build()
                .map_err(Error::internal)?,
            endpoint: env("MINIO_ENDPOINT", "http://minio:9000"),
            access: required("MINIO_ACCESS_KEY"),
            secret: std::env::var("MINIO_SECRET_KEY")
                .unwrap_or_else(|_| required("MINIO_ROOT_PASSWORD")),
            receipts: env("MINIO_BUCKET", "receipts"),
            profiles: env("MINIO_PROFILE_BUCKET", "profiles"),
            command_url: env("COMMAND_SERVICE_URL", "http://command-service:8081"),
            rabbit: tokio::sync::Mutex::new(rabbit),
            uploads: Semaphore::new(8),
        };
        for bucket in [&storage.receipts, &storage.profiles] {
            let response = storage
                .request(reqwest::Method::HEAD, bucket, "", false, &digest(b""), None)
                .await?;
            if response.status() == StatusCode::NOT_FOUND {
                let response = storage
                    .request(reqwest::Method::PUT, bucket, "", false, &digest(b""), None)
                    .await?;
                if !response.status().is_success() && response.status() != StatusCode::CONFLICT {
                    return Err(Error::internal(format!(
                        "bucket creation returned {}",
                        response.status()
                    )));
                }
            } else if !response.status().is_success() {
                return Err(Error::internal(format!(
                    "bucket lookup returned {}",
                    response.status()
                )));
            }
            // Remove legacy public-read policies before opening the HTTP listener.
            let response = storage
                .request(
                    reqwest::Method::DELETE,
                    bucket,
                    "",
                    true,
                    &digest(b""),
                    None,
                )
                .await?;
            if !response.status().is_success() && response.status() != StatusCode::NOT_FOUND {
                return Err(Error::internal("Could not make bucket private"));
            }
        }
        Ok(storage)
    }
    async fn connect_rabbit() -> Result<(Connection, Channel)> {
        let mut url = url::Url::parse(&format!(
            "amqp://{}:{}/%2f",
            env("RABBITMQ_HOST", "rabbitmq"),
            env("RABBITMQ_PORT", "5672")
        ))
        .map_err(Error::internal)?;
        url.set_username(&required("RABBITMQ_USER"))
            .map_err(|_| Error::internal("Invalid RabbitMQ user"))?;
        url.set_password(Some(&required("RABBITMQ_PASSWORD")))
            .map_err(|_| Error::internal("Invalid RabbitMQ password"))?;
        let connection = Connection::connect(url.as_str(), ConnectionProperties::default())
            .await
            .map_err(Error::internal)?;
        let channel = connection.create_channel().await.map_err(Error::internal)?;
        channel
            .exchange_declare(
                "finance-events",
                ExchangeKind::Topic,
                ExchangeDeclareOptions {
                    durable: true,
                    ..Default::default()
                },
                FieldTable::default(),
            )
            .await
            .map_err(Error::internal)?;
        channel
            .exchange_declare(
                "finance-events-dlx",
                ExchangeKind::Direct,
                ExchangeDeclareOptions {
                    durable: true,
                    ..Default::default()
                },
                FieldTable::default(),
            )
            .await
            .map_err(Error::internal)?;
        for (queue, exchange, key) in [
            ("ocr-jobs", "finance-events", "ocr.job"),
            ("ocr-jobs-dead", "finance-events-dlx", "ocr.job.dead"),
        ] {
            channel
                .queue_declare(
                    queue,
                    QueueDeclareOptions {
                        durable: true,
                        ..Default::default()
                    },
                    FieldTable::default(),
                )
                .await
                .map_err(Error::internal)?;
            channel
                .queue_bind(
                    queue,
                    exchange,
                    key,
                    QueueBindOptions::default(),
                    FieldTable::default(),
                )
                .await
                .map_err(Error::internal)?;
        }
        channel
            .confirm_select(ConfirmSelectOptions::default())
            .await
            .map_err(Error::internal)?;
        Ok((connection, channel))
    }
    pub async fn ready(&self) -> Result<()> {
        let response = self
            .client
            .get(format!("{}/minio/health/ready", self.endpoint))
            .send()
            .await
            .map_err(Error::internal)?;
        if !response.status().is_success() {
            return Err(Error::internal("MinIO unavailable"));
        }
        self.rabbit_channel().await?;
        Ok(())
    }
    async fn rabbit_channel(&self) -> Result<Channel> {
        let mut rabbit = self.rabbit.lock().await;
        if !rabbit.0.status().connected() || !rabbit.1.status().connected() {
            *rabbit = Self::connect_rabbit().await?;
        }
        Ok(rabbit.1.clone())
    }
    async fn publish(&self, event: serde_json::Value) -> Result<()> {
        let channel = self.rabbit_channel().await?;
        let bytes = serde_json::to_vec(&event).map_err(Error::internal)?;
        let confirmation = channel
            .basic_publish(
                "finance-events",
                "ocr.job",
                BasicPublishOptions {
                    mandatory: true,
                    ..Default::default()
                },
                &bytes,
                BasicProperties::default()
                    .with_content_type("application/json".into())
                    .with_delivery_mode(2),
            )
            .await
            .map_err(Error::internal)?
            .await
            .map_err(Error::internal)?;
        match confirmation {
            lapin::publisher_confirm::Confirmation::Ack(None) => Ok(()),
            _ => Err(Error::internal("OCR job was not routed and confirmed")),
        }
    }
    async fn request(
        &self,
        method: reqwest::Method,
        bucket: &str,
        object: &str,
        policy: bool,
        payload_hash: &str,
        payload: Option<(reqwest::Body, u64)>,
    ) -> Result<reqwest::Response> {
        let mut url = url::Url::parse(&self.endpoint).map_err(Error::internal)?;
        {
            let mut segments = url
                .path_segments_mut()
                .map_err(|_| Error::internal("Invalid MinIO endpoint"))?;
            segments.clear().push(bucket);
            if !object.is_empty() {
                for segment in object.split('/') {
                    segments.push(segment);
                }
            }
        }
        if policy {
            url.set_query(Some("policy="));
        }
        let timestamp = chrono::Utc::now();
        let date = timestamp.format("%Y%m%d").to_string();
        let amzdate = timestamp.format("%Y%m%dT%H%M%SZ").to_string();
        let host = match url.port() {
            Some(port) => format!("{}:{port}", url.host_str().unwrap()),
            None => url.host_str().unwrap().to_string(),
        };
        let signed = "host;x-amz-content-sha256;x-amz-date";
        let canonical = format!(
            "{}\n{}\n{}\nhost:{}\nx-amz-content-sha256:{}\nx-amz-date:{}\n\n{}\n{}",
            method,
            url.path(),
            url.query().unwrap_or(""),
            host,
            payload_hash,
            amzdate,
            signed,
            payload_hash
        );
        let region = env("MINIO_REGION", "us-east-1");
        let scope = format!("{date}/{region}/s3/aws4_request");
        let to_sign = format!(
            "AWS4-HMAC-SHA256\n{amzdate}\n{scope}\n{}",
            digest(canonical.as_bytes())
        );
        let date_key = hmac(format!("AWS4{}", self.secret).as_bytes(), &date);
        let region_key = hmac(&date_key, &region);
        let service_key = hmac(&region_key, "s3");
        let signing_key = hmac(&service_key, "aws4_request");
        let authorization = format!(
            "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed}, Signature={}",
            self.access,
            hex::encode(hmac(&signing_key, &to_sign))
        );
        let mut request = self
            .client
            .request(method, url)
            .header("host", host)
            .header("x-amz-date", amzdate)
            .header("x-amz-content-sha256", payload_hash)
            .header("authorization", authorization);
        if let Some((body, size)) = payload {
            request = request.header("content-length", size).body(body);
        }
        request.send().await.map_err(Error::internal)
    }
}
fn storage(app: &App) -> &Storage {
    app.storage.as_deref().expect("storage mode")
}
fn safe_filename(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("upload");
    let safe: String = base
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || "._-".contains(c) {
                c
            } else {
                '_'
            }
        })
        .collect();
    if safe.is_empty() || safe == "." || safe == ".." {
        "upload".into()
    } else {
        safe
    }
}
fn content_type(name: &str) -> &'static str {
    match name
        .rsplit('.')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "pdf" => "application/pdf",
        "csv" => "text/csv; charset=utf-8",
        _ => "application/octet-stream",
    }
}
fn valid_magic(head: &[u8], kind: &str) -> bool {
    match kind {
        "image/png" => head.starts_with(b"\x89PNG\r\n\x1a\n"),
        "image/jpeg" => head.starts_with(b"\xff\xd8\xff"),
        "image/gif" => head.starts_with(b"GIF87a") || head.starts_with(b"GIF89a"),
        "image/webp" => head.starts_with(b"RIFF") && head.get(8..12) == Some(b"WEBP"),
        "application/pdf" => head.starts_with(b"%PDF"),
        "text/csv; charset=utf-8" => !head.is_empty() && !head.contains(&0),
        _ => false,
    }
}
fn upload_busy_response() -> Response {
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
async fn upload(
    State(app): State<App>,
    headers: HeaderMap,
    OriginalUri(uri): OriginalUri,
    Query(query): Query<std::collections::HashMap<String, String>>,
    mut multipart: Multipart,
) -> Result<Response> {
    let claims = auth::identity(&app, &headers).await?;
    let s = storage(&app);
    let _permit = match s.uploads.try_acquire() {
        Ok(permit) => permit,
        Err(_) => return Ok(upload_busy_response()),
    };
    let profile = uri.path() == "/api/profile/upload";
    let batch = uri.path().starts_with("/api/bill/batch-process/");
    let bill = uri.path().starts_with("/api/bill/");
    if !profile
        && uri
            .path()
            .rsplit('/')
            .next()
            .is_none_or(|v| !v.eq_ignore_ascii_case(&claims.user_id))
    {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "Access denied: user mismatch".into(),
        ));
    }
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
            format!("{}-batch-{}/", claims.user_id, Uuid::new_v4())
        } else {
            format!("{}/", claims.user_id)
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
            let mut event = json!({"userId":claims.user_id,"receiptId":receipt_id,"objectName":name,"bucket":bucket,"isBatch":if batch {"true"} else {"false"}});
            if let Some(c) = query.get("targetCurrency").filter(|v| !v.trim().is_empty()) {
                let c = c.trim().to_ascii_uppercase();
                if c.len() != 3 || !c.bytes().all(|b| b.is_ascii_uppercase()) {
                    return Err(Error::bad("Invalid target currency"));
                }
                event["targetCurrency"] = json!(c);
            }
            s.publish(event).await?;
            return Ok((StatusCode::ACCEPTED,Json(json!({"message":if batch {"Batch statement accepted for processing"} else {"Receipt accepted for OCR processing"},"receiptId":receipt_id,"url":url}))).into_response());
        }
        return Ok(Json(json!({"url":url})).into_response());
    }
    Err(Error::bad("Missing file part"))
}
async fn download(
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
        let claims = auth::identity(&app, &headers).await?;
        let allowed = s
            .client
            .get(format!("{}/upsert/storage-access", s.command_url))
            .query(&[("url", format!("/api/storage/{bucket}/{name}"))])
            .header("x-user-id", claims.user_id)
            .send()
            .await
            .map_err(Error::internal)?;
        if !allowed.status().is_success() {
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
pub fn routes() -> Router<App> {
    Router::new()
        .route("/api/receipt/upload/{id}", post(upload))
        .route("/api/profile/upload", post(upload))
        .route("/api/bill/process/{id}", post(upload))
        .route("/api/bill/batch-process/{id}", post(upload))
        .route("/api/storage/{bucket}/{*name}", get(download))
        .route(
            "/api/bill/health",
            get(|| async { Json(json!({"status":"UP"})) }),
        )
}
#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

    #[tokio::test]
    async fn busy_upload_closes_unread_request_connection() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                Router::new().route("/upload", post(|| async { upload_busy_response() })),
            )
            .await
            .unwrap();
        });
        let mut socket = tokio::net::TcpStream::connect(address).await.unwrap();
        // Send only part of a large body: the rejection must complete without
        // waiting for the client and must forbid reuse of the unread stream.
        socket.write_all(b"POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100000\r\n\r\npartial body").await.unwrap();
        let mut reader = BufReader::new(socket);
        let response = tokio::time::timeout(Duration::from_secs(2), async {
            let mut headers = String::new();
            loop {
                let mut line = String::new();
                assert!(reader.read_line(&mut line).await.unwrap() > 0);
                headers.push_str(&line);
                if line == "\r\n" {
                    break;
                }
            }
            assert!(headers.starts_with("HTTP/1.1 429"));
            assert!(
                headers
                    .to_ascii_lowercase()
                    .contains("connection: close\r\n")
            );
            assert!(headers.to_ascii_lowercase().contains("retry-after: 1\r\n"));
            let length: usize = headers
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length: ")
                        .map(|value| value.parse().unwrap())
                })
                .unwrap();
            let mut body = vec![0; length];
            reader.read_exact(&mut body).await.unwrap();
            assert!(String::from_utf8_lossy(&body).contains("Too many uploads"));
            assert_eq!(reader.read(&mut [0; 1]).await.unwrap(), 0);
        })
        .await;
        server.abort();
        response.expect("busy upload response must close promptly");
    }

    #[test]
    fn file_validation() {
        assert!(valid_magic(b"\x89PNG\r\n\x1a\n", "image/png"));
        assert!(!valid_magic(b"<script>", "image/png"));
        assert!(!valid_magic(b"RIFF0000HTML", "image/webp"));
        assert!(valid_magic(b"RIFF0000WEBP", "image/webp"));
        assert_eq!(content_type("x.svg"), "application/octet-stream");
        assert_eq!(safe_filename("../../a\"b.png"), "a_b.png");
    }
}
