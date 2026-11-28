use axum::http::{HeaderMap, HeaderValue};
use pfa_native::auth::rate_limit::is_local_test;

#[test]
fn only_the_exact_trusted_marker_enables_local_testing() {
    let mut headers = HeaderMap::new();
    assert!(!is_local_test(&headers));
    for value in ["true", "0", "1, 1", "127.0.0.1"] {
        headers.insert("x-pfa-local-test", HeaderValue::from_str(value).unwrap());
        assert!(!is_local_test(&headers));
    }
    headers.insert("x-pfa-local-test", HeaderValue::from_static("1"));
    assert!(is_local_test(&headers));
}

#[test]
fn claiming_a_loopback_ip_does_not_enable_the_exemption() {
    let mut headers = HeaderMap::new();
    headers.insert("x-real-ip", HeaderValue::from_static("127.0.0.1"));
    headers.insert("x-forwarded-for", HeaderValue::from_static("127.0.0.1"));
    assert!(!is_local_test(&headers));
}
