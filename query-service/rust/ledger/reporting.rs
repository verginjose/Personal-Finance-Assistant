use crate::app::App;
use crate::error::Result;
use crate::request::{Params, dates};
use serde_json::Value;
use uuid::Uuid;

pub const REPORTABLE: &str = "coalesce(transaction_kind,'STANDARD') NOT IN ('TRANSFER','INVESTMENT') AND category NOT IN ('SETTLEMENT','CREDIT_CARD_PAYMENT','MUTUAL_FUNDS_SIP','STOCKS_AND_TRADING','CRYPTO','EMERGENCY_FUND','FIXED_DEPOSIT','GOAL','OWN_ACCOUNT_TRANSFER','BORROWING','LENDING','LOAN_PRINCIPAL','INVESTMENT_WITHDRAWAL')";
pub fn reporting() -> String {
    format!(
        "WITH reporting AS (SELECT *, CASE WHEN transaction_kind IN ('REFUND','REIMBURSEMENT') THEN 'EXPENSE' ELSE type END AS report_type, CASE WHEN transaction_kind IN ('REFUND','REIMBURSEMENT') THEN -amount ELSE amount END AS report_amount FROM finance.transaction_entries WHERE deleted_at IS NULL AND {REPORTABLE}) "
    )
}
pub async fn summary(a: &App, id: Uuid, p: &Params) -> Result<Value> {
    let (start, end) = dates(p, false)?;
    let sql = format!(
        "{} SELECT jsonb_build_object('totalIncome',coalesce(sum(report_amount) FILTER(WHERE report_type='INCOME'),0),'totalExpense',coalesce(sum(report_amount) FILTER(WHERE report_type='EXPENSE'),0),'netBalance',coalesce(sum(CASE WHEN report_type='INCOME' THEN report_amount ELSE -report_amount END),0),'totalCount',count(*)) FROM reporting WHERE user_id=$1 AND ($2::timestamp IS NULL OR created_at >=$2) AND ($3::timestamp IS NULL OR created_at <=$3)",
        reporting()
    );
    Ok(sqlx::query_scalar(&sql)
        .bind(id)
        .bind(start)
        .bind(end)
        .fetch_one(&a.db)
        .await?)
}
