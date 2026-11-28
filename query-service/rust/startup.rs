//! Connect dependencies, construct application state and start the HTTP listeners.

use crate::app::App;
use crate::config::env;
use sqlx::postgres::PgPoolOptions;
use std::{collections::HashMap, sync::Arc, time::Duration};

pub async fn run() -> std::result::Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();
    tracing::info!(
        service = "query-service",
        version = env!("CARGO_PKG_VERSION"),
        "starting"
    );
    let metrics = metrics_exporter_prometheus::PrometheusBuilder::new()
        .set_buckets(&[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1., 2.5, 5., 10.])?
        .add_global_label("application", "query-service")
        .install_recorder()?;
    let db_host = env("DB_HOST", "postgres-replica");
    let db_port = env("DB_PORT", "5432");
    let options = sqlx::postgres::PgConnectOptions::new()
        .host(&db_host)
        .port(db_port.parse()?)
        .database(&env("DB_NAME", "pfa"))
        .username(&std::env::var("DB_USER")?)
        .password(&std::env::var("DB_PASSWORD")?)
        .options([("default_transaction_read_only", "on")]);
    // Pool size is tunable: 20 starved under 400 users (requests queued on
    // acquire_timeout). DB replica has max_connections=200.
    let max_conns: u32 = env("DB_MAX_CONNECTIONS", "50").parse().unwrap_or(50);
    let db = PgPoolOptions::new()
        .max_connections(max_conns)
        .acquire_timeout(Duration::from_secs(10))
        .connect_with(options)
        .await
        .map_err(|e| {
            tracing::error!(error = %e, host = %db_host, port = %db_port, "database connection failed");
            e
        })?;
    tracing::info!(host = %db_host, port = %db_port, "connected to read replica");
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
    let app = App {
        db,
        redis,
        advice: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        http: reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(30))
            .build()?,
        fx: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        fx_locks: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        metrics,
    };
    let (router, management) = crate::routes::routers(app);
    let port = env("PORT", "8084");
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
