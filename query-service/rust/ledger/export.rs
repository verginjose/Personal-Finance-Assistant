use crate::app::App;
use axum::response::IntoResponse;
use axum::response::Response;
use serde_json::Value;
use uuid::Uuid;

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
pub(super) fn export(a: &App, id: Uuid) -> Response {
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
