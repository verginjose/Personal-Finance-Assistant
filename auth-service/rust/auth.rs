use super::*;
use axum::{
    extract::{Path, Query},
    http::{HeaderMap, HeaderValue},
    routing::{get, post, put},
};
use base64::Engine;
use jsonwebtoken::{Algorithm, DecodingKey, EncodingKey, Header, Validation};
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    #[serde(rename = "userId")]
    pub user_id: String,
    pub role: String,
    #[serde(default)]
    pub sv: i64,
    pub exp: u64,
    pub iat: u64,
    pub jti: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub purpose: Option<String>,
}
#[derive(sqlx::FromRow)]
struct User {
    id: Uuid,
    email: String,
    username: String,
    password: String,
    role: String,
    enabled: bool,
    account_non_locked: bool,
    profile_picture: Option<String>,
    default_currency: String,
}
fn db(app: &App) -> &sqlx::PgPool {
    app.db.as_ref().expect("auth database")
}
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
fn algorithm(key: &[u8]) -> Algorithm {
    if key.len() >= 64 {
        Algorithm::HS512
    } else if key.len() >= 48 {
        Algorithm::HS384
    } else {
        Algorithm::HS256
    }
}
fn mint(app: &App, user: &User, sv: i64, stream: bool) -> Result<String> {
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
pub async fn validate(app: &App, token: &str, stream: bool) -> Result<Claims> {
    // JJWT chooses the signing algorithm based on the HMAC key length. Accept
    // the HMAC family for existing tokens, never asymmetric or unsigned tokens.
    let mut validation = Validation::new(algorithm(&app.key));
    validation.algorithms = vec![Algorithm::HS256, Algorithm::HS384, Algorithm::HS512];
    validation.leeway = 0;
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
fn bearer(headers: &HeaderMap) -> Result<&str> {
    headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .filter(|v| !v.is_empty())
        .ok_or_else(Error::unauthorized)
}
pub async fn identity(app: &App, headers: &HeaderMap) -> Result<Claims> {
    validate(app, bearer(headers)?, false).await
}
async fn version(app: &App, id: Uuid) -> Result<i64> {
    app.redis
        .clone()
        .get::<_, Option<i64>>(format!("session_version:{id}"))
        .await
        .map(|v| v.unwrap_or(0))
        .map_err(Error::internal)
}
fn refresh_ttl() -> u64 {
    env("JWT_REFRESH_EXPIRATION", "604800000")
        .parse::<u64>()
        .unwrap_or(604800000)
        / 1000
}
fn cookie(token: &str, clear: bool) -> HeaderValue {
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
fn session(user: &User, token: &str) -> Value {
    json!({"token": token, "refreshToken":null,"userId":user.id,"email":user.email,"username":user.username,"role":user.role,"defaultCurrency":user.default_currency})
}
fn session_response(value: Value, refresh: &str) -> Response {
    let mut r = Json(value).into_response();
    r.headers_mut().insert("set-cookie", cookie(refresh, false));
    r.headers_mut()
        .insert("cache-control", HeaderValue::from_static("no-store"));
    r
}
fn input_string<'a>(body: &'a Value, name: &str) -> Result<&'a str> {
    body.get(name)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| Error::bad(&format!("{name} is required")))
}
fn currency(value: &str) -> Result<String> {
    let c = value.trim().to_ascii_uppercase();
    if c.len() != 3 || !c.bytes().all(|b| b.is_ascii_uppercase()) {
        return Err(Error::bad("Default currency must be a 3-letter ISO code"));
    }
    Ok(c)
}
async fn hash(app: &App, password: String) -> Result<String> {
    let permit = app
        .hash_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(Error::internal)?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        bcrypt::hash(password, 10)
    })
    .await
    .map_err(Error::internal)?
    .map_err(Error::internal)
}
async fn matches(app: &App, password: String, encoded: String) -> Result<bool> {
    let permit = app
        .hash_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(Error::internal)?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        bcrypt::verify(password, &encoded).unwrap_or(false)
    })
    .await
    .map_err(Error::internal)
}
async fn find_email(app: &App, email: &str) -> Result<User> {
    sqlx::query_as::<_, User>("SELECT * FROM auth.users WHERE email=$1")
        .bind(email)
        .fetch_optional(db(app))
        .await
        .map_err(Error::internal)?
        .ok_or_else(Error::unauthorized)
}
async fn register(State(app): State<App>, Json(body): Json<Value>) -> Result<Response> {
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
async fn login(State(app): State<App>, Json(body): Json<Value>) -> Result<Response> {
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
fn refresh_input(headers: &HeaderMap, body: &Value) -> String {
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
async fn refresh(
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
async fn me(State(app): State<App>, headers: HeaderMap) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let u = find_email(&app, &c.sub).await?;
    Ok(Json(
        json!({"email":u.email,"role":format!("ROLE_{}",u.role),"profilePicture":u.profile_picture.unwrap_or_default(),"username":u.username,"defaultCurrency":u.default_currency}),
    ))
}
async fn profile(
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
async fn logout(
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
async fn change_password(
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
async fn ticket(State(app): State<App>, headers: HeaderMap) -> Result<Json<Value>> {
    let c = identity(&app, &headers).await?;
    let user = find_email(&app, &c.sub).await?;
    Ok(Json(json!({"ticket":mint(&app,&user,c.sv,true)?})))
}
fn user_result(user: User, email: &str) -> Value {
    json!({"userId":user.id,"username":user.username,"email":if user.email.eq_ignore_ascii_case(email) {Some(user.email)} else {None},"profilePicture":user.profile_picture})
}
async fn search(
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
async fn get_user(
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
async fn bulk(
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

async fn rate(app: &App, key: &str, replenish: f64, burst: f64) -> Result<()> {
    let allowed: i32 = redis::Script::new(r#"
        local t=redis.call('TIME'); local now=tonumber(t[1])+tonumber(t[2])/1000000
        local data=redis.call('HMGET',KEYS[1],'tokens','at'); local tokens=tonumber(data[1]) or tonumber(ARGV[2]); local at=tonumber(data[2]) or now
        tokens=math.min(tonumber(ARGV[2]),tokens+math.max(0,now-at)*tonumber(ARGV[1])); local ok=0
        if tokens>=1 then tokens=tokens-1; ok=1 end
        redis.call('HSET',KEYS[1],'tokens',tokens,'at',now); redis.call('EXPIRE',KEYS[1],math.ceil(2*tonumber(ARGV[2])/tonumber(ARGV[1]))); return ok
    "#).key(format!("native_rate:{key}")).arg(replenish).arg(burst).invoke_async(&mut app.redis.clone()).await.map_err(Error::internal)?;
    if allowed == 0 {
        return Err(Error(
            StatusCode::TOO_MANY_REQUESTS,
            "Rate limit exceeded".into(),
        ));
    }
    Ok(())
}
async fn verify(State(app): State<App>, headers: HeaderMap) -> Result<Response> {
    // This endpoint is reachable only by the edge proxy (NetworkPolicy).
    let uri = headers
        .get("x-forwarded-uri")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(Error::unauthorized)?;
    let method = headers
        .get("x-forwarded-method")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("GET");
    let url =
        url::Url::parse(&format!("http://edge{uri}")).map_err(|_| Error::bad("Invalid URI"))?;
    let path = url.path();
    for (k, v) in url.query_pairs() {
        if k == "userId" && !v.is_empty() && Uuid::parse_str(&v).is_err() {
            return Err(Error::bad("userId must be a valid UUID"));
        }
    }
    if path.starts_with("/api/auth/") {
        let ip = headers
            .get("x-real-ip")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<std::net::IpAddr>().ok())
            .map(|v| v.to_string())
            .unwrap_or_else(|| "unknown".into());
        let credentials = path == "/api/auth/login" || path == "/api/auth/register";
        rate(
            &app,
            &format!("ip:{ip}:{path}"),
            if credentials { 5.0 } else { 100.0 },
            if credentials { 20.0 } else { 200.0 },
        )
        .await?;
        return Ok(StatusCode::OK.into_response());
    }
    if path.starts_with("/api/storage/profiles/")
        || [
            "/api/upsert/health",
            "/api/analytics/health",
            "/api/bill/health",
        ]
        .contains(&path)
    {
        return Ok(StatusCode::OK.into_response());
    }
    let stream = path.starts_with("/api/upsert/notifications/stream");
    let query_ticket = url
        .query_pairs()
        .find(|(k, _)| k == "ticket")
        .map(|(_, v)| v.into_owned());
    let token = bearer(&headers)
        .ok()
        .map(str::to_owned)
        .or(if stream { query_ticket } else { None })
        .ok_or_else(Error::unauthorized)?;
    let claims = validate(&app, &token, stream).await?;
    if headers.get("authorization").is_none() && claims.purpose.as_deref() != Some("stream") {
        return Err(Error::unauthorized());
    }
    let policy = if path.starts_with("/api/analytics/") {
        Some(("analytics", 10., 20.))
    } else if path.starts_with("/api/upsert/") && !path.starts_with("/api/upsert/notifications/") {
        if method == "GET" {
            Some(("read", 50., 100.))
        } else {
            Some(("write", 20., 40.))
        }
    } else {
        None
    };
    if let Some((group, r, b)) = policy {
        rate(&app, &format!("{group}:{}", claims.user_id), r, b).await?;
    }
    let mut response = StatusCode::OK.into_response();
    response.headers_mut().insert(
        "x-user-id",
        HeaderValue::from_str(&claims.user_id).map_err(Error::internal)?,
    );
    Ok(response)
}
pub fn routes() -> Router<App> {
    Router::new()
        .route(
            "/api/auth/health",
            get(|| async { Json(json!({"status":"UP","service":"auth-service"})) }),
        )
        .route("/api/auth/register", post(register))
        .route("/api/auth/login", post(login))
        .route("/api/auth/refresh", post(refresh))
        .route("/api/auth/logout", post(logout))
        .route("/api/auth/me", get(me))
        .route("/api/auth/profile", put(profile))
        .route("/api/auth/change-password", post(change_password))
        .route("/api/auth/stream-ticket", post(ticket))
        .route("/api/auth/users/search", get(search))
        .route("/api/auth/users/bulk", post(bulk))
        .route("/api/auth/users/{id}", get(get_user))
        .route("/internal/verify", get(verify))
}

#[cfg(test)]
mod tests {
    use super::*;
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
}
