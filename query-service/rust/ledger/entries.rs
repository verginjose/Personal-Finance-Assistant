use super::currency::decorate;
use crate::app::App;
use crate::error::{Error, Result, bad};
use crate::request::{Params, dates, paging};
use crate::serialization::{camel, page};
use axum::http::StatusCode;
use serde_json::{Value, json};
use uuid::Uuid;

pub async fn entries(
    a: &App,
    id: Uuid,
    p: &Params,
    single: Option<i64>,
    recurring: bool,
    goal: Option<i64>,
) -> Result<Value> {
    // /upsert/recurring returns the whole list and ignores page/size in Spring;
    // a single-entry read likewise has no paging contract.
    let (n, size) = if recurring {
        (0, 100000)
    } else if single.is_some() {
        (0, 1)
    } else {
        paging(p, 20)?
    };
    let (start, end) = dates(p, false)?;
    let typ = p.get("type").or(p.get("transactionType"));
    let cat = p.get("category").or(p.get("categoryName"));
    let search = p.get("q").map(|q| format!("%{q}%"));
    let filter = "t.user_id=$1 AND t.deleted_at IS NULL AND ($2::text IS NULL OR t.type::text=$2) AND ($3::text IS NULL OR t.category::text=$3) AND ($4::timestamp IS NULL OR t.created_at >=$4) AND ($5::timestamp IS NULL OR t.created_at <=$5) AND ($6::text IS NULL OR (t.name ILIKE $6 OR t.description ILIKE $6)) AND ($7::bigint IS NULL OR t.id=$7) AND (NOT $8 OR t.recurring) AND ($9::bigint IS NULL OR EXISTS(SELECT 1 FROM finance.transaction_goal_allocations ga WHERE ga.transaction_id=t.id AND ga.goal_id=$9))";
    let query = format!(
        "SELECT to_jsonb(t)||jsonb_build_object('group_id',g.id,'group_name',g.name,'total_amount',e.amount,'my_share',s.amount) FROM finance.transaction_entries t LEFT JOIN groups.expense_transaction_links l ON l.transaction_entry_id=t.id LEFT JOIN groups.shared_expenses e ON e.id=l.shared_expense_id LEFT JOIN groups.expense_groups g ON g.id=e.group_id LEFT JOIN groups.expense_splits s ON s.shared_expense_id=e.id AND s.user_id=t.user_id WHERE {filter} ORDER BY t.created_at DESC,t.id DESC LIMIT $10 OFFSET $11"
    );
    let mut rows: Vec<Value> = sqlx::query_scalar(&query)
        .bind(id)
        .bind(typ)
        .bind(cat)
        .bind(start)
        .bind(end)
        .bind(&search)
        .bind(single)
        .bind(recurring)
        .bind(goal)
        .bind(size)
        .bind(n.saturating_mul(size))
        .fetch_all(&a.db)
        .await?
        .into_iter()
        .map(camel)
        .collect();
    decorate(a, id, p, &mut rows).await?;
    if goal.is_some() {
        for row in &mut rows {
            row["amountDefault"] = Value::Null;
            if let Some(o) = row.as_object_mut() {
                o.retain(|k, _| {
                    [
                        "id",
                        "userId",
                        "name",
                        "amount",
                        "type",
                        "category",
                        "currency",
                        "description",
                        "recurring",
                        "recurringPeriod",
                        "createdAt",
                        "updatedAt",
                        "receiptUrl",
                        "amountDefault",
                        "transactionKind",
                        "originalTransactionId",
                        "subcategory",
                    ]
                    .contains(&k.as_str())
                });
            }
        }
    }
    if single.is_some() {
        let mut row = rows
            .into_iter()
            .next()
            .ok_or_else(|| Error(StatusCode::NOT_FOUND, "Transaction entry not found".into()))?;
        if let Some(o) = row.as_object_mut() {
            o.retain(|k, _| {
                [
                    "id",
                    "userId",
                    "name",
                    "amount",
                    "type",
                    "category",
                    "currency",
                    "description",
                    "recurring",
                    "recurringPeriod",
                    "createdAt",
                    "updatedAt",
                    "receiptUrl",
                    "amountDefault",
                    "transactionKind",
                    "originalTransactionId",
                    "subcategory",
                ]
                .contains(&k.as_str())
            });
        }
        return Ok(row);
    }
    if recurring {
        return Ok(Value::Array(rows));
    }
    let countsql = format!("SELECT count(*) FROM finance.transaction_entries t WHERE {filter}");
    let count = sqlx::query_scalar::<_, i64>(&countsql)
        .bind(id)
        .bind(typ)
        .bind(cat)
        .bind(start)
        .bind(end)
        .bind(search)
        .bind(single)
        .bind(recurring)
        .bind(goal)
        .fetch_one(&a.db)
        .await?;
    Ok(page(rows, count, n, size))
}
// These analytics endpoints expose the historical entity view, including soft
// deletion metadata. The public ledger endpoints above exclude deleted rows.
pub async fn analytics_entries(a: &App, id: Uuid, p: &Params, recent: bool) -> Result<Value> {
    let (n, size) = if recent { (0, 10) } else { paging(p, 20)? };
    let (start, end) = dates(p, false)?;
    let typ = p.get("type").or(p.get("transactionType"));
    let cat = p.get("categoryName");
    let filter = "user_id=$1 AND ($2::timestamp IS NULL OR created_at>=$2) AND ($3::timestamp IS NULL OR created_at<=$3) AND ($4::text IS NULL OR type::text=$4) AND ($5::text IS NULL OR category::text=$5)";
    let sort = if recent {
        " ORDER BY created_at DESC".to_string()
    } else if let Some(sort) = p.get("sort") {
        let bits: Vec<_> = sort.split(',').collect();
        let column = match bits[0] {
            "id" => "id",
            "amount" => "amount",
            "name" => "name",
            "createdAt" => "created_at",
            "updatedAt" => "updated_at",
            _ => return Err(bad("Invalid sort property")),
        };
        format!(
            " ORDER BY {column} {}",
            if bits.get(1) == Some(&"desc") {
                "DESC"
            } else {
                "ASC"
            }
        )
    } else {
        String::new()
    };
    let sql = format!(
        "SELECT to_jsonb(t) FROM finance.transaction_entries t WHERE {filter}{sort} LIMIT $6 OFFSET $7"
    );
    let rows: Vec<Value> = sqlx::query_scalar(&sql)
        .bind(id)
        .bind(start)
        .bind(end)
        .bind(typ)
        .bind(cat)
        .bind(size)
        .bind(n.saturating_mul(size))
        .fetch_all(&a.db)
        .await?;
    let rows = rows
        .into_iter()
        .map(|v| {
            let mut v = camel(v);
            for k in [
                "groupId",
                "groupName",
                "totalAmount",
                "myShare",
                "amountDefault",
            ] {
                v[k] = Value::Null;
            }
            v
        })
        .collect();
    if recent {
        return Ok(Value::Array(rows));
    }
    let count = sqlx::query_scalar::<_, i64>(&format!(
        "SELECT count(*) FROM finance.transaction_entries WHERE {filter}"
    ))
    .bind(id)
    .bind(start)
    .bind(end)
    .bind(typ)
    .bind(cat)
    .fetch_one(&a.db)
    .await?;
    let mut result = page(rows, count, n, size);
    let sort = json!({"empty":!p.contains_key("sort"),"sorted":p.contains_key("sort"),"unsorted":!p.contains_key("sort")});
    result["sort"] = sort.clone();
    result["pageable"] = json!({"pageNumber":n,"pageSize":size,"sort":sort,"offset":n*size,"paged":true,"unpaged":false});
    Ok(result)
}
