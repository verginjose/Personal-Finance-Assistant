use super::*;
use rust_decimal::Decimal;
use std::str::FromStr;
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
pub async fn decorate(a: &App, id: Uuid, p: &Params, rows: &mut [Value]) -> Result<()> {
    let target = if let Some(t) = p.get("targetCurrency") {
        Some(t.trim().to_uppercase())
    } else {
        sqlx::query_scalar::<_, String>("SELECT default_currency FROM auth.users WHERE id=$1")
            .bind(id)
            .fetch_optional(&a.db)
            .await?
    };
    for row in rows {
        row["amountDefault"] = Value::Null;
        let Some(target) = target.as_ref() else {
            continue;
        };
        let source = row["currency"].as_str().unwrap_or("").to_uppercase();
        if let Some(amount) = decimal(&row["amount"]) {
            if source == *target {
                row["amountDefault"] = money(amount)
            } else if let Some(rate) = rate(a, &source, target).await {
                row["amountDefault"] = money((amount * rate).round_dp_with_strategy(
                    2,
                    rust_decimal::RoundingStrategy::MidpointAwayFromZero,
                ));
            }
        }
    }
    Ok(())
}
pub fn decimal(v: &Value) -> Option<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).ok()
}
pub fn money(d: Decimal) -> Value {
    serde_json::from_str(&d.to_string()).unwrap_or(Value::Null)
}
async fn rate(a: &App, from: &str, to: &str) -> Option<Decimal> {
    if ![from, to]
        .iter()
        .all(|s| s.len() == 3 && s.bytes().all(|b| b.is_ascii_uppercase()))
    {
        return None;
    }
    let gate = {
        let mut gates = a.fx_locks.lock().await;
        if gates.len() >= 256 && !gates.contains_key(from) {
            gates.clear();
        }
        gates
            .entry(from.into())
            .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
            .clone()
    };
    let _guard = gate.lock().await;
    let cached = a.fx.lock().await.get(from).cloned();
    if let Some((at, v)) = &cached
        && at.elapsed() < Duration::from_secs(21600)
    {
        return decimal(&v["rates"][to]);
    }
    let value = match a
        .http
        .get(env("FX_RATES_URL", "https://api.frankfurter.dev/v1/latest"))
        .query(&[("base", from)])
        .timeout(Duration::from_secs(5))
        .send()
        .await
    {
        Ok(r) if r.status().is_success() => r.json::<Value>().await.ok(),
        _ => None,
    };
    let (at, value) = if let Some(value) = value {
        (std::time::Instant::now(), value)
    } else {
        (
            std::time::Instant::now() - Duration::from_secs(21540),
            cached.map(|(_, v)| v).unwrap_or(Value::Null),
        )
    };
    let result = decimal(&value["rates"][to]);
    let mut cache = a.fx.lock().await;
    if cache.len() >= 256 && !cache.contains_key(from) {
        cache.clear();
    }
    cache.insert(from.into(), (at, value));
    result
}

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
pub async fn handle(a: &App, id: Uuid, path: &str, p: &Params) -> Result<Response> {
    let v = if path == "/upsert/summary" {
        summary(a, id, p).await?
    } else if path == "/upsert/entries" || path == "/upsert/search" {
        entries(a, id, p, None, false, None).await?
    } else if path == "/upsert/recurring" {
        entries(a, id, p, None, true, None).await?
    } else if path == "/upsert/entries/export" {
        return Ok(export(a, id));
    } else if let Some(v) = path.strip_prefix("/upsert/entries/") {
        entries(
            a,
            id,
            p,
            Some(v.parse().map_err(|_| bad("Invalid entry ID"))?),
            false,
            None,
        )
        .await?
    } else {
        return Err(Error(StatusCode::NOT_FOUND, "Not found".into()));
    };
    Ok(Json(v).into_response())
}

// These analytics endpoints expose the historical entity view, including soft
// deletion metadata. The public ledger endpoints above exclude deleted rows.
pub async fn analytics_entries(a: &App, id: Uuid, p: &Params, recent: bool) -> Result<Value> {
    let (n, size) = if recent {
        (0, 10)
    } else {
        paging(p, 20)?
    };
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

// Spring only wraps a field in quotes when it actually contains a delimiter,
// quote or newline, so reproduce that instead of quoting every value.
fn csv_field(value: String) -> String {
    if value.contains([',', '"', '\n', '\r']) {
        format!("\"{}\"", value.replace('"', "\"\""))
    } else {
        value
    }
}
fn csv_line(row: &Value) -> String {
    let fields = [
        "id",
        "name",
        "amount",
        "type",
        "category",
        "currency",
        "description",
        "recurring",
        "recurring_period",
        "created_at",
        "transaction_kind",
        "original_transaction_id",
        "subcategory",
    ];
    let values: Vec<_> = fields
        .into_iter()
        .map(|key| {
            let value = match &row[key] {
                Value::Null if key == "transaction_kind" => "STANDARD".into(),
                Value::Null => String::new(),
                Value::String(s) => s.clone(),
                v => v.to_string(),
            };
            csv_field(value)
        })
        .collect();
    values.join(",") + "\n"
}
fn export(a: &App, id: Uuid) -> Response {
    use futures_util::StreamExt;
    let (sender, receiver) =
        tokio::sync::mpsc::channel::<std::result::Result<String, std::io::Error>>(2);
    let db = a.db.clone();
    tokio::spawn(async move {
        if sender.send(Ok("id,name,amount,type,category,currency,description,recurring,recurringPeriod,createdAt,transactionKind,originalTransactionId,subcategory\n".into())).await.is_err(){return;}
        let mut rows=sqlx::query_scalar::<_,Value>("SELECT to_jsonb(t) FROM finance.transaction_entries t WHERE user_id=$1 AND deleted_at IS NULL ORDER BY created_at DESC,id DESC").bind(id).fetch(&db);
        while let Some(row) = rows.next().await {
            match row {
                Ok(v) => {
                    if sender.send(Ok(csv_line(&v))).await.is_err() {
                        break;
                    }
                }
                Err(e) => {
                    tracing::error!(error=%e,"CSV export failed");
                    let _ = sender
                        .send(Err(std::io::Error::other("CSV export failed")))
                        .await;
                    break;
                }
            }
        }
    });
    let stream = futures_util::stream::unfold(receiver, |mut receiver| async move {
        receiver.recv().await.map(|chunk| (chunk, receiver))
    });
    let mut response = axum::body::Body::from_stream(stream).into_response();
    response.headers_mut().insert(
        "content-type",
        axum::http::HeaderValue::from_static("text/csv"),
    );
    response.headers_mut().insert(
        "content-disposition",
        axum::http::HeaderValue::from_static("attachment; filename=\"transactions.csv\""),
    );
    response
}
