use super::models::{Claims, User};
use crate::app::App;
use crate::config::{env, now};
use crate::error::{Error, Result};
use axum::Json;
use axum::http::{HeaderMap, HeaderValue};
use axum::response::IntoResponse;
use axum::response::Response;
use base64::Engine;
use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header, Validation};
use redis::AsyncCommands;
use serde_json::Value;
use serde_json::json;
use uuid::Uuid;

pub fn resolve_key(secret: &str) -> std::result::Result<Vec<u8>, &'static str> {
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(secret)
        .ok()
        .filter(|b| b.len() >= 32);
    let key = decoded.unwrap_or_else(|| secret.as_bytes().to_vec());
    if key.len() < 32 {
        return Err("JWT_SECRET must contain at least 32 bytes");
    }
    Ok(key)
}
pub fn algorithm(key: &[u8]) -> Algorithm {
    if key.len() >= 64 {
        Algorithm::HS512
    } else if key.len() >= 48 {
        Algorithm::HS384
    } else {
        Algorithm::HS256
    }
}
pub(crate) fn mint(app: &App, user: &User, sv: i64, stream: bool) -> Result<String> {
    let ttl = if stream {
        120
    } else {
        env("JWT_EXPIRATION", "900000")
            .parse::<u64>()
            .unwrap_or(900000)
            / 1000
    };
    let claims = Claims {
        sub: user.email.clone(),
        user_id: user.id.to_string(),
        role: user.role.clone(),
        sv,
        exp: now() + ttl,
        iat: now(),
        jti: Uuid::new_v4().to_string(),
        purpose: stream.then(|| "stream".into()),
    };
    jsonwebtoken::encode(
        &Header::new(algorithm(&app.key)),
        &claims,
        &EncodingKey::from_secret(&app.key),
    )
    .map_err(Error::internal)
}
pub fn token_validation(key: &[u8]) -> Validation {
    let mut validation = Validation::new(algorithm(key));
    validation.algorithms = vec![Algorithm::HS256, Algorithm::HS384, Algorithm::HS512];
    validation.leeway = 0;
    validation.validate_nbf = true;
    validation
}

pub(crate) async fn validate(app: &App, token: &str, stream: bool) -> Result<Claims> {
    // JJWT chooses the signing algorithm based on the HMAC key length. Accept
    // the HMAC family for existing tokens, never asymmetric or unsigned tokens.
    let validation = token_validation(&app.key);
    let claims =
        jsonwebtoken::decode::<Claims>(token, &DecodingKey::from_secret(&app.key), &validation)
            .map_err(|_| Error::unauthorized())?
            .claims;
    if claims.exp <= now()
        || Uuid::parse_str(&claims.user_id).is_err()
        || claims.role != "USER"
        || claims.sub.is_empty()
        || (claims.purpose.is_some() && !(stream && claims.purpose.as_deref() == Some("stream")))
    {
        return Err(Error::unauthorized());
    }
    let (blocked, version): (bool, Option<i64>) = redis::pipe()
        .exists(format!("token_blacklist:{token}"))
        .get(format!("session_version:{}", claims.user_id))
        .query_async(&mut app.redis.clone())
        .await
        .map_err(Error::internal)?;
    if blocked || version.unwrap_or(0) != claims.sv {
        return Err(Error::unauthorized());
    }
    Ok(claims)
}
pub(crate) fn bearer(headers: &HeaderMap) -> Result<&str> {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .filter(|v| !v.is_empty())
        .ok_or_else(Error::unauthorized)
}
pub(crate) async fn identity(app: &App, headers: &HeaderMap) -> Result<Claims> {
    validate(app, bearer(headers)?, false).await
}
pub(crate) async fn version(app: &App, id: Uuid) -> Result<i64> {
    app.redis
        .clone()
        .get::<_, Option<i64>>(format!("session_version:{id}"))
        .await
        .map(|v| v.unwrap_or(0))
        .map_err(Error::internal)
}
pub(crate) fn refresh_ttl() -> u64 {
    env("JWT_REFRESH_EXPIRATION", "604800000")
        .parse::<u64>()
        .unwrap_or(604800000)
        / 1000
}
pub(crate) fn cookie(token: &str, clear: bool) -> HeaderValue {
    HeaderValue::from_str(&format!(
        "pfa_refresh={token}; HttpOnly; SameSite=Strict; Path=/api/auth; Max-Age={}{}",
        if clear { 0 } else { refresh_ttl() },
        if env("COOKIE_SECURE", "false") == "true" {
            "; Secure"
        } else {
            ""
        }
    ))
    .unwrap()
}
pub(crate) fn session(user: &User, token: &str) -> Value {
    json!({"token": token, "refreshToken":null,"userId":user.id,"email":user.email,"username":user.username,"role":user.role,"defaultCurrency":user.default_currency})
}
pub(crate) fn session_response(value: Value, refresh: &str) -> Response {
    let mut r = Json(value).into_response();
    r.headers_mut().insert("set-cookie", cookie(refresh, false));
    r.headers_mut()
        .insert("cache-control", HeaderValue::from_static("no-store"));
    r
}
pub(crate) fn input_string<'a>(body: &'a Value, name: &str) -> Result<&'a str> {
    body.get(name)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| Error::bad(&format!("{name} is required")))
}
