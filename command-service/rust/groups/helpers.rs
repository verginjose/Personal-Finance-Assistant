use super::GROUP_COLS;
use super::MEMBER_COLS;
use super::models::{ExpenseRow, GroupRow, MemberRow};
use crate::{App, Params, Result};
use crate::{bad, forbidden, money, not_found, notifications, ts};
use axum::http::HeaderMap;
use serde_json::{Value, json};
use sqlx::{Postgres, Transaction};
use uuid::Uuid;

pub(crate) fn actor(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("Invalid X-User-Id header"))?;
    Uuid::parse_str(raw).map_err(|_| bad("Invalid X-User-Id header"))
}

pub(crate) fn q_bool(q: &Params, key: &str, default: bool) -> Result<bool> {
    match q.get(key) {
        None => Ok(default),
        Some(v) => v
            .parse()
            .map_err(|_| bad(&format!("Invalid value for parameter '{key}'"))),
    }
}

pub(crate) fn q_uuid(q: &Params, key: &str) -> Result<Uuid> {
    let raw = q
        .get(key)
        .ok_or_else(|| bad(&format!("Required request parameter '{key}' is missing")))?;
    Uuid::parse_str(raw).map_err(|_| bad(&format!("Invalid value for parameter '{key}'")))
}

pub(crate) fn group_json(g: &GroupRow) -> Value {
    json!({
        "id": g.id,
        "name": g.name,
        "description": g.description,
        "createdBy": g.created_by,
        "currency": g.currency,
        "createdAt": ts(&g.created_at),
        "deleted": g.is_deleted,
        "isArchived": Value::Null,
        "currentUserStatus": Value::Null,
        "currentUserRole": Value::Null,
        "currentUserNetBalance": Value::Null,
    })
}

pub(crate) fn member_json(m: &MemberRow) -> Value {
    json!({
        "id": m.id,
        "groupId": m.group_id,
        "userId": m.user_id,
        "name": m.name,
        "joinedAt": ts(&m.joined_at),
        "status": m.status,
        "archived": m.is_archived,
        "role": m.role.clone().unwrap_or_else(|| "MEMBER".into()),
        "admin": m.role.as_deref() == Some("ADMIN"),
    })
}

pub(crate) fn expense_json(e: &ExpenseRow) -> Value {
    json!({
        "id": e.id,
        "groupId": e.group_id,
        "description": e.description,
        "amount": money(e.amount),
        "currency": e.currency,
        "paidBy": e.paid_by,
        "splitType": e.split_type,
        "expenseCategory": e.expense_category,
        "expenseDate": e.expense_date.as_ref().map(ts),
        "createdAt": ts(&e.created_at),
        "receiptUrl": e.receipt_url,
    })
}

pub(crate) async fn group(tx: &mut Transaction<'_, Postgres>, id: i64) -> Result<GroupRow> {
    sqlx::query_as::<_, GroupRow>(&format!(
        "SELECT {GROUP_COLS} FROM groups.expense_groups WHERE id=$1 AND NOT is_deleted"
    ))
    .bind(id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| not_found(&format!("Group {id} not found")))
}

pub(crate) async fn members(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
) -> Result<Vec<MemberRow>> {
    Ok(sqlx::query_as::<_, MemberRow>(&format!(
        "SELECT {MEMBER_COLS} FROM groups.group_members WHERE group_id=$1 ORDER BY id"
    ))
    .bind(group)
    .fetch_all(&mut **tx)
    .await?)
}

pub(crate) async fn member(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    user: Uuid,
) -> Result<Option<MemberRow>> {
    Ok(sqlx::query_as::<_, MemberRow>(&format!(
        "SELECT {MEMBER_COLS} FROM groups.group_members WHERE group_id=$1 AND user_id=$2"
    ))
    .bind(group)
    .bind(user)
    .fetch_optional(&mut **tx)
    .await?)
}

pub(crate) async fn display_name(tx: &mut Transaction<'_, Postgres>, user: Uuid) -> Result<String> {
    let row: Option<(Option<String>, Option<String>)> =
        sqlx::query_as("SELECT username, email FROM auth.users WHERE id=$1")
            .bind(user)
            .fetch_optional(&mut **tx)
            .await?;
    let (username, email) =
        row.ok_or_else(|| bad("User not found. Only registered users can be added to groups."))?;
    username
        .or(email)
        .ok_or_else(|| bad("User not found. Only registered users can be added to groups."))
}

pub(crate) async fn require_admin(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    user: Uuid,
) -> Result<MemberRow> {
    let m = member(tx, group, user)
        .await?
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    if m.role.as_deref() != Some("ADMIN") {
        return Err(forbidden("Only group admins can do that"));
    }
    Ok(m)
}

pub(crate) async fn require_member(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    user: Uuid,
) -> Result<MemberRow> {
    member(tx, group, user)
        .await?
        .ok_or_else(|| forbidden("User is not a member of this group"))
}

pub(crate) async fn count_other_admins(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    excluding: Uuid,
) -> Result<i64> {
    Ok(sqlx::query_scalar::<_, i64>(
        "SELECT count(*) FROM groups.group_members \
         WHERE group_id=$1 AND status='ACCEPTED' AND role='ADMIN' AND user_id<>$2",
    )
    .bind(group)
    .bind(excluding)
    .fetch_one(&mut **tx)
    .await?)
}

pub(crate) async fn log_activity(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    actor: Uuid,
    actor_name: &str,
    kind: &str,
    message: &str,
    reference: i64,
) -> Result<()> {
    sqlx::query(
        "INSERT INTO activity.group_activities \
         (group_id, actor_user_id, actor_name, activity_type, message, reference_id, created_at) \
         VALUES ($1,$2,$3,$4,$5,$6,now())",
    )
    .bind(group)
    .bind(actor)
    .bind(actor_name)
    .bind(kind)
    .bind(message)
    .bind(reference)
    .execute(&mut **tx)
    .await?;
    Ok(())
}

#[derive(Clone)]
pub(crate) struct Notice {
    pub(crate) user: Uuid,
    pub(crate) status: String,
    pub(crate) event: String,
    pub(crate) message: String,
    pub(crate) group: i64,
    pub(crate) payload: Value,
}

impl Notice {
    pub(crate) fn new(user: Uuid, group: i64, event: &str, message: String, status: &str) -> Self {
        Self {
            user,
            status: status.into(),
            event: event.into(),
            message: message.clone(),
            group,
            payload: json!({
                "status": status,
                "event": event,
                "message": message,
                "groupId": group,
            }),
        }
    }
}

pub(crate) async fn send_notices(a: &App, notices: Vec<Notice>) {
    let items = notices
        .into_iter()
        .map(|notice| notifications::BatchNotification {
            user: notice.user,
            status: notice.status,
            event: Some(notice.event),
            message: notice.message,
            group: Some(notice.group),
            payload: notice.payload,
        })
        .collect();
    notifications::send_batch(a, items).await;
}
