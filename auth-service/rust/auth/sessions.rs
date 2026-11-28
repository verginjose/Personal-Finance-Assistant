//! Login, refresh cookies, logout and short-lived SSE tickets.

use super::accounts::find_email;
use super::db;
use super::models::User;
use super::password::matches;
use super::tokens::{
    bearer, cookie, identity, input_string, mint, refresh_ttl, session, session_response, validate,
    version,
};
use crate::app::App;
use crate::config::now;
use crate::error::{Error, Result};
use axum::Json;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use redis::AsyncCommands;
use serde_json::Value;
use serde_json::json;
use std::time::Duration;
use uuid::Uuid;

pub(crate) async fn login(State(app): State<App>, Json(body): Json<Value>) -> Result<Response> {
    let email = input_string(&body, "email")?;
    let password = input_string(&body, "password")?;
    let failkey = format!("login_fail:{}", email.trim().to_lowercase());
    let failures: Option<i64> = app
        .redis
        .clone()
        .get(&failkey)
        .await
        .map_err(Error::internal)?;
    if failures.unwrap_or(0) >= 10 {
        return Err(Error(
            StatusCode::TOO_MANY_REQUESTS,
            "Too many failed attempts. Try again later.".into(),
        ));
    }
    // Hold a shared user-row lock until issuing the session. Password changes
    // take an exclusive lock, so an old-password login cannot mint a token
    // carrying the newly incremented session version.
    let mut transaction = db(&app).begin().await.map_err(Error::internal)?;
    let user = sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE email=$1 FOR SHARE")
        .bind(email)
        .fetch_optional(&mut *transaction)
        .await
        .map_err(Error::internal)?;
    // A dummy BCrypt comparison gives unknown users the same expensive path.
    let encoded = user
        .as_ref()
        .map(|u| u.password.clone())
        .unwrap_or_else(|| "$2b$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy".into());
    let valid = matches(&app, password.into(), encoded).await?;
    if !valid
        || user
            .as_ref()
            .is_none_or(|u| !u.enabled || !u.account_non_locked)
    {
        redis::pipe()
            .atomic()
            .incr(&failkey, 1)
            .ignore()
            .expire(&failkey, 900)
            .ignore()
            .query_async::<()>(&mut app.redis.clone())
            .await
            .map_err(Error::internal)?;
        return Err(Error(
            StatusCode::UNAUTHORIZED,
            "Invalid email or password".into(),
        ));
    }
    let user = user.unwrap();
    let sv = version(&app, user.id).await?;
    let token = mint(&app, &user, sv, false)?;
    let refresh = Uuid::new_v4().to_string();
    redis::pipe()
        .atomic()
        .del(&failkey)
        .ignore()
        .set_ex(
            format!("refresh_token:{refresh}"),
            format!("{}|{}|{}", user.id, user.email, user.role),
            refresh_ttl(),
        )
        .ignore()
        .sadd(format!("user_tokens:{}", user.email), &refresh)
        .ignore()
        .expire(format!("user_tokens:{}", user.email), refresh_ttl() as i64)
        .ignore()
        .query_async::<()>(&mut app.redis.clone())
        .await
        .map_err(Error::internal)?;
    transaction.commit().await.map_err(Error::internal)?;
    Ok(session_response(session(&user, &token), &refresh))
}
pub fn refresh_input(headers: &HeaderMap, body: &Value) -> String {
    headers
        .get("cookie")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| {
            s.split(';')
                .map(str::trim)
                .find_map(|s| s.strip_prefix("pfa_refresh=").filter(|v| !v.is_empty()))
        })
        .or_else(|| body.get("refreshToken").and_then(Value::as_str))
        .unwrap_or("")
        .to_string()
}
pub(crate) async fn refresh(
    State(app): State<App>,
    headers: HeaderMap,
    body: Option<Json<Value>>,
) -> Result<Response> {
    let old = refresh_input(&headers, &body.map(|j| j.0).unwrap_or(Value::Null));
    if Uuid::parse_str(&old).is_err() {
        return Err(Error::unauthorized());
    }
    for _ in 0..12 {
        let raw: Option<String> = app
            .redis
            .clone()
            .get(format!("refresh_token:{old}"))
            .await
            .map_err(Error::internal)?;
        if let Some(raw) = raw {
            let parts: Vec<_> = raw.split('|').collect();
            if parts.len() != 3 {
                return Err(Error::unauthorized());
            }
            let user = find_email(&app, parts[1]).await?;
            if user.id.to_string() != parts[0] || !user.enabled || !user.account_non_locked {
                return Err(Error::unauthorized());
            }
            let sv = version(&app, user.id).await?;
            let token = mint(&app, &user, sv, false)?;
            let next = Uuid::new_v4().to_string();
            let grace = format!("{token}|{next}|{}|{}|{}", user.id, user.email, user.role);
            // Consume, issue and save the grace pair in one Redis transaction.
            // A concurrent password change bumps sv and makes this script fail.
            let won: i32 = redis::Script::new(r#"
                if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
                if tonumber(redis.call('GET',KEYS[5]) or '0') ~= tonumber(ARGV[6]) then return -1 end
                redis.call('DEL',KEYS[1]); redis.call('SREM',KEYS[3],ARGV[2])
                redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[4]); redis.call('SADD',KEYS[3],ARGV[3]); redis.call('EXPIRE',KEYS[3],ARGV[4])
                redis.call('SET',KEYS[4],ARGV[5],'EX',60); return 1
            "#).key(format!("refresh_token:{old}")).key(format!("refresh_token:{next}")).key(format!("user_tokens:{}",user.email)).key(format!("refresh_grace:{old}")).key(format!("session_version:{}",user.id)).arg(raw).arg(&old).arg(&next).arg(refresh_ttl()).arg(grace).arg(sv).invoke_async(&mut app.redis.clone()).await.map_err(Error::internal)?;
            if won == 1 {
                return Ok(session_response(session(&user, &token), &next));
            }
            if won < 0 {
                return Err(Error::unauthorized());
            }
        }
        let grace: Option<String> = app
            .redis
            .clone()
            .get(format!("refresh_grace:{old}"))
            .await
            .map_err(Error::internal)?;
        if let Some(grace) = grace {
            let parts: Vec<_> = grace.split('|').collect();
            if parts.len() != 5 {
                return Err(Error::unauthorized());
            }
            validate(&app, parts[0], false).await?;
            let live: bool = app
                .redis
                .clone()
                .exists(format!("refresh_token:{}", parts[1]))
                .await
                .map_err(Error::internal)?;
            if !live {
                return Err(Error::unauthorized());
            }
            let user = find_email(&app, parts[3]).await?;
            return Ok(session_response(session(&user, parts[0]), parts[1]));
        }
        tokio::time::sleep(Duration::from_millis(40)).await;
    }
    Err(Error::unauthorized())
}
pub(crate) async fn logout(
    State(app): State<App>,
    headers: HeaderMap,
    body: Option<Json<Value>>,
) -> Result<Response> {
    let c = identity(&app, &headers).await?;
    let refresh = refresh_input(&headers, &body.map(|j| j.0).unwrap_or(Value::Null));
    let raw: Option<String> = app
        .redis
        .clone()
        .get(format!("refresh_token:{refresh}"))
        .await
        .map_err(Error::internal)?;
    if let Some(raw) = raw
        && raw.split('|').next() != Some(c.user_id.as_str())
    {
        return Err(Error(StatusCode::FORBIDDEN, "Access denied".into()));
    }
    redis::pipe()
        .atomic()
        .del(format!("refresh_token:{refresh}"))
        .ignore()
        .srem(format!("user_tokens:{}", c.sub), &refresh)
        .ignore()
        .set_ex(
            format!("token_blacklist:{}", bearer(&headers)?),
            "revoked",
            c.exp.saturating_sub(now()).max(1),
        )
        .ignore()
        .query_async::<()>(&mut app.redis.clone())
        .await
        .map_err(Error::internal)?;
    let mut response = Json(json!({"message":"Logged out successfully"})).into_response();
    response
        .headers_mut()
        .insert("set-cookie", cookie("", true));
    Ok(response)
}
pub(crate) async fn ticket(State(app): State<App>, headers: HeaderMap) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let user = find_email(&app, &c.sub).await?;
    Ok(Json(json!({"ticket":mint(&app,&user,c.sv,true)?})))
}
