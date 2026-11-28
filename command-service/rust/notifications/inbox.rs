use super::user_header;
use crate::{App, Params, Result, ts};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderValue},
    response::{
        IntoResponse, Response,
        sse::{Event, Sse},
    },
};
use futures_util::stream;
use serde_json::{Value, json};
use std::convert::Infallible;
use std::time::Duration;
use tokio::sync::{broadcast, mpsc};

pub(super) async fn stream_notifications(State(a): State<App>, h: HeaderMap) -> Result<Response> {
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

pub(super) async fn inbox(
    State(a): State<App>,
    h: HeaderMap,
    Query(q): Query<Params>,
) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    let page: i64 = q
        .get("page")
        .map(|v| v.parse().unwrap_or(0))
        .unwrap_or(0)
        .max(0);
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
    let total: i64 =
        sqlx::query_scalar("SELECT count(*) FROM finance.notifications WHERE user_id=$1")
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

pub(super) async fn unread_count(State(a): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    let unread: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM finance.notifications WHERE user_id=$1 AND read_at IS NULL",
    )
    .bind(user)
    .fetch_one(&a.db)
    .await?;
    Ok(Json(json!({"unread":unread})))
}

pub(super) async fn mark_read(
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

pub(super) async fn mark_all_read(State(a): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    sqlx::query(
        "UPDATE finance.notifications SET read_at=now() WHERE user_id=$1 AND read_at IS NULL",
    )
    .bind(user)
    .execute(&a.db)
    .await?;
    Ok(Json(json!({"message":"All notifications marked read"})))
}

pub(super) async fn clear_all(State(a): State<App>, h: HeaderMap) -> Result<Json<Value>> {
    let user = user_header(&h)?;
    let removed = sqlx::query("DELETE FROM finance.notifications WHERE user_id=$1")
        .bind(user)
        .execute(&a.db)
        .await?
        .rows_affected();
    tracing::info!(user = %user, removed, "cleared notifications");
    Ok(Json(
        json!({"message":"Notifications cleared","removed":removed}),
    ))
}
