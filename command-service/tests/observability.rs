use axum::{Router, http::StatusCode, middleware, routing as rt};
use pfa_command::observability::observe;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[tokio::test]
async fn transaction_ids_do_not_create_metric_series() {
    let recorder = metrics_exporter_prometheus::PrometheusBuilder::new().build_recorder();
    let handle = recorder.handle();
    metrics::set_global_recorder(recorder).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let router = Router::new()
        .route(
            "/upsert/delete/{id}",
            rt::delete(|| async { StatusCode::NO_CONTENT }),
        )
        .layer(middleware::from_fn(observe));
    let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let known = (90000..90100).map(|id| ("DELETE", format!("/upsert/delete/{id}"), 204));
    let unknown = (91000..91003).map(|id| ("GET", format!("/unknown/{id}"), 404));
    for (method, path, status) in known.chain(unknown) {
        let mut socket = tokio::net::TcpStream::connect(address).await.unwrap();
        socket.write_all(format!("{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").as_bytes()).await.unwrap();
        let mut response = Vec::new();
        tokio::time::timeout(Duration::from_secs(2), socket.read_to_end(&mut response))
            .await
            .unwrap()
            .unwrap();
        assert!(
            String::from_utf8(response)
                .unwrap()
                .starts_with(&format!("HTTP/1.1 {status}"))
        );
    }
    server.abort();
    let rendered = handle.render();
    let labels: std::collections::BTreeSet<_> = rendered
        .lines()
        .filter_map(|line| {
            line.split_once("uri=\"")
                .map(|(_, rest)| rest.split('"').next().unwrap())
        })
        .collect();
    assert!(labels.contains("/upsert/delete/{id}"));
    assert!(
        labels
            .iter()
            .all(|label| matches!(*label, "/upsert/delete/{id}" | "unmatched"))
    );
}
