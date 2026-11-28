use axum::http::{HeaderMap, HeaderValue, StatusCode};
use pfa_query::request::{Params, dates, paging, required, user};
use uuid::Uuid;

const USER: &str = "00000000-0000-4000-a000-000000000001";

#[test]
fn missing_identity_is_unauthorized() {
    let error = user(&HeaderMap::new(), &Params::new()).unwrap_err();
    assert_eq!(error.0, StatusCode::UNAUTHORIZED);
}

#[test]
fn mismatched_user_is_forbidden_before_uuid_parsing() {
    let mut headers = HeaderMap::new();
    headers.insert("x-user-id", HeaderValue::from_static(USER));
    let params = Params::from([("userId".into(), "malformed-other-user".into())]);
    assert_eq!(
        user(&headers, &params).unwrap_err().0,
        StatusCode::FORBIDDEN
    );
}

#[test]
fn matching_uuid_is_case_insensitive() {
    let mut headers = HeaderMap::new();
    headers.insert("x-user-id", HeaderValue::from_static(USER));
    let params = Params::from([("userId".into(), USER.to_uppercase())]);
    assert_eq!(
        user(&headers, &params).unwrap(),
        Uuid::parse_str(USER).unwrap()
    );
}

#[test]
fn missing_required_query_parameters_are_bad_requests() {
    assert_eq!(
        required(&Params::new(), &["userId"]).unwrap_err().0,
        StatusCode::BAD_REQUEST
    );
}

#[test]
fn pagination_rejects_invalid_values_instead_of_clamping() {
    for (key, value) in [("page", "-1"), ("size", "0"), ("page", "text")] {
        let params = Params::from([(key.into(), value.into())]);
        assert_eq!(paging(&params, 20).unwrap_err().0, StatusCode::BAD_REQUEST);
    }
    assert_eq!(paging(&Params::new(), 20).unwrap(), (0, 20));
}

#[test]
fn analytics_dates_apply_the_local_timezone_offset() {
    let params = Params::from([
        ("startDate".into(), "2026-11-28".into()),
        ("endDate".into(), "2026-11-28".into()),
        ("tzOffsetMinutes".into(), "330".into()),
    ]);
    let (start, end) = dates(&params, true).unwrap();
    assert_eq!(start.unwrap().to_string(), "2026-11-27 18:30:00");
    assert_eq!(end.unwrap().to_string(), "2026-11-28 18:29:59");
    let (start, _) = dates(&params, false).unwrap();
    assert_eq!(start.unwrap().to_string(), "2026-11-28 00:00:00");
}
