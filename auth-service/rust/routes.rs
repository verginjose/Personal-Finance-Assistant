//! Assemble public API routes and private management routes.

use crate::{
    App, auth,
    health::{liveness, metrics_handler, readiness},
    observability::observe,
};
use axum::{Router, extract::DefaultBodyLimit, middleware, routing::get};

pub(crate) fn routers(app: App) -> (Router, Router) {
    let api = auth::routes()
        .layer(DefaultBodyLimit::max(1024 * 1024))
        .layer(middleware::from_fn(observe))
        .layer(tower::limit::ConcurrencyLimitLayer::new(256))
        .with_state(app.clone());
    let management = Router::new()
        .route("/actuator/health/liveness", get(liveness))
        .route("/actuator/health/readiness", get(readiness))
        .route("/actuator/health", get(readiness))
        .route("/actuator/prometheus", get(metrics_handler))
        .with_state(app);
    (api, management)
}
