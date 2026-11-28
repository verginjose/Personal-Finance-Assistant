use axum::{
    body::Body,
    extract::FromRequest,
    http::{Request, StatusCode},
};
use pfa_command::{JsonBody, format::datetime};
use serde::Deserialize;

#[test]
fn browser_timestamps_normalize_to_utc() {
    let utc = datetime("2026-11-28T10:00:00Z").unwrap();
    assert_eq!(datetime("2026-11-28T15:30:00+05:30").unwrap(), utc);
    assert_eq!(datetime("2026-11-28T10:00:00").unwrap(), utc);
}

#[test]
fn invalid_dates_are_client_errors() {
    for value in ["", "2026-02-30", "yesterday"] {
        assert_eq!(datetime(value).unwrap_err().0, StatusCode::BAD_REQUEST);
    }
}

#[derive(Deserialize)]
struct AmountRequest {
    amount: i64,
}

#[tokio::test]
async fn malformed_json_keeps_the_400_contract() {
    let request = Request::builder()
        .header("content-type", "application/json")
        .body(Body::from(r#"{"amount":"invalid"}"#))
        .unwrap();
    let result = JsonBody::<AmountRequest>::from_request(request, &()).await;
    let error = match result {
        Err(error) => error,
        Ok(_) => panic!("invalid amount was accepted"),
    };
    assert_eq!(error.0, StatusCode::BAD_REQUEST);
    assert!(error.1.starts_with("Malformed request body:"));
}

#[tokio::test]
async fn valid_json_reaches_the_handler_without_changes() {
    let request = Request::builder()
        .header("content-type", "application/json")
        .body(Body::from(r#"{"amount":125}"#))
        .unwrap();
    let JsonBody(value) = JsonBody::<AmountRequest>::from_request(request, &())
        .await
        .unwrap();
    assert_eq!(value.amount, 125);
}
