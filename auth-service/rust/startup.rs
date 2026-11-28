//! Connect dependencies, construct application state and start the HTTP listeners.

use crate::{
    App, auth,
    config::{env, required},
    routes,
};
use redis::aio::ConnectionManager;
use std::{sync::Arc, time::Duration};
use tokio::sync::Semaphore;

pub async fn run() -> std::result::Result<(), Box<dyn std::error::Error>> {
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
        .add_global_label("application", "auth-service")
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
    tracing::info!(
        service = "auth-service",
        version = env!("CARGO_PKG_VERSION"),
        "starting authentication"
    );
    let redis = ConnectionManager::new(redis::Client::open(redis_url.as_str())?)
        .await
        .map_err(|e| {
            tracing::error!(error = %e, "redis connection failed");
            e
        })?;
    tracing::info!("connected to redis");
    let db_host = env("DB_HOST", "postgres-primary");
    let db_port = env("DB_PORT", "5432");
    let options = sqlx::postgres::PgConnectOptions::new()
        .host(&db_host)
        .port(db_port.parse()?)
        .database(&env("DB_NAME", "pfa"))
        .username(&required("DB_USER"))
        .password(&required("DB_PASSWORD"));
    let db = sqlx::postgres::PgPoolOptions::new()
        .max_connections(20)
        .acquire_timeout(Duration::from_secs(10))
        .connect_with(options)
        .await
        .map_err(|e| {
            tracing::error!(error = %e, host = %db_host, port = %db_port, "database connection failed");
            e
        })?;
    tracing::info!(host = %db_host, port = %db_port, "connected to database");
    let app = App {
        redis,
        db,
        key: auth::resolve_key(&required("JWT_SECRET"))?,
        hash_slots: Arc::new(Semaphore::new(4)),
        metrics,
        rate_limit_disabled: matches!(
            env("RATE_LIMIT_DISABLED", "false")
                .to_ascii_lowercase()
                .as_str(),
            "1" | "true" | "yes"
        ),
    };
    let (router, management) = routes::routers(app);
    let port = env("PORT", "8082");
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}")).await?;
    let management_listener = tokio::net::TcpListener::bind("0.0.0.0:9090").await?;
    tracing::info!(%port, management_port = "9090", "listening");
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
