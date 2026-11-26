use super::*;
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response, sse::{Event, Sse}},
    routing as rt,
};
use futures_util::stream;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{Mutex, broadcast, mpsc};
use uuid::Uuid;

/// Per-user live SSE subscribers. Notifications are persisted first (the
/// inbox is recoverable) and then fanned out to every open connection.
#[derive(Clone)]
pub struct Events {
    inner: Arc<Mutex<HashMap<Uuid, broadcast::Sender<String>>>>,
}

impl Events {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    async fn subscribe(&self, user: Uuid) -> broadcast::Receiver<String> {
        let mut map = self.inner.lock().await;
        map.entry(user)
            .or_insert_with(|| broadcast::channel(128).0)
            .subscribe()
    }

    async fn publish(&self, user: Uuid, message: String) {
        let mut map = self.inner.lock().await;
        if let Some(sender) = map.get(&user) {
            if sender.receiver_count() > 0 {
                let _ = sender.send(message);
            } else {
                map.remove(&user);
            }
        }
    }
}

pub fn routes() -> Router<App> {
    Router::new()
        .route("/notifications", rt::get(inbox).delete(clear_all))
        .route("/notifications/stream", rt::get(stream_notifications))
        .route("/notifications/unread-count", rt::get(unread_count))
        .route("/notifications/{id}/read", rt::post(mark_read))
        .route("/notifications/read-all", rt::post(mark_all_read))
        .route("/upsert/storage-access", rt::get(storage_access))
        .route("/internal/ocr-complete", rt::post(ocr_complete))
        .route("/internal/batch-ocr-complete", rt::post(batch_ocr_complete))
}

fn user_header(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("Invalid user context"))?;
    Uuid::parse_str(raw).map_err(|_| bad("Invalid user context"))
}

/// Persist a notification and broadcast it. `payload` is the map the client
/// receives; the inserted row id is added as `notificationId` on the wire.
pub async fn send_fields(
    a: &App,
    user: Uuid,
    status: &str,
    event: Option<&str>,
    message: &str,
    group: Option<i64>,
    payload: Value,
) {
    let payload_text = serde_json::to_string(&payload).unwrap_or_default();
    let inserted = sqlx::query_scalar::<_, i64>(
        "INSERT INTO finance.notifications \
         (user_id,status,event,message,group_id,payload,created_at) \
         VALUES ($1,$2,$3,$4,$5,$6,now()) RETURNING id",
    )
    .bind(user)
    .bind(status)
    .bind(event)
    .bind(message)
    .bind(group)
    .bind(&payload_text)
    .fetch_one(&a.db)
    .await;
    let mut outbound = payload;
    match inserted {
        Ok(id) => {
            if outbound.is_object() {
                outbound["notificationId"] = json!(id);
            }
        }
        Err(e) => {
            tracing::warn!(error = %e, %user, "failed to persist notification");
        }
    }
    let text = serde_json::to_string(&outbound).unwrap_or_default();
    a.events.publish(user, text).await;
}

pub async fn send_raw(a: &App, user: Uuid, payload: Value) {
    let status = payload["status"]
        .as_str()
        .unwrap_or("INFO")
        .to_string();
    let event = payload["event"].as_str().map(str::to_string);
    let message = payload["message"]
        .as_str()
        .map(str::to_string)
        .unwrap_or_else(|| payload.to_string());
    let group = payload["groupId"].as_i64().or_else(|| {
        payload["groupId"]
            .as_str()
            .and_then(|s| s.parse().ok())
    });
    send_fields(a, user, &status, event.as_deref(), &message, group, payload).await;
}

async fn stream_notifications(State(a): State<App>, h: HeaderMap) -> Result<Response> {
    let user = user_header(&h)?;
    let mut subscriber = a.events.subscribe(user).await;
    let (tx, rx) = mpsc::channel::<std::result::Result<Event, Infallible>>(32);
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_secs(15));
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let _ = tx
            .send(Ok(Event::default().event("ping").data("connected")))
            .await;
        loop {
            tokio::select! {
                received = subscriber.recv() => match received {
                    Ok(message) => {
                        if tx.send(Ok(Event::default().event("notification").data(message))).await.is_err() {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(broadcast::error::RecvError::Closed) => break,
                },
                _ = tick.tick() => {
                    if tx.send(Ok(Event::default().event("ping").data("heartbeat"))).await.is_err() {
                        break;
                    }
                }
            }
        }
    });
    let body = stream::unfold(rx, |mut rx| async move {
        rx.recv().await.map(|item| (item, rx))
    });
    let mut response = Sse::new(body).into_response();
    response.headers_mut().insert(
        "cache-control",
        HeaderValue::from_static("no-cache, no-store, must-revalidate"),
    );
    response
        .headers_mut()
        .insert("x-accel-buffering", HeaderValue::from_static("no"));
    Ok(response)
}

#[derive(Debug, sqlx::FromRow)]
struct NotificationRow {
    id: i64,
    status: String,
    event: Option<String>,
    message: String,
    group_id: Option<i64>,
    payload: Option<String>,
    created_at: chrono::NaiveDateTime,
    read_at: Option<chrono::NaiveDateTime>,
}

fn notification_json(n: &NotificationRow) -> Value {
    json!({
        "id": n.id,
        "status": n.status,
        "event": n.event,
        "message": n.message,
        "groupId": n.group_id,
        "payload": n.payload,
        "createdAt": ts(&n.created_at),
        "readAt": n.read_at.as_ref().map(ts),
    })
}

async fn inbox(
    State(a): State<App>,
    h: HeaderMap,
    Query(q): Query<Params>,
) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    let page: i64 = q.get("page").map(|v| v.parse().unwrap_or(0)).unwrap_or(0).max(0);
    let size: i64 = q
        .get("size")
        .map(|v| v.parse().unwrap_or(30))
        .unwrap_or(30)
        .clamp(1, 100);
    let rows = sqlx::query_as::<_, NotificationRow>(
        "SELECT id,status,event,message,group_id,payload,created_at,read_at \
         FROM finance.notifications WHERE user_id=$1 \
         ORDER BY created_at DESC LIMIT $2 OFFSET $3",
    )
    .bind(user)
    .bind(size)
    .bind(page * size)
    .fetch_all(&a.db)
    .await?;
    let total: i64 = sqlx::query_scalar("SELECT count(*) FROM finance.notifications WHERE user_id=$1")
        .bind(user)
        .fetch_one(&a.db)
        .await?;
    let unread: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM finance.notifications WHERE user_id=$1 AND read_at IS NULL",
    )
    .bind(user)
    .fetch_one(&a.db)
    .await?;
    let total_pages = (total + size - 1) / size;
    Ok(Json(json!({
        "items": rows.iter().map(notification_json).collect::<Vec<_>>(),
        "unread": unread,
        "page": page,
        "totalPages": total_pages,
    })))
}

async fn unread_count(State(a): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    let unread: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM finance.notifications WHERE user_id=$1 AND read_at IS NULL",
    )
    .bind(user)
    .fetch_one(&a.db)
    .await?;
    Ok(Json(json!({"unread":unread})))
}

async fn mark_read(
    State(a): State<App>,
    h: HeaderMap,
    Path(id): Path<i64>,
) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    sqlx::query(
        "UPDATE finance.notifications SET read_at=now() \
         WHERE id=$1 AND user_id=$2 AND read_at IS NULL",
    )
    .bind(id)
    .bind(user)
    .execute(&a.db)
    .await?;
    Ok(Json(json!({"message":"Notification marked read"})))
}

async fn mark_all_read(State(a): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    sqlx::query("UPDATE finance.notifications SET read_at=now() WHERE user_id=$1 AND read_at IS NULL")
        .bind(user)
        .execute(&a.db)
        .await?;
    Ok(Json(json!({"message":"All notifications marked read"})))
}

async fn clear_all(State(a): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    let removed = sqlx::query("DELETE FROM finance.notifications WHERE user_id=$1")
        .bind(user)
        .execute(&a.db)
        .await?
        .rows_affected();
    tracing::info!(user = %user, removed, "cleared notifications");
    Ok(Json(json!({"message":"Notifications cleared","removed":removed})))
}

fn belongs_to(url: &str, user: Uuid) -> bool {
    let prefix = format!("/api/storage/receipts/{user}");
    url.starts_with(&format!("{prefix}/")) || url.starts_with(&format!("{prefix}-batch-"))
}

async fn storage_access(
    State(a): State<App>,
    h: HeaderMap,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = user_header(&h)?;
    let url = q.get("url").cloned().unwrap_or_default();
    if belongs_to(&url, user) {
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    let owned_entry: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM finance.transaction_entries \
         WHERE receipt_url=$1 AND user_id=$2 AND deleted_at IS NULL)",
    )
    .bind(&url)
    .bind(user)
    .fetch_one(&a.db)
    .await?;
    if owned_entry {
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    let shared: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM groups.shared_expenses e \
         JOIN groups.group_members m ON m.group_id=e.group_id \
         WHERE e.receipt_url=$1 AND m.user_id=$2 AND m.status='ACCEPTED')",
    )
    .bind(&url)
    .bind(user)
    .fetch_one(&a.db)
    .await?;
    if shared {
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    Ok(StatusCode::NOT_FOUND.into_response())
}

async fn internal_authorized(a: &App, h: &HeaderMap) -> Result<()> {
    let configured = env("INTERNAL_API_TOKEN", "");
    if configured.is_empty() {
        tracing::error!("INTERNAL_API_TOKEN is not configured; refusing /internal request");
        return Err(Error(StatusCode::SERVICE_UNAVAILABLE, String::new()));
    }
    let presented = h
        .get("x-internal-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if presented != configured {
        tracing::warn!("rejected /internal request with wrong token");
        return Err(Error(StatusCode::UNAUTHORIZED, String::new()));
    }
    let _ = a;
    Ok(())
}

async fn ocr_complete(
    State(a): State<App>,
    h: HeaderMap,
    Json(mut body): Json<Value>,
) -> Result<Response> {
    internal_authorized(&a, &h).await?;
    handle_ocr(&a, &mut body, false).await
}

async fn batch_ocr_complete(
    State(a): State<App>,
    h: HeaderMap,
    Json(mut body): Json<Value>,
) -> Result<Response> {
    internal_authorized(&a, &h).await?;
    handle_ocr(&a, &mut body, true).await
}

async fn handle_ocr(a: &App, body: &mut Value, batch: bool) -> Result<Response> {
    let Some(user_str) = body["userId"].as_str() else {
        tracing::warn!("received OCR completion event with no userId");
        return Err(bad("userId is required"));
    };
    let user = Uuid::parse_str(user_str).map_err(|_| bad("Invalid userId"))?;
    if batch {
        body["type"] = json!("batch-ocr-completed");
    }
    let status = body["status"].as_str().unwrap_or("INFO");
    tracing::info!(%user, status, batch, "OCR completion notification");
    send_raw(a, user, body.clone()).await;
    Ok(StatusCode::OK.into_response())
}
