mod cache;
mod entries;
mod groups;
mod jobs;
mod notifications;
mod planning;
mod subscriptions;

use axum::{
    Json, Router,
    extract::{FromRequest, Path, Query, Request, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing as rt,
};
use chrono::{NaiveDate, NaiveDateTime};
use serde_json::{Value, json};
use sqlx::{PgPool, postgres::PgPoolOptions};
use std::{collections::HashMap, time::Duration};
use uuid::Uuid;

pub type Params = HashMap<String, String>;

#[derive(Clone)]
pub struct App {
    pub db: PgPool,
    pub redis: redis::aio::ConnectionManager,
    pub metrics: metrics_exporter_prometheus::PrometheusHandle,
    pub events: notifications::Events,
}

#[derive(Debug)]
pub struct Error(pub StatusCode, pub String);
pub type Result<T> = std::result::Result<T, Error>;

impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(json!({
                "error": self.0.canonical_reason().unwrap_or("Error"),
                "message": self.1
            })),
        )
            .into_response()
    }
}
impl From<sqlx::Error> for Error {
    fn from(e: sqlx::Error) -> Self {
        tracing::error!(error = %e, "database operation failed");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
impl From<redis::RedisError> for Error {
    fn from(e: redis::RedisError) -> Self {
        tracing::error!(error = %e, "redis operation failed");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}

pub fn bad(m: &str) -> Error {
    Error(StatusCode::BAD_REQUEST, m.into())
}
pub fn not_found(m: &str) -> Error {
    Error(StatusCode::NOT_FOUND, m.into())
}
pub fn forbidden(m: &str) -> Error {
    Error(StatusCode::FORBIDDEN, m.into())
}
pub fn illegal_state(m: &str) -> Error {
    Error(StatusCode::BAD_REQUEST, m.into())
}
pub fn server_error(m: &str) -> Error {
    Error(StatusCode::INTERNAL_SERVER_ERROR, m.into())
}
pub fn env(k: &str, d: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| d.into())
}
pub fn money(d: rust_decimal::Decimal) -> Value {
    serde_json::from_str(&d.to_string()).unwrap_or(Value::Null)
}
pub fn ts(v: &chrono::NaiveDateTime) -> String {
    v.to_string().replace(' ', "T")
}

/// Accept both naive timestamps and the browser's `toISOString()` form
/// (RFC 3339 with `Z`/offset), normalising offsets to UTC.
pub fn datetime(value: &str) -> Result<NaiveDateTime> {
    let value = value.trim();
    if value.is_empty() {
        return Err(bad("Invalid date"));
    }
    if let Ok(parsed) = chrono::DateTime::parse_from_rfc3339(value) {
        return Ok(parsed.naive_utc());
    }
    for format in [
        "%Y-%m-%dT%H:%M:%S%.f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M:%S%.f",
        "%Y-%m-%dT%H:%M",
    ] {
        if let Ok(parsed) = NaiveDateTime::parse_from_str(value, format) {
            return Ok(parsed);
        }
    }
    if let Ok(parsed) = NaiveDate::parse_from_str(value, "%Y-%m-%d")
        && let Some(datetime) = parsed.and_hms_opt(0, 0, 0)
    {
        return Ok(datetime);
    }
    Err(bad("Invalid date"))
}

pub fn date(value: &str) -> Result<NaiveDate> {
    Ok(datetime(value)?.date())
}

fn header_user(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| Error(StatusCode::UNAUTHORIZED, "Invalid user context".into()))?;
    Uuid::parse_str(raw).map_err(|_| Error(StatusCode::UNAUTHORIZED, "Invalid user context".into()))
}

fn same_user(h: &HeaderMap, body_user: Option<Uuid>) -> Result<Uuid> {
    let uid = body_user.ok_or_else(|| bad("User ID is required"))?;
    if header_user(h)? != uid {
        return Err(forbidden("Access denied: User ID mismatch"));
    }
    Ok(uid)
}

/// Spring's SecurityInterceptor compares the raw query string with the header
/// before any UUID parsing, so a malformed `userId` is a 403, not a 400.
fn query_user(h: &HeaderMap, q: &Params) -> Result<Uuid> {
    let raw_header = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| Error(StatusCode::UNAUTHORIZED, "Invalid user context".into()))?;
    let raw = q
        .get("userId")
        .ok_or_else(|| bad("Required request parameter 'userId' is missing"))?;
    if !raw.eq_ignore_ascii_case(raw_header) {
        return Err(forbidden("Forbidden: Access denied for requested user ID"));
    }
    Uuid::parse_str(raw).map_err(|_| Error(StatusCode::UNAUTHORIZED, "Invalid user context".into()))
}

/// `axum::Json` rejects a body that fails deserialization with 422. Spring maps
/// the same client error to 400 ("Malformed request body"), so wrap it.
pub struct JsonBody<T>(pub T);

impl<T, S> FromRequest<S> for JsonBody<T>
where
    T: serde::de::DeserializeOwned,
    S: Send + Sync,
{
    type Rejection = Error;

    async fn from_request(
        req: Request,
        state: &S,
    ) -> std::result::Result<Self, Self::Rejection> {
        let Json(value) = Json::<T>::from_request(req, state)
            .await
            .map_err(|rejection: axum::extract::rejection::JsonRejection| {
                bad(&format!("Malformed request body: {}", rejection.body_text()))
            })?;
        Ok(JsonBody(value))
    }
}

async fn create(    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<entries::EntryRequest>,
) -> Result<Response> {
    let uid = same_user(&h, body.user_id)?;
    let key = h.get("x-idempotency-key").and_then(|v| v.to_str().ok());
    let value = entries::create(&a, uid, body, key).await?;
    Ok((StatusCode::CREATED, Json(value)).into_response())
}

async fn create_bulk(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<Vec<entries::EntryRequest>>,
) -> Result<Response> {
    let uid = header_user(&h)?;
    if body.is_empty() {
        return Ok(Json(Vec::<Value>::new()).into_response());
    }
    for item in &body {
        if item.user_id != Some(uid) {
            return Err(forbidden("Access denied: User ID mismatch"));
        }
    }
    let value = entries::create_bulk(&a, uid, body).await?;
    Ok((StatusCode::CREATED, Json(value)).into_response())
}

async fn update(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<entries::EntryRequest>,
) -> Result<Response> {
    let uid = same_user(&h, body.user_id)?;
    Ok(Json(entries::update(&a, uid, body).await?).into_response())
}

#[derive(serde::Deserialize)]
struct PatchAmount {
    amount: Value,
}

async fn patch_amount(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
    JsonBody(body): JsonBody<PatchAmount>,
) -> Result<Response> {
    let uid = query_user(&h, &q)?;
    Ok(Json(entries::patch_amount(&a, id, uid, body.amount).await?).into_response())
}

async fn delete_entry(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let uid = query_user(&h, &q)?;
    entries::delete(&a, id, uid).await?;
    Ok(Json(json!({"message":"Entry deleted successfully"})).into_response())
}

async fn bulk_delete(
    State(a): State<App>,
    h: HeaderMap,
    Query(q): Query<Params>,
    JsonBody(ids): JsonBody<Vec<i64>>,
) -> Result<Response> {
    let uid = query_user(&h, &q)?;
    for id in &ids {
        entries::delete(&a, *id, uid).await?;
    }
    Ok(Json(json!({"message":"Entries deleted successfully","count":ids.len()})).into_response())
}

async fn health_service() -> Json<Value> {
    Json(json!({"status":"UP","service":"upsert-service"}))
}
async fn health(State(a): State<App>) -> Result<Json<Value>> {
    sqlx::query("SELECT 1").execute(&a.db).await?;
    Ok(Json(json!({"status":"UP"})))
}
async fn live() -> Json<Value> {
    Json(json!({"status":"UP"}))
}
async fn metrics_handler(State(a): State<App>) -> String {
    a.metrics.render()
}

async fn observe(req: Request, next: Next) -> Response {
    let started = std::time::Instant::now();
    let method = req.method().to_string();
    let path = req.uri().path().to_string();
    let response = next.run(req).await;
    let status = response.status().as_u16();
    let elapsed_ms = started.elapsed().as_millis() as u64;
    if path.starts_with("/actuator/") {
        tracing::debug!(method = %method, uri = %path, status, elapsed_ms, "request");
    } else if status >= 500 {
        tracing::error!(method = %method, uri = %path, status, elapsed_ms, "request failed");
    } else {
        tracing::info!(method = %method, uri = %path, status, elapsed_ms, "request");
    }
    metrics::histogram!(
        "http_server_requests_seconds",
        "uri" => path,
        "method" => method,
        "status" => status.to_string()
    )
    .record(started.elapsed().as_secs_f64());
    response
}

#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();
    tracing::info!(
        service = "command-service",
        version = env!("CARGO_PKG_VERSION"),
        "starting"
    );

    let metrics = metrics_exporter_prometheus::PrometheusBuilder::new()
        .set_buckets(&[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1., 2.5, 5., 10.])?
        .add_global_label("application", "command-service")
        .install_recorder()?;

    let db_host = env("DB_HOST", "postgres-primary");
    let db_port = env("DB_PORT", "5432");
    let options = sqlx::postgres::PgConnectOptions::new()
        .host(&db_host)
        .port(db_port.parse()?)
        .database(&env("DB_NAME", "pfa"))
        .username(&std::env::var("DB_USER")?)
        .password(&std::env::var("DB_PASSWORD")?);
    let db = PgPoolOptions::new()
        .max_connections(20)
        .acquire_timeout(Duration::from_secs(10))
        .connect_with(options)
        .await
        .map_err(|e| {
            tracing::error!(error = %e, host = %db_host, port = %db_port, "database connection failed");
            e
        })?;
    tracing::info!(host = %db_host, port = %db_port, "connected to database");

    let redis_host = env("REDIS_HOST", "redis");
    let redis_port = env("REDIS_SERVER_PORT", "6379");
    let mut redis_url = url::Url::parse(&format!("redis://{redis_host}:{redis_port}/"))?;
    let password = env("REDIS_PASSWORD", "");
    if !password.is_empty() {
        redis_url.set_password(Some(&password)).unwrap();
    }
    let redis = redis::aio::ConnectionManager::new_with_config(
        redis::Client::open(redis_url.as_str())?,
        redis::aio::ConnectionManagerConfig::new()
            .set_connection_timeout(Duration::from_secs(3))
            .set_response_timeout(Duration::from_secs(2)),
    )
    .await
    .map_err(|e| {
        tracing::error!(error = %e, host = %redis_host, port = %redis_port, "redis connection failed");
        e
    })?;
    tracing::info!(host = %redis_host, port = %redis_port, "connected to redis");

    let app = App { db, redis, metrics, events: notifications::Events::new() };
    jobs::spawn_all(app.clone());

    let router = Router::new()
        .merge(groups::routes())
        .merge(planning::routes())
        .merge(notifications::routes())
        .route("/upsert/create", rt::post(create))
        .route("/upsert/create/bulk", rt::post(create_bulk))
        .route("/upsert/update", rt::put(update))
        .route("/upsert/entries/{id}/amount", rt::patch(patch_amount))
        .route("/upsert/delete/{id}", rt::delete(delete_entry))
        .route("/upsert/delete/bulk", rt::post(bulk_delete))
        .route("/upsert/health", rt::get(health_service))
        .route("/actuator/health", rt::get(health))
        .route("/actuator/health/readiness", rt::get(health))
        .route("/actuator/health/liveness", rt::get(live))
        .route("/actuator/prometheus", rt::get(metrics_handler))
        .layer(middleware::from_fn(observe))
        .layer(tower::limit::ConcurrencyLimitLayer::new(200))
        .with_state(app.clone());

    let management = Router::new()
        .route("/actuator/health", rt::get(health))
        .route("/actuator/health/readiness", rt::get(health))
        .route("/actuator/health/liveness", rt::get(live))
        .route("/actuator/prometheus", rt::get(metrics_handler))
        .with_state(app);

    let port = env("PORT", "8081");
    let management_port = env("MANAGEMENT_PORT", "9090");
    let api = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}")).await?;
    let mgmt = tokio::net::TcpListener::bind(format!("0.0.0.0:{management_port}")).await?;
    tracing::info!(port = %port, management_port = %management_port, "listening");
    tokio::try_join!(
        async {
            axum::serve(api, router)
                .with_graceful_shutdown(shutdown())
                .await
        },
        async {
            axum::serve(mgmt, management)
                .with_graceful_shutdown(shutdown())
                .await
        }
    )?;
    Ok(())
}

async fn shutdown() {
    let mut signal =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
    tokio::select! {_=signal.recv()=>{},_=tokio::signal::ctrl_c()=>{}}
    tracing::info!("shutdown signal received");
}
