//! Registration, the current account profile and password changes.

use super::db;
use super::models::User;
use super::password::{currency, hash, matches};
use super::tokens::{cookie, identity, input_string};
use crate::app::App;
use crate::error::{Error, Result};
use axum::Json;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde_json::Value;
use serde_json::json;

pub(crate) async fn find_email(app: &App, email: &str) -> Result<User> {
    sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE email=$1")
        .bind(email)
        .fetch_optional(db(app))
        .await
        .map_err(Error::internal)?
        .ok_or_else(Error::unauthorized)
}
pub(crate) async fn register(State(app): State<App>, Json(body): Json<Value>) -> Result<Response> {
    let email = input_string(&body, "email")?;
    let name = input_string(&body, "username")?;
    let password = input_string(&body, "password")?;
    if !email.contains('@')
        || email.len() > 255
        || email.contains(char::is_whitespace)
        || !(3..=30).contains(&name.len())
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_@.-".contains(&b))
        || password.chars().count() < 8
        || password.len() > 72
    {
        return Err(Error::bad(
            "Invalid email, username or password (8–72 bytes)",
        ));
    }
    let currency = currency(
        body.get("defaultCurrency")
            .and_then(Value::as_str)
            .unwrap_or("INR"),
    )?;
    let password = hash(&app, password.into()).await?;
    let result = sqlx::query_as::<_,User>("INSERT INTO auth.users (email,username,password,role,default_currency) VALUES ($1,$2,$3,'USER',$4) RETURNING *").bind(email).bind(name).bind(password).bind(currency).fetch_one(db(&app)).await;
    let user = result.map_err(|e| {
        if e.as_database_error()
            .is_some_and(|e| e.is_unique_violation())
        {
            Error::bad("Email or username already registered")
        } else {
            Error::internal(e)
        }
    })?;
    Ok((StatusCode::CREATED, Json(json!({"userId":user.id,"email":user.email,"username":user.username,"role":user.role,"defaultCurrency":user.default_currency}))).into_response())
}
pub(crate) async fn me(State(app): State<App>, headers: HeaderMap) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let u = find_email(&app, &c.sub).await?;
    Ok(Json(
        json!({"email":u.email,"role":format!("ROLE_{}",u.role),"profilePicture":u.profile_picture.unwrap_or_default(),"username":u.username,"defaultCurrency":u.default_currency}),
    ))
}
pub(crate) async fn profile(
    State(app): State<App>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let u = find_email(&app, &c.sub).await?;
    let name = body
        .get("username")
        .map(|v| v.as_str().unwrap_or("").trim())
        .unwrap_or(&u.username);
    if body.get("username").is_some()
        && (!(3..=30).contains(&name.len())
            || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_'))
    {
        return Err(Error::bad(
            "Username must be 3–30 letters, numbers or underscores",
        ));
    }
    let picture = body
        .get("profilePicture")
        .map(|v| v.as_str().unwrap_or("").trim())
        .unwrap_or(u.profile_picture.as_deref().unwrap_or(""));
    if body.get("profilePicture").is_some()
        && !picture.is_empty()
        && (picture.len() > 512
            || !picture.starts_with("/api/storage/profiles/")
            || !picture
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"/._-".contains(&b)))
    {
        return Err(Error::bad("Invalid profile picture URL"));
    }
    let currency = currency(
        body.get("defaultCurrency")
            .and_then(Value::as_str)
            .unwrap_or(&u.default_currency),
    )?;
    sqlx::query("UPDATE auth.users SET username=$1,profile_picture=NULLIF($2,''),default_currency=$3 WHERE id=$4").bind(name).bind(picture).bind(currency).bind(u.id).execute(db(&app)).await.map_err(|e| if e.as_database_error().is_some_and(|e|e.is_unique_violation()) {Error::bad("Username is already taken")} else {Error::internal(e)})?;
    Ok(Json(json!({"message":"Profile updated successfully"})))
}
pub(crate) async fn change_password(
    State(app): State<App>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> Result<Response> {
    let c = identity(&app, &headers).await?;
    let mut transaction = db(&app).begin().await.map_err(Error::internal)?;
    let user = sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE email=$1 FOR UPDATE")
        .bind(&c.sub)
        .fetch_one(&mut *transaction)
        .await
        .map_err(Error::internal)?;
    let new = input_string(&body, "newPassword")?;
    if new.chars().count() < 8 || new.len() > 72 {
        return Err(Error::bad("New password must be 8–72 bytes"));
    }
    if !matches(
        &app,
        input_string(&body, "currentPassword")?.into(),
        user.password,
    )
    .await?
    {
        return Err(Error::bad("Current password does not match"));
    }
    let encoded = hash(&app, new.into()).await?;
    sqlx::query("UPDATE auth.users SET password=$1 WHERE id=$2")
        .bind(encoded)
        .bind(user.id)
        .execute(&mut *transaction)
        .await
        .map_err(Error::internal)?;
    // Revocation must succeed before committing the password. On a Redis
    // failure the database transaction rolls back and the request fails closed.
    redis::Script::new(r#"local tokens=redis.call('SMEMBERS',KEYS[1]); for _,t in ipairs(tokens) do redis.call('DEL','refresh_token:'..t) end; redis.call('DEL',KEYS[1]); return redis.call('INCR',KEYS[2])"#).key(format!("user_tokens:{}",c.sub)).key(format!("session_version:{}",c.user_id)).invoke_async::<i64>(&mut app.redis.clone()).await.map_err(Error::internal)?;
    transaction.commit().await.map_err(Error::internal)?;
    let mut response = Json(json!({"message":"Password changed successfully"})).into_response();
    response
        .headers_mut()
        .insert("set-cookie", cookie("", true));
    Ok(response)
}
