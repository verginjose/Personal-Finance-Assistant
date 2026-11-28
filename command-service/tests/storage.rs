use axum::{Router, http::StatusCode, routing::post};
use pfa_command::storage::{
    backpressure::{reject_busy_upload, upload_busy_response},
    broker::confirmed_publish,
    validation::{content_type, safe_filename, valid_magic},
};
use std::time::Duration;
use tokio::io::AsyncWriteExt;

use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};

#[test]
fn full_ocr_queue_is_busy_not_a_false_acceptance() {
    let error = confirmed_publish(lapin::publisher_confirm::Confirmation::Nack(None)).unwrap_err();
    assert_eq!(error.0, StatusCode::TOO_MANY_REQUESTS);
    assert!(error.1.contains("15 seconds"));
    assert!(confirmed_publish(lapin::publisher_confirm::Confirmation::Ack(None)).is_ok());
}

#[test]
fn missing_publisher_confirmation_is_not_accepted() {
    let error =
        confirmed_publish(lapin::publisher_confirm::Confirmation::NotRequested).unwrap_err();
    assert_eq!(error.0, StatusCode::SERVICE_UNAVAILABLE);
}

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

#[tokio::test]
async fn busy_upload_drains_body_before_closing_connection() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new()
                .route("/upload", post(reject_busy_upload))
                .layer(axum::extract::DefaultBodyLimit::max(12 * 1024 * 1024)),
        )
        .await
        .unwrap();
    });
    let mut socket = tokio::net::TcpStream::connect(address).await.unwrap();
    let start = b"--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n";
    let mut body = start.to_vec();
    body.extend(vec![1; 300000]);
    body.extend(b"\r\n--boundary--\r\n");
    let headers = format!(
        "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=boundary\r\nContent-Length: {}\r\n\r\n",
        body.len()
    );
    socket.write_all(headers.as_bytes()).await.unwrap();
    socket.write_all(&body[..start.len() + 10]).await.unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(25), socket.read(&mut [0; 1]))
            .await
            .is_err(),
        "rejection must not close an in-flight body"
    );
    socket.write_all(&body[start.len() + 10..]).await.unwrap();
    let mut response = Vec::new();
    tokio::time::timeout(Duration::from_secs(2), socket.read_to_end(&mut response))
        .await
        .unwrap()
        .unwrap();
    server.abort();
    let response = String::from_utf8(response).unwrap();
    assert!(response.starts_with("HTTP/1.1 429"));
    assert!(response.contains("Too many uploads in progress"));
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
