use pfa_query::serialization::{camel, page};
use serde_json::json;

#[test]
fn entity_timestamps_and_nested_keys_keep_the_legacy_shape() {
    let result = camel(json!({
        "created_at": "2026-11-28T10:00:00",
        "deleted_at": "2026-11-28T11:00:00Z",
        "rows": [{"user_id": "example", "expense_date": "2026-11-28T12:00:00"}],
    }));
    assert_eq!(
        result,
        json!({
            "createdAt": "2026-11-28T10:00:00Z",
            "deletedAt": "2026-11-28T11:00:00Z",
            "rows": [{"userId": "example", "expenseDate": "2026-11-28T12:00:00Z"}],
        })
    );
}

#[test]
fn page_metadata_matches_the_response_contract() {
    let result = page(vec![json!({"id": 21})], 21, 1, 20);
    assert_eq!(result["totalPages"], 2);
    assert_eq!(result["totalElements"], 21);
    assert_eq!(result["first"], false);
    assert_eq!(result["last"], true);
    assert_eq!(result["numberOfElements"], 1);
    assert_eq!(result["content"], json!([{"id": 21}]));
}

#[test]
fn an_empty_first_page_is_also_the_last_page() {
    let result = page(vec![], 0, 0, 20);
    assert_eq!(result["empty"], true);
    assert_eq!(result["first"], true);
    assert_eq!(result["last"], true);
    assert_eq!(result["totalPages"], 0);
}
