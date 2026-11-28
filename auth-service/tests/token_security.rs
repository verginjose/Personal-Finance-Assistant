use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header};
use pfa_native::auth::token_validation;
use serde_json::{Value, json};

fn now() -> u64 {
    chrono::Utc::now().timestamp() as u64
}

#[test]
fn accepts_existing_hmac_algorithms() {
    let key = [7u8; 64];
    for algorithm in [Algorithm::HS256, Algorithm::HS384, Algorithm::HS512] {
        let claims = json!({"sub": "test", "exp": now() + 300});
        let token = jsonwebtoken::encode(
            &Header::new(algorithm),
            &claims,
            &EncodingKey::from_secret(&key),
        )
        .unwrap();
        assert!(
            jsonwebtoken::decode::<Value>(
                &token,
                &DecodingKey::from_secret(&key),
                &token_validation(&key)
            )
            .is_ok()
        );
    }
}

#[test]
fn rejects_malformed_or_future_time_claims() {
    let key = [7u8; 64];
    for claims in [
        json!({"exp": (now() + 300).to_string()}),
        json!({"exp": now() + 300, "nbf": "99999999999"}),
        json!({"exp": now() + 300, "nbf": now() + 300}),
        json!({"exp": now() - 300}),
    ] {
        let token = jsonwebtoken::encode(
            &Header::new(Algorithm::HS512),
            &claims,
            &EncodingKey::from_secret(&key),
        )
        .unwrap();
        assert!(
            jsonwebtoken::decode::<Value>(
                &token,
                &DecodingKey::from_secret(&key),
                &token_validation(&key)
            )
            .is_err()
        );
    }
}
