//! Service errors and their HTTP response format.

use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde_json::json;

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

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.0, self.1)
    }
}
impl std::error::Error for Error {}

impl Error {
    pub(crate) fn bad(message: &str) -> Self {
        bad(message)
    }

    pub(crate) fn internal(error: impl std::fmt::Display) -> Self {
        tracing::error!(error = %error, "storage dependency failure");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
