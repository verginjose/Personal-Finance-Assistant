use chrono::NaiveDateTime;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EntryRequest {
    pub id: Option<i64>,
    pub user_id: Option<Uuid>,
    pub name: Option<String>,
    pub amount: Option<Value>,
    pub r#type: Option<String>,
    pub category: Option<String>,
    pub currency: Option<String>,
    pub description: Option<String>,
    pub recurring: Option<bool>,
    pub recurring_period: Option<String>,
    pub created_at: Option<String>,
    pub receipt_url: Option<String>,
    pub transaction_kind: Option<String>,
    pub original_transaction_id: Option<i64>,
    pub subcategory: Option<String>,
}

#[derive(Clone)]
pub(crate) struct Validated {
    pub(crate) name: String,
    pub(crate) amount: Decimal,
    pub(crate) r#type: String,
    pub(crate) kind: String,
    pub(crate) category: Option<String>,
    pub(crate) currency: String,
    pub(crate) description: Option<String>,
    pub(crate) recurring: bool,
    pub(crate) period: Option<String>,
    pub(crate) created_at: Option<NaiveDateTime>,
    pub(crate) receipt_url: Option<String>,
    pub(crate) original_id: Option<i64>,
    pub(crate) subcategory: Option<String>,
}

#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
pub struct EntryRow {
    pub id: i64,
    pub user_id: Uuid,
    pub name: String,
    pub amount: Decimal,
    #[sqlx(rename = "type")]
    pub r#type: String,
    pub category: Option<String>,
    pub currency: String,
    pub description: Option<String>,
    pub created_at: NaiveDateTime,
    pub updated_at: Option<NaiveDateTime>,
    pub recurring: bool,
    pub recurring_period: Option<String>,
    pub next_run_date: Option<NaiveDateTime>,
    pub deleted_at: Option<NaiveDateTime>,
    pub receipt_url: Option<String>,
    pub transaction_kind: Option<String>,
    pub original_transaction_id: Option<i64>,
    pub subcategory: Option<String>,
}

