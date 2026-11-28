use crate::App;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, broadcast};
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

    pub(super) async fn subscribe(&self, user: Uuid) -> broadcast::Receiver<String> {
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

pub(crate) struct BatchNotification {
    pub user: Uuid,
    pub status: String,
    pub event: Option<String>,
    pub message: String,
    pub group: Option<i64>,
    pub payload: Value,
}

/// Persist many notifications in one multi-row INSERT, then publish each SSE
/// event. Returns nothing; failures are logged (same contract as send_fields).
pub async fn send_batch(a: &App, items: Vec<BatchNotification>) {
    if items.is_empty() {
        return;
    }
    let users: Vec<Uuid> = items.iter().map(|i| i.user).collect();
    let statuses: Vec<String> = items.iter().map(|i| i.status.clone()).collect();
    let events: Vec<Option<String>> = items.iter().map(|i| i.event.clone()).collect();
    let messages: Vec<String> = items.iter().map(|i| i.message.clone()).collect();
    let groups: Vec<Option<i64>> = items.iter().map(|i| i.group).collect();
    let payloads: Vec<String> = items
        .iter()
        .map(|i| serde_json::to_string(&i.payload).unwrap_or_default())
        .collect();
    let inserted: Vec<(i64, Uuid)> = match sqlx::query_as(
        "INSERT INTO finance.notifications (user_id,status,event,message,group_id,payload,created_at) \
         SELECT u,s,e,m,g,p,now() \
         FROM unnest($1::uuid[],$2::text[],$3::text[],$4::text[],$5::bigint[],$6::text[]) AS t(u,s,e,m,g,p) \
         RETURNING id, user_id",
    )
    .bind(&users)
    .bind(&statuses)
    .bind(&events)
    .bind(&messages)
    .bind(&groups)
    .bind(&payloads)
    .fetch_all(&a.db)
    .await
    {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!(error = %e, "failed to persist notifications");
            return;
        }
    };
    let mut ids: std::collections::HashMap<Uuid, i64> =
        inserted.into_iter().map(|(id, u)| (u, id)).collect();
    for item in items {
        let user = item.user;
        let mut outbound = item.payload;
        if let Some(id) = ids.remove(&user)
            && outbound.is_object()
        {
            outbound["notificationId"] = json!(id);
        }
        let text = serde_json::to_string(&outbound).unwrap_or_default();
        a.events.publish(user, text).await;
    }
}

pub async fn send_raw(a: &App, user: Uuid, payload: Value) {
    let status = payload["status"].as_str().unwrap_or("INFO").to_string();
    let event = payload["event"].as_str().map(str::to_string);
    let message = payload["message"]
        .as_str()
        .map(str::to_string)
        .unwrap_or_else(|| payload.to_string());
    let group = payload["groupId"]
        .as_i64()
        .or_else(|| payload["groupId"].as_str().and_then(|s| s.parse().ok()));
    send_fields(a, user, &status, event.as_deref(), &message, group, payload).await;
}
