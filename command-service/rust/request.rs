use crate::{Error, Result, bad, forbidden};
use axum::{
    Json,
    extract::{FromRequest, Request},
    http::{HeaderMap, StatusCode},
};
use std::collections::HashMap;
use uuid::Uuid;

pub type Params = HashMap<String, String>;

pub(crate) fn header_user(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| Error(StatusCode::UNAUTHORIZED, "Invalid user context".into()))?;
    Uuid::parse_str(raw).map_err(|_| Error(StatusCode::UNAUTHORIZED, "Invalid user context".into()))
}

pub(crate) fn same_user(h: &HeaderMap, body_user: Option<Uuid>) -> Result<Uuid> {
    let uid = body_user.ok_or_else(|| bad("User ID is required"))?;
    if header_user(h)? != uid {
        return Err(forbidden("Access denied: User ID mismatch"));
    }
    Ok(uid)
}

/// Spring's SecurityInterceptor compares the raw query string with the header
/// before any UUID parsing, so a malformed `userId` is a 403, not a 400.
pub(crate) fn query_user(h: &HeaderMap, q: &Params) -> Result<Uuid> {
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

    async fn from_request(req: Request, state: &S) -> std::result::Result<Self, Self::Rejection> {
        let Json(value) = Json::<T>::from_request(req, state).await.map_err(
            |rejection: axum::extract::rejection::JsonRejection| {
                bad(&format!(
                    "Malformed request body: {}",
                    rejection.body_text()
                ))
            },
        )?;
        Ok(JsonBody(value))
    }
}
