use chrono::NaiveDateTime;
use rust_decimal::Decimal;
use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
pub(crate) struct GroupRow {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) description: Option<String>,
    pub(crate) created_by: Uuid,
    pub(crate) currency: String,
    pub(crate) created_at: NaiveDateTime,
    pub(crate) is_deleted: bool,
}

#[derive(Clone, Debug, sqlx::FromRow)]
pub(crate) struct MemberRow {
    pub(crate) id: i64,
    pub(crate) group_id: i64,
    pub(crate) user_id: Uuid,
    pub(crate) name: String,
    pub(crate) joined_at: NaiveDateTime,
    pub(crate) status: String,
    pub(crate) is_archived: bool,
    pub(crate) role: Option<String>,
}

#[derive(Clone, Debug, sqlx::FromRow)]
pub(crate) struct ExpenseRow {
    pub(crate) id: i64,
    pub(crate) group_id: i64,
    pub(crate) description: String,
    pub(crate) amount: Decimal,
    pub(crate) currency: String,
    pub(crate) paid_by: Uuid,
    pub(crate) split_type: String,
    pub(crate) expense_category: Option<String>,
    pub(crate) expense_date: Option<NaiveDateTime>,
    pub(crate) created_at: NaiveDateTime,
    pub(crate) receipt_url: Option<String>,
}

#[derive(Clone, Debug, sqlx::FromRow)]
#[allow(dead_code)]
pub(crate) struct SplitRow {
    pub(crate) id: i64,
    pub(crate) shared_expense_id: i64,
    pub(crate) user_id: Uuid,
    pub(crate) user_name: Option<String>,
    pub(crate) amount: Decimal,
    pub(crate) is_settled: bool,
    pub(crate) settled_at: Option<NaiveDateTime>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct CreateGroup {
    pub(crate) name: Option<String>,
    pub(crate) description: Option<String>,
    pub(crate) created_by: Option<Uuid>,
    pub(crate) currency: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UpdateGroup {
    pub(crate) name: Option<String>,
    pub(crate) description: Option<String>,
    pub(crate) currency: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AddMember {
    pub(crate) user_id: Option<Uuid>,
    pub(crate) name: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SplitDetail {
    pub(crate) user_id: Option<Uuid>,
    pub(crate) value: Option<Value>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ExpenseRequest {
    pub(crate) description: Option<String>,
    pub(crate) amount: Option<Value>,
    pub(crate) currency: Option<String>,
    pub(crate) paid_by: Option<Uuid>,
    pub(crate) split_type: Option<String>,
    pub(crate) expense_category: Option<String>,
    pub(crate) expense_date: Option<String>,
    pub(crate) split_details: Option<Vec<SplitDetail>>,
    pub(crate) receipt_url: Option<String>,
}
