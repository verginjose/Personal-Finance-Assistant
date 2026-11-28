//! Authentication routes. Handler code is grouped by accounts, sessions and user lookup.

use crate::app::App;
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde_json::json;

pub(crate) fn db(app: &App) -> &sqlx::PgPool {
    &app.db
}

mod accounts;
mod directory;
mod models;
mod password;
pub mod rate_limit;
mod sessions;
mod tokens;
mod verify;

pub(crate) fn routes() -> Router<App> {
    Router::new()
        .route(
            "/api/auth/health",
            get(|| async { Json(json!({"status":"UP","service":"auth-service"})) }),
        )
        .route("/api/auth/register", post(accounts::register))
        .route("/api/auth/login", post(sessions::login))
        .route("/api/auth/refresh", post(sessions::refresh))
        .route("/api/auth/logout", post(sessions::logout))
        .route("/api/auth/me", get(accounts::me))
        .route("/api/auth/profile", put(accounts::profile))
        .route("/api/auth/change-password", post(accounts::change_password))
        .route("/api/auth/stream-ticket", post(sessions::ticket))
        .route("/api/auth/users/search", get(directory::search))
        .route("/api/auth/users/bulk", post(directory::bulk))
        .route("/api/auth/users/{id}", get(directory::get_user))
        .route("/internal/verify", get(verify::verify))
}

pub use password::currency;
pub use sessions::refresh_input;
pub use tokens::{algorithm, resolve_key, token_validation};
