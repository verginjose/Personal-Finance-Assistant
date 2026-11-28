use axum::http::{HeaderMap, HeaderValue, StatusCode};
use pfa_command::storage::access::upload_user;

const USER: &str = "00000000-0000-4000-a000-000000000001";

#[test]
fn an_upload_requires_gateway_identity() {
    let error = upload_user(&HeaderMap::new(), "/api/profile/upload").unwrap_err();
    assert_eq!(error.0, StatusCode::UNAUTHORIZED);
}

#[test]
fn receipts_cannot_be_uploaded_for_another_user() {
    let mut headers = HeaderMap::new();
    headers.insert("x-user-id", HeaderValue::from_static(USER));
    let error = upload_user(
        &headers,
        "/api/bill/process/00000000-0000-4000-a000-000000000002",
    )
    .unwrap_err();
    assert_eq!(error.0, StatusCode::FORBIDDEN);
    assert!(upload_user(&headers, &format!("/api/bill/process/{USER}")).is_ok());
}

#[test]
fn profile_uploads_use_the_authenticated_user_without_a_path_id() {
    let mut headers = HeaderMap::new();
    headers.insert("x-user-id", HeaderValue::from_static(USER));
    assert_eq!(
        upload_user(&headers, "/api/profile/upload")
            .unwrap()
            .to_string(),
        USER
    );
}
