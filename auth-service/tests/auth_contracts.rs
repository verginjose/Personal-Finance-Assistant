use pfa_native::auth::{algorithm, currency, refresh_input, resolve_key};
use serde_json::json;

use axum::http::{HeaderMap, HeaderValue};
use base64::Engine;
use jsonwebtoken::Algorithm;
#[test]
fn spring_key_selection() {
    assert_eq!(algorithm(&[0; 32]), Algorithm::HS256);
    assert_eq!(algorithm(&[0; 48]), Algorithm::HS384);
    assert_eq!(algorithm(&[0; 64]), Algorithm::HS512);
    assert!(resolve_key("weak").is_err());
    assert_eq!(
        resolve_key(&base64::engine::general_purpose::STANDARD.encode([7; 64])).unwrap(),
        vec![7; 64]
    );
}
#[test]
fn cookie_overrides_body() {
    let mut h = HeaderMap::new();
    h.insert(
        "cookie",
        HeaderValue::from_static("a=b; pfa_refresh=cookie"),
    );
    assert_eq!(refresh_input(&h, &json!({"refreshToken":"body"})), "cookie");
}
#[test]
fn currency_validation() {
    assert_eq!(currency(" inr ").unwrap(), "INR");
    assert!(currency("<x>").is_err());
}
#[test]
fn bcrypt_spring_compatibility() {
    let encoded = bcrypt::hash("Password123!", 4)
        .unwrap()
        .replacen("$2b$", "$2a$", 1);
    assert!(bcrypt::verify("Password123!", &encoded).unwrap());
    assert!(!bcrypt::verify("wrong", &encoded).unwrap());
}
