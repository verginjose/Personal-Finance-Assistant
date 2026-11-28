use crate::app::App;
use crate::error::{Error, Result, bad};
use crate::request::{Params, dates, integer};
use crate::{ledger, planning};
use axum::http::StatusCode;
use serde_json::{Value, json};
use uuid::Uuid;
fn dataset(label: &str, data: Vec<Value>) -> Value {
    json!({"label":label,"data":data,"backgroundColor":null,"borderColor":null,"borderWidth":1})
}
async fn categories(a: &App, id: Uuid, p: &Params) -> Result<Vec<Value>> {
    let (start, end) = dates(p, true)?;
    let sql = format!(
        "{} SELECT jsonb_build_object('category',category,'amount',sum(report_amount),'count',count(*)) FROM reporting WHERE user_id=$1 AND ($2::timestamp IS NULL OR created_at>=$2) AND ($3::timestamp IS NULL OR created_at<=$3) AND ($4::text IS NULL OR report_type=$4) GROUP BY category ORDER BY sum(report_amount) DESC",
        ledger::reporting()
    );
    Ok(sqlx::query_scalar(&sql)
        .bind(id)
        .bind(start)
        .bind(end)
        .bind(
            p.get("transactionFilter")
                .map(String::as_str)
                .or(Some("EXPENSE")),
        )
        .fetch_all(&a.db)
        .await?)
}
async fn category(a: &App, id: Uuid, p: &Params) -> Result<Value> {
    let rows = categories(a, id, p).await?;
    let negative = rows
        .iter()
        .any(|r| ledger::decimal(&r["amount"]).is_some_and(|d| d.is_sign_negative()));
    let labels: Vec<_> = rows.iter().map(|r| r["category"].clone()).collect();
    let data = rows.iter().map(|r| r["amount"].clone()).collect();
    let title = p
        .get("transactionFilter")
        .map(|s| format!("{s} Category Distribution"))
        .unwrap_or_else(|| "Category Distribution".into());
    Ok(
        json!({"chartType":if negative{"bar"}else{"pie"},"title":title,"labels":labels,"datasets":[dataset("Amount",data)]}),
    )
}
async fn timeline(a: &App, id: Uuid, p: &Params) -> Result<Value> {
    let (start, end) = dates(p, true)?;
    let offset = integer(p, "tzOffsetMinutes", 0)?.clamp(-1080, 1080);
    let kind = p
        .get("timelineType")
        .map(|v| v.to_uppercase())
        .unwrap_or_else(|| "MONTHLY".into());
    let (unit, format, name) = match kind.as_str() {
        "DAILY" => ("day", "YYYY-MM-DD", "Daily"),
        "WEEKLY" => ("week", "IYYY-\"W\"IW", "Weekly"),
        "YEARLY" => ("year", "YYYY", "Yearly"),
        _ => ("month", "YYYY-MM", "Monthly"),
    };
    let sql = format!(
        "{} SELECT jsonb_build_object('label',to_char(date_trunc('{unit}',created_at + $4 * interval '1 minute'),'{format}'),'income',coalesce(sum(report_amount) FILTER(WHERE report_type='INCOME'),0),'expense',coalesce(sum(report_amount) FILTER(WHERE report_type='EXPENSE'),0)) FROM reporting WHERE user_id=$1 AND ($2::timestamp IS NULL OR created_at>=$2) AND ($3::timestamp IS NULL OR created_at<=$3) GROUP BY date_trunc('{unit}',created_at + $4 * interval '1 minute') ORDER BY date_trunc('{unit}',created_at + $4 * interval '1 minute')",
        ledger::reporting()
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(id)
        .bind(start)
        .bind(end)
        .bind(offset as i32)
        .fetch_all(&a.db)
        .await?;
    let mut ds = vec![];
    let filter = p.get("transactionFilter").map(String::as_str);
    if filter.is_none() || filter == Some("INCOME") {
        ds.push(dataset(
            "Income",
            rows.iter().map(|r| r["income"].clone()).collect(),
        ))
    }
    if filter.is_none() || filter == Some("EXPENSE") {
        ds.push(dataset(
            "Expense",
            rows.iter().map(|r| r["expense"].clone()).collect(),
        ))
    }
    Ok(
        json!({"chartType":"line","title":format!("{}{name} Transaction Trends",filter.map(|s|format!("{s} ")).unwrap_or_default()),"labels":rows.iter().map(|r|r["label"].clone()).collect::<Vec<_>>(),"datasets":ds}),
    )
}
pub async fn handle(a: &App, id: Uuid, path: &str, p: &Params) -> Result<Value> {
    match path {
        "/analytics/category-pie-chart" => category(a, id, p).await,
        "/analytics/timeline-chart" => timeline(a, id, p).await,
        "/analytics/comprehensive" => {
            let mut params = p.clone();
            params.remove("transactionFilter");
            let mut shifted = params.clone();
            let (start, end) = dates(p, true)?;
            if let Some(d) = start {
                shifted.insert("startDate".into(), d.to_string().replace(' ', "T"));
            }
            if let Some(d) = end {
                shifted.insert("endDate".into(), d.to_string().replace(' ', "T"));
            }
            let totals = ledger::summary(a, id, &shifted).await?;
            let mut income = params.clone();
            income.insert("transactionFilter".into(), "INCOME".into());
            let mut expense = params.clone();
            expense.insert("transactionFilter".into(), "EXPENSE".into());
            let rows = ledger::analytics_entries(a, id, &Params::new(), true).await?;
            Ok(
                json!({"totalIncome":totals["totalIncome"],"totalExpense":totals["totalExpense"],"netAmount":totals["netBalance"],"incomeByCategory":category(a,id,&income).await?,"expenseByCategory":category(a,id,&expense).await?,"timelineTrends":timeline(a,id,&params).await?,"recentTransactions":rows,"transactionCount":totals["totalCount"]}),
            )
        }
        "/analytics/transaction-entries"
        | "/analytics/transactions/by-type"
        | "/analytics/transactions/income-by-category" => {
            let mut params = p.clone();
            if path.ends_with("income-by-category") {
                params.insert("type".into(), "INCOME".into());
            }
            ledger::analytics_entries(a, id, &params, false).await
        }
        "/analytics/budgets/trends" => planning::trends(a, id).await,
        "/analytics/ai-insights" => planning::insights(a, id, p).await,
        _ => {
            if let Some(v) = path
                .strip_prefix("/analytics/goals/")
                .and_then(|v| v.strip_suffix("/forecast"))
            {
                planning::forecast(a, id, v.parse().map_err(|_| bad("Invalid goal ID"))?).await
            } else {
                Err(Error(StatusCode::NOT_FOUND, "Not found".into()))
            }
        }
    }
}
