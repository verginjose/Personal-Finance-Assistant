mod auth;
mod storage;

use axum::{
    Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, Request, State},
    http::StatusCode,
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::get,
};
use redis::aio::ConnectionManager;
use serde_json::json;
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::sync::Semaphore;

#[derive(Clone)]
struct App {
    redis: ConnectionManager,
    db: Option<sqlx::PgPool>,
    key: Vec<u8>,
    hash_slots: Arc<Semaphore>,
    storage: Option<Arc<storage::Storage>>,
    metrics: metrics_exporter_prometheus::PrometheusHandle,
}

#[derive(Debug)]
struct Error(StatusCode, String);
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.0, self.1)
    }
}
impl std::error::Error for Error {}
type Result<T> = std::result::Result<T, Error>;
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(json!({"error": self.0.canonical_reason().unwrap_or("Error"), "message": self.1})),
        )
            .into_response()
    }
}
impl Error {
    fn bad(message: &str) -> Self {
        Self(StatusCode::BAD_REQUEST, message.into())
    }
    fn unauthorized() -> Self {
        Self(StatusCode::UNAUTHORIZED, "Invalid or expired token".into())
    }
    fn internal(e: impl std::fmt::Display) -> Self {
        tracing::error!(error = %e, "dependency failure");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
fn env(key: &str, fallback: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| fallback.into())
}
fn required(key: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| panic!("{key} must be configured"))
}
fn now() -> u64 {
    chrono::Utc::now().timestamp() as u64
}

async fn liveness() -> Json<serde_json::Value> {
    Json(json!({"status":"UP"}))
}
async fn readiness(State(app): State<App>) -> Result<Json<serde_json::Value>> {
    redis::cmd("PING")
        .query_async::<String>(&mut app.redis.clone())
        .await
        .map_err(Error::internal)?;
    if let Some(db) = &app.db {
        sqlx::query("SELECT 1")
            .execute(db)
            .await
            .map_err(Error::internal)?;
    }
    if let Some(storage) = &app.storage {
        storage.ready().await?;
    }
    Ok(Json(json!({"status":"UP"})))
}
async fn metrics_handler(State(app): State<App>) -> String {
    app.metrics.render()
}
async fn observe(req: Request, next: Next) -> Response {
    let start = Instant::now();
    let route = req
        .extensions()
        .get::<axum::extract::MatchedPath>()
        .map(|p| p.as_str().to_owned())
        .unwrap_or_else(|| "unmatched".into());
    let method = req.method().to_string();
    let response = next.run(req).await;
    let status = response.status().as_u16();
    let elapsed_ms = start.elapsed().as_millis() as u64;
    // Log the matched route only: paths can contain ids but never credentials,
    // and request bodies/headers (tokens, passwords) are deliberately omitted.
    if status >= 500 {
        tracing::error!(method = %method, uri = %route, status, elapsed_ms, "request failed");
    } else {
        tracing::info!(method = %method, uri = %route, status, elapsed_ms, "request");
    }
    metrics::histogram!("http_server_requests_seconds", "uri" => route, "method" => method, "status" => status.to_string()).record(start.elapsed().as_secs_f64());
    response
}
#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    let metrics = metrics_exporter_prometheus::PrometheusBuilder::new()
        .set_buckets_for_metric(
            metrics_exporter_prometheus::Matcher::Full("http_server_requests_seconds".into()),
            &[
                0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0,
            ],
        )?
        .add_global_label(
            "application",
            if env("SERVICE_MODE", "auth") == "auth" {
                "auth-service"
            } else {
                "storage-service"
            },
        )
        .install_recorder()?;
    let mut redis_url = url::Url::parse(&format!(
        "redis://{}:{}/",
        env("REDIS_HOST", "redis"),
        env("REDIS_SERVER_PORT", "6379")
    ))?;
    let password = env("REDIS_PASSWORD", "");
    if !password.is_empty() {
        redis_url.set_password(Some(&password)).unwrap();
    }
    let mode = env("SERVICE_MODE", "auth");
    tracing::info!(
        service = if mode == "auth" { "auth-service" } else { "storage-service" },
        version = env!("CARGO_PKG_VERSION"),
        %mode,
        "starting"
    );
    let redis = ConnectionManager::new(redis::Client::open(redis_url.as_str())?)
        .await
        .map_err(|e| {
            tracing::error!(error = %e, "redis connection failed");
            e
        })?;
    tracing::info!("connected to redis");
    let db = if mode == "auth" {
        let db_host = env("DB_HOST", "postgres-primary");
        let db_port = env("DB_PORT", "5432");
        let options = sqlx::postgres::PgConnectOptions::new()
            .host(&db_host)
            .port(db_port.parse()?)
            .database(&env("DB_NAME", "pfa"))
            .username(&required("DB_USER"))
            .password(&required("DB_PASSWORD"));
        let pool = sqlx::postgres::PgPoolOptions::new()
            .max_connections(20)
            .acquire_timeout(Duration::from_secs(10))
            .connect_with(options)
            .await
            .map_err(|e| {
                tracing::error!(error = %e, host = %db_host, port = %db_port, "database connection failed");
                e
            })?;
        tracing::info!(host = %db_host, port = %db_port, "connected to database");
        Some(pool)
    } else {
        None
    };
    let storage = if mode == "storage" {
        let storage = storage::Storage::new().await.map_err(|e| {
            tracing::error!(error = %e, "storage backend initialisation failed");
            e
        })?;
        tracing::info!("storage backend ready");
        Some(Arc::new(storage))
    } else {
        None
    };
    let app = App {
        redis,
        db,
        key: auth::resolve_key(&required("JWT_SECRET"))?,
        hash_slots: Arc::new(Semaphore::new(4)),
        storage,
        metrics,
    };
    let routes = if mode == "auth" {
        auth::routes()
    } else {
        storage::routes()
    };
    let management = Router::new()
        .route("/actuator/health/liveness", get(liveness))
        .route("/actuator/health/readiness", get(readiness))
        .route("/actuator/health", get(readiness))
        .route("/actuator/prometheus", get(metrics_handler))
        .with_state(app.clone());
    let router = routes
        .layer(DefaultBodyLimit::max(if mode == "auth" {
            1024 * 1024
        } else {
            12 * 1024 * 1024
        }))
        .layer(middleware::from_fn(observe))
        .layer(tower::limit::ConcurrencyLimitLayer::new(256))
        .with_state(app);
    let port = env("PORT", if mode == "auth" { "8082" } else { "8083" });
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}")).await?;
    let management_listener = tokio::net::TcpListener::bind("0.0.0.0:9090").await?;
    tracing::info!(%mode, %port, management_port = "9090", "listening");
    tokio::try_join!(
        async {
            axum::serve(listener, router)
                .with_graceful_shutdown(shutdown())
                .await
        },
        async {
            axum::serve(management_listener, management)
                .with_graceful_shutdown(shutdown())
                .await
        }
    )?;
    Ok(())
}
async fn shutdown() {
    let mut term =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
    tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = term.recv() => {} }
    tracing::info!("shutdown signal received");
}
