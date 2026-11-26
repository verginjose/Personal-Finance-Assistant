use super::*;
use axum::{
    extract::{Path, Query},
    http::HeaderMap,
};
use redis::AsyncCommands;
use serde_json::Value;
use uuid::Uuid;


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
pub(crate) fn refresh_input(headers: &HeaderMap, body: &Value) -> String {
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
pub(crate) async fn ticket(State(app): State<App>, headers: HeaderMap) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let user = find_email(&app, &c.sub).await?;
    Ok(Json(json!({"ticket":mint(&app,&user,c.sv,true)?})))
}
pub(crate) fn user_result(user: User, email: &str) -> Value {
    json!({"userId":user.id,"username":user.username,"email":if user.email.eq_ignore_ascii_case(email) {Some(user.email)} else {None},"profilePicture":user.profile_picture})
}
pub(crate) async fn search(
    State(app): State<App>,
    headers: HeaderMap,
    Query(q): Query<std::collections::HashMap<String, String>>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let query = q.get("q").map(|v| v.trim()).unwrap_or("");
    if query.is_empty() {
        return Ok(Json(json!([])));
    }
    let limit = q
        .get("limit")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(10)
        .clamp(1, 20);
    let page = q
        .get("page")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(0)
        .max(0);
    let users = sqlx::query_as::<_,User>("SELECT * FROM auth.users WHERE username ILIKE $1 OR email ILIKE $1 ORDER BY id LIMIT $2 OFFSET $3").bind(format!("%{query}%")).bind(limit).bind(page.saturating_mul(limit)).fetch_all(db(&app)).await.map_err(Error::internal)?;
    Ok(Json(Value::Array(
        users.into_iter().map(|u| user_result(u, &c.sub)).collect(),
    )))
}
pub(crate) async fn get_user(
    State(app): State<App>,
    headers: HeaderMap,
    Path(id): Path<Uuid>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let u = sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE id=$1")
        .bind(id)
        .fetch_optional(db(&app))
        .await
        .map_err(Error::internal)?
        .ok_or_else(|| Error(StatusCode::NOT_FOUND, "User not found".into()))?;
    Ok(Json(user_result(u, &c.sub)))
}
pub(crate) async fn bulk(
    State(app): State<App>,
    headers: HeaderMap,
    Json(ids): Json<Vec<Uuid>>,
) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    if ids.len() > 1000 {
        return Err(Error::bad("Too many users"));
    }
    let users = sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE id=ANY($1)")
        .bind(ids)
        .fetch_all(db(&app))
        .await
        .map_err(Error::internal)?;
    Ok(Json(Value::Array(
        users.into_iter().map(|u| user_result(u, &c.sub)).collect(),
    )))
}

