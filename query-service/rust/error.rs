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
            Json(json!({"error":self.0.canonical_reason(),"message":self.1})),
        )
            .into_response()
    }
}
impl From<sqlx::Error> for Error {
    fn from(e: sqlx::Error) -> Self {
        tracing::error!(error=%e,"query failed");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
pub fn bad(m: &str) -> Error {
    Error(StatusCode::BAD_REQUEST, m.into())
}
