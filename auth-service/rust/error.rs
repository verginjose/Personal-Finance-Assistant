//! Service errors and their HTTP response format.

use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde_json::json;

#[derive(Debug)]
pub struct Error(pub StatusCode, pub String);
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.0, self.1)
    }
}
impl std::error::Error for Error {}
pub type Result<T> = std::result::Result<T, Error>;
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
    pub(crate) fn bad(message: &str) -> Self {
        Self(StatusCode::BAD_REQUEST, message.into())
    }
    pub(crate) fn unauthorized() -> Self {
        Self(StatusCode::UNAUTHORIZED, "Invalid or expired token".into())
    }
    pub(crate) fn internal(e: impl std::fmt::Display) -> Self {
        tracing::error!(error = %e, "dependency failure");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
