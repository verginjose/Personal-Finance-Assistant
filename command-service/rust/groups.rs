use super::*;
use crate::{bad, cache, forbidden, money, not_found, notifications, ts};
use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing as rt,
};
use chrono::NaiveDateTime;
use rust_decimal::{Decimal, prelude::ToPrimitive};
use serde::Deserialize;
use serde_json::{Value, json};
use sqlx::{Postgres, Transaction};
use std::collections::{HashMap, HashSet};
use std::str::FromStr;
use uuid::Uuid;

const GROUP_COLS: &str = "id,name,description,created_by,currency,created_at,is_deleted";
const MEMBER_COLS: &str =
    "id,group_id,user_id,name,joined_at,status::text AS status,is_archived,role::text AS role";
const EXPENSE_COLS: &str =
    "id,group_id,description,amount,currency,paid_by,split_type::text AS split_type,\
expense_category::text AS expense_category,expense_date,created_at,receipt_url";
const SPLIT_COLS: &str =
    "id,shared_expense_id,user_id,user_name,amount,is_settled,settled_at";

#[derive(Debug, sqlx::FromRow)]
#[allow(dead_code)]
struct GroupRow {
    id: i64,
    name: String,
    description: Option<String>,
    created_by: Uuid,
    currency: String,
    created_at: NaiveDateTime,
    is_deleted: bool,
}

#[derive(Clone, Debug, sqlx::FromRow)]
struct MemberRow {
    id: i64,
    group_id: i64,
    user_id: Uuid,
    name: String,
    joined_at: NaiveDateTime,
    status: String,
    is_archived: bool,
    role: Option<String>,
}

#[derive(Clone, Debug, sqlx::FromRow)]
struct ExpenseRow {
    id: i64,
    group_id: i64,
    description: String,
    amount: Decimal,
    currency: String,
    paid_by: Uuid,
    split_type: String,
    expense_category: Option<String>,
    expense_date: Option<NaiveDateTime>,
    created_at: NaiveDateTime,
    receipt_url: Option<String>,
}

#[derive(Clone, Debug, sqlx::FromRow)]
#[allow(dead_code)]
struct SplitRow {
    id: i64,
    shared_expense_id: i64,
    user_id: Uuid,
    user_name: Option<String>,
    amount: Decimal,
    is_settled: bool,
    settled_at: Option<NaiveDateTime>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateGroup {
    name: Option<String>,
    description: Option<String>,
    created_by: Option<Uuid>,
    currency: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpdateGroup {
    name: Option<String>,
    description: Option<String>,
    currency: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AddMember {
    user_id: Option<Uuid>,
    name: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct SplitDetail {
    user_id: Option<Uuid>,
    value: Option<Value>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ExpenseRequest {
    description: Option<String>,
    amount: Option<Value>,
    currency: Option<String>,
    paid_by: Option<Uuid>,
    split_type: Option<String>,
    expense_category: Option<String>,
    expense_date: Option<String>,
    split_details: Option<Vec<SplitDetail>>,
    receipt_url: Option<String>,
}

pub fn routes() -> Router<App> {
    Router::new()
        .route("/upsert/groups", rt::post(create_group))
        .route(
            "/upsert/groups/{group_id}",
            rt::put(update_group).delete(delete_group),
        )
        .route("/upsert/groups/{group_id}/archive", rt::put(archive_group))
        .route("/upsert/groups/{group_id}/members", rt::post(add_member))
        .route(
            "/upsert/groups/{group_id}/members/{member_user_id}/accept",
            rt::post(accept_invitation),
        )
        .route(
            "/upsert/groups/{group_id}/members/{member_user_id}/reject",
            rt::post(reject_invitation),
        )
        .route(
            "/upsert/groups/{group_id}/members/{member_user_id}",
            rt::delete(remove_or_leave),
        )
        .route(
            "/upsert/groups/{group_id}/members/{member_user_id}/admin",
            rt::put(set_admin),
        )
        .route(
            "/upsert/groups/{group_id}/expenses",
            rt::post(add_expense),
        )
        .route(
            "/upsert/groups/{group_id}/expenses/{expense_id}",
            rt::put(update_expense).delete(delete_expense),
        )
        .route("/upsert/groups/{group_id}/settle", rt::post(settle_debt))
}

fn actor(h: &HeaderMap) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| bad("Invalid X-User-Id header"))?;
    Uuid::parse_str(raw).map_err(|_| bad("Invalid X-User-Id header"))
}

fn q_bool(q: &Params, key: &str, default: bool) -> Result<bool> {
    match q.get(key) {
        None => Ok(default),
        Some(v) => v
            .parse()
            .map_err(|_| bad(&format!("Invalid value for parameter '{key}'"))),
    }
}

fn q_uuid(q: &Params, key: &str) -> Result<Uuid> {
    let raw = q
        .get(key)
        .ok_or_else(|| bad(&format!("Required request parameter '{key}' is missing")))?;
    Uuid::parse_str(raw).map_err(|_| bad(&format!("Invalid value for parameter '{key}'")))
}

fn group_json(g: &GroupRow) -> Value {
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

fn member_json(m: &MemberRow) -> Value {
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

fn expense_json(e: &ExpenseRow) -> Value {
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

async fn group(tx: &mut Transaction<'_, Postgres>, id: i64) -> Result<GroupRow> {
    sqlx::query_as::<_, GroupRow>(&format!(
        "SELECT {GROUP_COLS} FROM groups.expense_groups WHERE id=$1 AND NOT is_deleted"
    ))
    .bind(id)
    .fetch_optional(&mut **tx)
    .await?
    .ok_or_else(|| not_found(&format!("Group {id} not found")))
}

async fn members(tx: &mut Transaction<'_, Postgres>, group: i64) -> Result<Vec<MemberRow>> {
    Ok(sqlx::query_as::<_, MemberRow>(&format!(
        "SELECT {MEMBER_COLS} FROM groups.group_members WHERE group_id=$1 ORDER BY id"
    ))
    .bind(group)
    .fetch_all(&mut **tx)
    .await?)
}

async fn member(
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

async fn display_name(tx: &mut Transaction<'_, Postgres>, user: Uuid) -> Result<String> {
    let row: Option<(Option<String>, Option<String>)> =
        sqlx::query_as("SELECT username, email FROM auth.users WHERE id=$1")
            .bind(user)
            .fetch_optional(&mut **tx)
            .await?;
    let (username, email) = row.ok_or_else(|| {
        bad("User not found. Only registered users can be added to groups.")
    })?;
    username
        .or(email)
        .ok_or_else(|| bad("User not found. Only registered users can be added to groups."))
}

async fn require_admin(
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

async fn require_member(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    user: Uuid,
) -> Result<MemberRow> {
    member(tx, group, user)
        .await?
        .ok_or_else(|| forbidden("User is not a member of this group"))
}

async fn count_other_admins(
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

async fn log_activity(
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
struct Notice {
    user: Uuid,
    status: String,
    event: String,
    message: String,
    group: i64,
    payload: Value,
}

impl Notice {
    fn new(user: Uuid, group: i64, event: &str, message: String, status: &str) -> Self {
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

async fn send_notices(a: &App, notices: Vec<Notice>) {
    for n in notices {
        notifications::send_fields(
            a,
            n.user,
            &n.status,
            Some(&n.event),
            &n.message,
            Some(n.group),
            n.payload,
        )
        .await;
    }
}

async fn create_group(
    State(a): State<App>,
    h: HeaderMap,
    JsonBody(body): JsonBody<CreateGroup>,
) -> Result<Response> {
    let user = actor(&h)?;
    let created_by = body
        .created_by
        .ok_or_else(|| bad("createdBy is required"))?;
    if created_by != user {
        return Err(forbidden("Access denied: User ID mismatch"));
    }
    let name = body.name.clone().unwrap_or_default().trim().to_string();
    if name.is_empty() {
        return Err(bad("Name is required"));
    }
    if name.chars().count() > 100 {
        return Err(bad("Name cannot exceed 100 characters"));
    }
    if body.description.as_ref().is_some_and(|d| d.chars().count() > 300) {
        return Err(bad("Description cannot exceed 300 characters"));
    }
    let currency = body
        .currency
        .clone()
        .filter(|c| !c.is_empty())
        .unwrap_or_else(|| "INR".into());

    let mut tx = a.db.begin().await?;
    let member_name = display_name(&mut tx, user).await?;
    let group = sqlx::query_as::<_, GroupRow>(&format!(
        "INSERT INTO groups.expense_groups (name,description,created_by,currency,created_at,is_deleted) \
         VALUES ($1,$2,$3,$4,now(),false) RETURNING {GROUP_COLS}"
    ))
    .bind(&name)
    .bind(&body.description)
    .bind(user)
    .bind(&currency)
    .fetch_one(&mut *tx)
    .await?;
    sqlx::query(
        "INSERT INTO groups.group_members (group_id,user_id,name,joined_at,status,is_archived,role) \
         VALUES ($1,$2,$3,now(),'ACCEPTED',false,'ADMIN')",
    )
    .bind(group.id)
    .bind(user)
    .bind(&member_name)
    .execute(&mut *tx)
    .await?;
    log_activity(
        &mut tx,
        group.id,
        user,
        &member_name,
        "GROUP_CREATED",
        &format!("@{member_name} created the group \"{name}\""),
        group.id,
    )
    .await?;
    tx.commit().await?;
    cache::evict_group(&a, group.id, &[user]).await;
    tracing::info!(id = group.id, name = %group.name, "group created");
    Ok((StatusCode::CREATED, Json(group_json(&group))).into_response())
}

async fn update_group(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    JsonBody(body): JsonBody<UpdateGroup>,
) -> Result<Response> {
    let user = actor(&h)?;
    let name = body.name.clone().unwrap_or_default().trim().to_string();
    if name.is_empty() {
        return Err(bad("Name is required"));
    }
    if name.chars().count() > 100 {
        return Err(bad("Name cannot exceed 100 characters"));
    }
    if body.description.as_ref().is_some_and(|d| d.chars().count() > 300) {
        return Err(bad("Description cannot exceed 300 characters"));
    }
    if body.currency.as_ref().is_some_and(|c| c.len() != 3) {
        return Err(bad("Currency must be 3 characters"));
    }
    let mut tx = a.db.begin().await?;
    let _ = group(&mut tx, group_id).await?;
    let admin = require_admin(&mut tx, group_id, user).await?;
    let updated = sqlx::query_as::<_, GroupRow>(&format!(
        "UPDATE groups.expense_groups SET name=$2, description=$3, \
         currency=COALESCE($4::text,currency) WHERE id=$1 RETURNING {GROUP_COLS}"
    ))
    .bind(group_id)
    .bind(&name)
    .bind(&body.description)
    .bind(&body.currency)
    .fetch_one(&mut *tx)
    .await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &admin.name,
        "GROUP_CREATED",
        &format!("@{} updated group details", admin.name),
        group_id,
    )
    .await?;
    let affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|m| m.user_id)
        .collect();
    tx.commit().await?;
    cache::evict_group(&a, group_id, &affected).await;
    Ok(Json(group_json(&updated)).into_response())
}

async fn delete_group(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let _ = group(&mut tx, group_id).await?;
    let _ = require_admin(&mut tx, group_id, user).await?;
    let bals = balances(&mut tx, group_id).await?;
    if bals.iter().any(|b| b.net != Decimal::ZERO) {
        return Err(bad("Cannot delete group: all debts must be settled first."));
    }
    sqlx::query("UPDATE groups.expense_groups SET is_deleted=true WHERE id=$1")
        .bind(group_id)
        .execute(&mut *tx)
        .await?;
    let affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|m| m.user_id)
        .collect();
    tx.commit().await?;
    cache::evict_group(&a, group_id, &affected).await;
    Ok(StatusCode::NO_CONTENT.into_response())
}

async fn archive_group(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = actor(&h)?;
    let archive = q_bool(&q, "archive", false)?;
    let mut tx = a.db.begin().await?;
    if member(&mut tx, group_id, user).await?.is_none() {
        return Err(not_found("Membership not found"));
    }
    sqlx::query("UPDATE groups.group_members SET is_archived=$3 WHERE group_id=$1 AND user_id=$2")
        .bind(group_id)
        .bind(user)
        .bind(archive)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    cache::evict_group(&a, group_id, &[user]).await;
    Ok(StatusCode::NO_CONTENT.into_response())
}

async fn add_member(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    JsonBody(body): JsonBody<AddMember>,
) -> Result<Response> {
    let user = actor(&h)?;
    let target = body.user_id.ok_or_else(|| bad("userId is required"))?;
    let mut tx = a.db.begin().await?;
    let g = group(&mut tx, group_id).await?;
    let admin = require_admin(&mut tx, group_id, user).await?;
    let fallback = display_name(&mut tx, target).await?;
    if member(&mut tx, group_id, target).await?.is_some() {
        return Err(bad("User is already a member of this group"));
    }
    let name = body
        .name
        .clone()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or(fallback);
    let row = sqlx::query_as::<_, MemberRow>(&format!(
        "INSERT INTO groups.group_members (group_id,user_id,name,joined_at,status,is_archived,role) \
         VALUES ($1,$2,$3,now(),'PENDING',false,'MEMBER') RETURNING {MEMBER_COLS}"
    ))
    .bind(group_id)
    .bind(target)
    .bind(&name)
    .fetch_one(&mut *tx)
    .await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &admin.name,
        "MEMBER_ADDED",
        &format!("@{} added @{} to the group", admin.name, name),
        row.id,
    )
    .await?;
    let affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|m| m.user_id)
        .collect();
    tx.commit().await?;
    cache::evict_group(&a, group_id, &affected).await;
    let message = format!("You've been invited to join the group: {}", g.name);
    send_notices(
        &a,
        vec![Notice::new(target, group_id, "group-invite", message, "INFO")],
    )
    .await;
    Ok((StatusCode::CREATED, Json(member_json(&row))).into_response())
}

async fn accept_invitation(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, member_user_id)): Path<(i64, Uuid)>,
) -> Result<Response> {
    let user = actor(&h)?;
    if user != member_user_id {
        return Err(forbidden("Cannot accept invitation for another user"));
    }
    let mut tx = a.db.begin().await?;
    let mut m = member(&mut tx, group_id, user)
        .await?
        .ok_or_else(|| not_found("Membership not found"))?;
    if m.status == "ACCEPTED" {
        return Ok(Json(json!({"message":"Invitation accepted"})).into_response());
    }
    sqlx::query("UPDATE groups.group_members SET status='ACCEPTED' WHERE id=$1")
        .bind(m.id)
        .execute(&mut *tx)
        .await?;
    m.status = "ACCEPTED".into();
    log_activity(
        &mut tx,
        group_id,
        user,
        &m.name,
        "MEMBER_ADDED",
        &format!("@{} joined the group", m.name),
        m.id,
    )
    .await?;
    let affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|x| x.user_id)
        .collect();
    tx.commit().await?;
    cache::evict_group(&a, group_id, &affected).await;
    Ok(Json(json!({"message":"Invitation accepted"})).into_response())
}

async fn reject_invitation(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, member_user_id)): Path<(i64, Uuid)>,
) -> Result<Response> {
    let user = actor(&h)?;
    if user != member_user_id {
        return Err(forbidden("Cannot reject invitation for another user"));
    }
    let mut tx = a.db.begin().await?;
    let m = member(&mut tx, group_id, user)
        .await?
        .ok_or_else(|| not_found("Membership not found"))?;
    if m.status == "ACCEPTED" {
        return Err(bad(
            "Cannot reject an already accepted invitation. Use leave group instead.",
        ));
    }
    sqlx::query("DELETE FROM groups.group_members WHERE id=$1")
        .bind(m.id)
        .execute(&mut *tx)
        .await?;
    let affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|x| x.user_id)
        .collect();
    tx.commit().await?;
    cache::evict_group(&a, group_id, &affected).await;
    Ok(Json(json!({"message":"Invitation rejected"})).into_response())
}

async fn remove_or_leave(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, member_user_id)): Path<(i64, Uuid)>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;

    if user == member_user_id {
        let m = member(&mut tx, group_id, user)
            .await?
            .ok_or_else(|| not_found("Membership not found"))?;
        let all = members(&mut tx, group_id).await?;
        let others_remain = all
            .iter()
            .any(|x| x.user_id != user && x.status == "ACCEPTED");
        if m.role.as_deref() == Some("ADMIN")
            && others_remain
            && count_other_admins(&mut tx, group_id, user).await? == 0
        {
            return Err(bad(
                "You are the group's only admin. Promote another admin before leaving.",
            ));
        }
        let bals = balances(&mut tx, group_id).await?;
        if bals
            .iter()
            .any(|b| b.user_id == user && b.net != Decimal::ZERO)
        {
            return Err(bad(
                "Cannot leave the group with a non-zero balance. Please settle your debts first.",
            ));
        }
        sqlx::query("DELETE FROM groups.group_members WHERE id=$1")
            .bind(m.id)
            .execute(&mut *tx)
            .await?;
        log_activity(
            &mut tx,
            group_id,
            user,
            &m.name,
            "MEMBER_ADDED",
            &format!("@{} left the group", m.name),
            m.id,
        )
        .await?;
        let affected: Vec<Uuid> = members(&mut tx, group_id)
            .await?
            .iter()
            .map(|x| x.user_id)
            .collect();
        tx.commit().await?;
        cache::evict_group(&a, group_id, &affected).await;
        return Ok(Json(json!({"message":"Successfully left the group"})).into_response());
    }

    let settle_first = q_bool(&q, "settle", true)?;
    let admin = require_admin(&mut tx, group_id, user).await?;
    let m = member(&mut tx, group_id, member_user_id)
        .await?
        .ok_or_else(|| not_found("User is not a member of this group"))?;

    if m.status != "ACCEPTED" {
        let name = group_name(&mut tx, group_id).await?;
        sqlx::query("DELETE FROM groups.group_members WHERE id=$1")
            .bind(m.id)
            .execute(&mut *tx)
            .await?;
        log_activity(
            &mut tx,
            group_id,
            user,
            &admin.name,
            "MEMBER_REMOVED",
            &format!(
                "@{} cancelled @{}'s invitation to the group",
                admin.name, m.name
            ),
            group_id,
        )
        .await?;
        let affected: Vec<Uuid> = members(&mut tx, group_id)
            .await?
            .iter()
            .map(|x| x.user_id)
            .collect();
        tx.commit().await?;
        cache::evict_group(&a, group_id, &affected).await;
        send_notices(
            &a,
            vec![Notice::new(
                m.user_id,
                group_id,
                "group-invite-cancelled",
                format!("Your invitation to join {name} was cancelled"),
                "INFO",
            )],
        )
        .await;
        return Ok(Json(json!({"message":"Member removed from the group"})).into_response());
    }

    if m.role.as_deref() == Some("ADMIN")
        && count_other_admins(&mut tx, group_id, member_user_id).await? == 0
    {
        return Err(bad(
            "Cannot remove the group's only admin. Promote another admin first.",
        ));
    }

    let mut notices = Vec::new();
    let mut settle_users = Vec::new();
    let mut settle_affected = Vec::new();
    if settle_first {
        for _ in 0..10 {
            let bals = balances(&mut tx, group_id).await?;
            let currency = currency_of(&mut tx, group_id).await?;
            let involving: Vec<Suggestion> = minimize_debts(&bals, &currency)
                .into_iter()
                .filter(|s| s.from == member_user_id || s.to == member_user_id)
                .collect();
            if involving.is_empty() {
                break;
            }
            for s in involving {
                if let Some(outcome) =
                    settle_debt_core(&mut tx, group_id, s.from, s.to, user).await?
                {
                    notices.extend(outcome.notices);
                    settle_users.extend(outcome.evict_users);
                    settle_affected.extend(outcome.affected);
                }
            }
        }
    }

    let bals = balances(&mut tx, group_id).await?;
    let residual = bals
        .iter()
        .find(|b| b.user_id == member_user_id)
        .map(|b| b.net)
        .unwrap_or(Decimal::ZERO);
    if residual != Decimal::ZERO {
        return Err(bad(&format!(
            "Cannot remove @{}: their balance in this group is {}. Record a settlement first.",
            m.name,
            residual.normalize()
        )));
    }
    let name = group_name(&mut tx, group_id).await?;
    sqlx::query("DELETE FROM groups.group_members WHERE id=$1")
        .bind(m.id)
        .execute(&mut *tx)
        .await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &admin.name,
        "MEMBER_REMOVED",
        &format!("@{} removed @{} from the group", admin.name, m.name),
        group_id,
    )
    .await?;
    let mut affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|x| x.user_id)
        .collect();
    affected.extend(settle_affected);
    tx.commit().await?;
    cache::evict_users(&a, &settle_users).await;
    cache::evict_group(&a, group_id, &affected).await;
    notices.push(Notice::new(
        member_user_id,
        group_id,
        "group-removed",
        format!("You were removed from the group: {name}"),
        "INFO",
    ));
    send_notices(&a, notices).await;
    Ok(Json(json!({"message":"Member removed from the group"})).into_response())
}

async fn set_admin(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, member_user_id)): Path<(i64, Uuid)>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = actor(&h)?;
    let promote = q_bool(&q, "admin", false)?;
    let mut tx = a.db.begin().await?;
    let actor_row = require_admin(&mut tx, group_id, user).await?;
    let m = member(&mut tx, group_id, member_user_id)
        .await?
        .filter(|m| m.status == "ACCEPTED")
        .ok_or_else(|| bad("User is not an active member of this group"))?;
    if !promote
        && m.role.as_deref() == Some("ADMIN")
        && count_other_admins(&mut tx, group_id, member_user_id).await? == 0
    {
        return Err(bad(
            "The group needs at least one admin. Promote someone else first.",
        ));
    }
    let label = if promote {
        "Member promoted to admin"
    } else {
        "Admin role removed"
    };
    if (m.role.as_deref() == Some("ADMIN")) == promote {
        return Ok(Json(json!({"message":label})).into_response());
    }
    sqlx::query("UPDATE groups.group_members SET role=$2 WHERE id=$1")
        .bind(m.id)
        .bind(if promote { "ADMIN" } else { "MEMBER" })
        .execute(&mut *tx)
        .await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &actor_row.name,
        "ADMIN_CHANGED",
        &if promote {
            format!("@{} made @{} an admin", actor_row.name, m.name)
        } else {
            format!("@{} removed admin from @{}", actor_row.name, m.name)
        },
        m.id,
    )
    .await?;
    let gname = group_name(&mut tx, group_id).await?;
    let affected: Vec<Uuid> = members(&mut tx, group_id)
        .await?
        .iter()
        .map(|x| x.user_id)
        .collect();
    tx.commit().await?;
    cache::evict_group(&a, group_id, &affected).await;
    let message = if promote {
        format!("You are now an admin of {gname}")
    } else {
        format!("You are no longer an admin of {gname}")
    };
    send_notices(
        &a,
        vec![Notice::new(
            member_user_id,
            group_id,
            "group-admin-changed",
            message,
            "INFO",
        )],
    )
    .await;
    Ok(Json(json!({"message":label})).into_response())
}

async fn group_name(tx: &mut Transaction<'_, Postgres>, group: i64) -> Result<String> {
    Ok(
        sqlx::query_scalar("SELECT name FROM groups.expense_groups WHERE id=$1")
            .bind(group)
            .fetch_optional(&mut **tx)
            .await?
            .unwrap_or_else(|| "the group".into()),
    )
}

async fn currency_of(tx: &mut Transaction<'_, Postgres>, group: i64) -> Result<String> {
    Ok(
        sqlx::query_scalar("SELECT currency FROM groups.expense_groups WHERE id=$1")
            .bind(group)
            .fetch_optional(&mut **tx)
            .await?
            .unwrap_or_else(|| "INR".into()),
    )
}

#[derive(Clone)]
struct Balance {
    user_id: Uuid,
    user_name: String,
    net: Decimal,
}

#[derive(Clone)]
struct Suggestion {
    from: Uuid,
    to: Uuid,
    amount: Decimal,
}

async fn balances(tx: &mut Transaction<'_, Postgres>, group: i64) -> Result<Vec<Balance>> {
    let members = members(tx, group).await?;
    let expenses = sqlx::query_as::<_, ExpenseRow>(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE group_id=$1 ORDER BY created_at DESC"
    ))
    .bind(group)
    .fetch_all(&mut **tx)
    .await?;
    let ids: Vec<i64> = expenses.iter().map(|e| e.id).collect();
    let splits = if ids.is_empty() {
        Vec::new()
    } else {
        sqlx::query_as::<_, SplitRow>(&format!(
            "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id = ANY($1)"
        ))
        .bind(&ids)
        .fetch_all(&mut **tx)
        .await?
    };
    let mut paid: HashMap<Uuid, Decimal> = HashMap::new();
    for e in &expenses {
        *paid.entry(e.paid_by).or_default() += e.amount;
    }
    let mut owed: HashMap<Uuid, Decimal> = HashMap::new();
    for s in &splits {
        *owed.entry(s.user_id).or_default() += s.amount;
    }
    Ok(members
        .into_iter()
        .map(|m| {
            let p = paid.get(&m.user_id).copied().unwrap_or(Decimal::ZERO);
            let o = owed.get(&m.user_id).copied().unwrap_or(Decimal::ZERO);
            Balance {
                user_id: m.user_id,
                user_name: m.name,
                net: (p - o).round_dp(2),
            }
        })
        .collect())
}

fn minimize_debts(balances: &[Balance], currency: &str) -> Vec<Suggestion> {
    let mut debt: Vec<(Uuid, Decimal)> = balances
        .iter()
        .filter(|b| b.net < Decimal::ZERO)
        .map(|b| (b.user_id, -b.net))
        .collect();
    let mut credit: Vec<(Uuid, Decimal)> = balances
        .iter()
        .filter(|b| b.net > Decimal::ZERO)
        .map(|b| (b.user_id, b.net))
        .collect();
    debt.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    credit.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let mut out = Vec::new();
    let (mut di, mut ci) = (0, 0);
    while di < debt.len() && ci < credit.len() {
        let amount = debt[di].1.min(credit[ci].1).round_dp(2);
        if amount <= Decimal::ZERO {
            if debt[di].1 <= Decimal::ZERO {
                di += 1;
            }
            if credit[ci].1 <= Decimal::ZERO {
                ci += 1;
            }
            continue;
        }
        out.push(Suggestion {
            from: debt[di].0,
            to: credit[ci].0,
            amount,
        });
        debt[di].1 -= amount;
        credit[ci].1 -= amount;
        if debt[di].1 <= Decimal::ZERO {
            di += 1;
        }
        if credit[ci].1 <= Decimal::ZERO {
            ci += 1;
        }
    }
    let _ = currency;
    out
}

fn parse_amount(v: &Value) -> Result<Decimal> {
    Decimal::from_str(v.to_string().trim_matches('"')).map_err(|_| bad("Invalid amount"))
}

fn receipt_owned(url: Option<&str>, user: Uuid) -> Result<()> {
    if let Some(url) = url
        && !url.is_empty()
    {
        let prefix = format!("/api/storage/receipts/{user}");
        if !url.starts_with(&format!("{prefix}/")) && !url.starts_with(&format!("{prefix}-batch-")) {
            return Err(forbidden("Receipt was not uploaded by this user"));
        }
    }
    Ok(())
}

fn split_equally(amount: Decimal, count: usize) -> Vec<Decimal> {
    if count == 0 {
        return Vec::new();
    }
    let total = (amount.round_dp(2) * Decimal::from(100))
        .to_i64()
        .unwrap_or(0);
    let base = total / count as i64;
    let rem = total % count as i64;
    (0..count)
        .map(|i| Decimal::new(base + if (i as i64) < rem { 1 } else { 0 }, 2))
        .collect()
}

fn split_by_percentage(amount: Decimal, percentages: &[Decimal]) -> Vec<Decimal> {
    let count = percentages.len();
    if count == 0 {
        return Vec::new();
    }
    let rounded = amount.round_dp(2);
    let total = (rounded * Decimal::from(100)).to_i64().unwrap_or(0);
    let mut cents = vec![0i64; count];
    let mut fracs = vec![Decimal::ZERO; count];
    let mut allocated = 0i64;
    for (i, pct) in percentages.iter().enumerate() {
        let raw = rounded * *pct;
        let floored = raw.trunc();
        cents[i] = floored.to_i64().unwrap_or(0);
        fracs[i] = raw - floored;
        allocated += cents[i];
    }
    let mut order: Vec<usize> = (0..count).collect();
    order.sort_by(|a, b| fracs[*b].cmp(&fracs[*a]).then(a.cmp(b)));
    let leftover = (total - allocated).max(0) as usize;
    for i in 0..leftover.min(count) {
        cents[order[i]] += 1;
    }
    cents.iter().map(|c| Decimal::new(*c, 2)).collect()
}

async fn insert_splits(
    tx: &mut Transaction<'_, Postgres>,
    expense: &ExpenseRow,
    members: &[MemberRow],
    request: &ExpenseRequest,
) -> Result<Vec<SplitRow>> {
    let accepted: Vec<&MemberRow> = members.iter().filter(|m| m.status == "ACCEPTED").collect();
    if accepted.is_empty() {
        return Ok(Vec::new());
    }
    let details = request.split_details.clone().unwrap_or_default();
    let member_ids: HashSet<Uuid> = accepted.iter().map(|m| m.user_id).collect();
    let detail_value = |user: Uuid| -> Option<Decimal> {
        details
            .iter()
            .find(|d| d.user_id == Some(user))
            .and_then(|d| d.value.as_ref())
            .and_then(|v| parse_amount(v).ok())
    };
    let amounts: Vec<Decimal> = match expense.split_type.as_str() {
        "PERCENTAGE" if !details.is_empty() => {
            let total: Decimal = details
                .iter()
                .filter(|d| d.user_id.is_some_and(|u| member_ids.contains(&u)))
                .filter_map(|d| d.value.as_ref())
                .filter_map(|v| parse_amount(v).ok())
                .sum();
            if (total - Decimal::from(100)).abs() >= Decimal::new(1, 2) {
                return Err(bad(&format!(
                    "Percentage splits must add up to 100 (got {total})"
                )));
            }
            let pcts: Vec<Decimal> = accepted
                .iter()
                .map(|m| detail_value(m.user_id).unwrap_or(Decimal::ZERO))
                .collect();
            split_by_percentage(expense.amount, &pcts)
        }
        "EXACT" | "SETTLEMENT" if !details.is_empty() => {
            let total: Decimal = details
                .iter()
                .filter(|d| d.user_id.is_some_and(|u| member_ids.contains(&u)))
                .filter_map(|d| d.value.as_ref())
                .filter_map(|v| parse_amount(v).ok())
                .sum();
            if total.round_dp(2) != expense.amount.round_dp(2) {
                return Err(bad(&format!(
                    "Exact splits must add up to the expense amount {} (got {total})",
                    expense.amount.normalize()
                )));
            }
            accepted
                .iter()
                .map(|m| detail_value(m.user_id).unwrap_or(Decimal::ZERO))
                .collect()
        }
        _ => split_equally(expense.amount, accepted.len()),
    };
    let mut rows = Vec::new();
    for (i, m) in accepted.iter().enumerate() {
        let row = sqlx::query_as::<_, SplitRow>(&format!(
            "INSERT INTO groups.expense_splits \
             (shared_expense_id,user_id,user_name,amount,is_settled) \
             VALUES ($1,$2,$3,$4,$5) RETURNING {SPLIT_COLS}"
        ))
        .bind(expense.id)
        .bind(m.user_id)
        .bind(&m.name)
        .bind(amounts.get(i).copied().unwrap_or(Decimal::ZERO))
        .bind(m.user_id == expense.paid_by)
        .fetch_one(&mut **tx)
        .await?;
        rows.push(row);
    }
    Ok(rows)
}

#[allow(clippy::too_many_arguments)]
async fn insert_entry(
    tx: &mut Transaction<'_, Postgres>,
    user: Uuid,
    name: &str,
    amount: Decimal,
    r#type: &str,
    category: &str,
    currency: &str,
    description: &str,
) -> Result<i64> {
    Ok(sqlx::query_scalar::<_, i64>(
        "INSERT INTO finance.transaction_entries \
         (user_id,name,amount,type,category,currency,description,created_at,updated_at,\
          recurring,transaction_kind) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,now(),now(),false,'STANDARD') RETURNING id",
    )
    .bind(user)
    .bind(name)
    .bind(amount)
    .bind(r#type)
    .bind(category)
    .bind(currency)
    .bind(description)
    .fetch_one(&mut **tx)
    .await?)
}

async fn create_personal_transactions(
    tx: &mut Transaction<'_, Postgres>,
    expense: &ExpenseRow,
    splits: &[SplitRow],
    group_name: &str,
    payer_name: &str,
) -> Result<()> {
    let category = if expense.split_type == "SETTLEMENT" {
        "SETTLEMENT".to_string()
    } else {
        expense
            .expense_category
            .clone()
            .unwrap_or_else(|| "OTHERS".into())
    };
    for split in splits.iter().filter(|s| s.amount > Decimal::ZERO) {
        let mut entries: Vec<(Uuid, String, Decimal, &str)> = Vec::new();
        if expense.split_type == "SETTLEMENT" {
            entries.push((
                expense.paid_by,
                format!("Paid {}", split.user_name.clone().unwrap_or_default()),
                split.amount,
                "EXPENSE",
            ));
            entries.push((
                split.user_id,
                format!("Received from {payer_name}"),
                split.amount,
                "INCOME",
            ));
        } else {
            entries.push((
                split.user_id,
                expense.description.clone(),
                split.amount,
                "EXPENSE",
            ));
        }
        for (user, name, amount, r#type) in entries {
            let description = if expense.split_type == "SETTLEMENT" {
                format!("Settlement · {group_name}")
            } else {
                format!("Split expense · {group_name}")
            };
            let entry_id = insert_entry(
                tx,
                user,
                &name,
                amount,
                r#type,
                &category,
                &expense.currency,
                &description,
            )
            .await?;
            sqlx::query(
                "INSERT INTO groups.expense_transaction_links \
                 (shared_expense_id,user_id,transaction_entry_id) VALUES ($1,$2,$3)",
            )
            .bind(expense.id)
            .bind(user)
            .bind(entry_id)
            .execute(&mut **tx)
            .await?;
        }
    }
    Ok(())
}

async fn delete_personal_transactions(
    tx: &mut Transaction<'_, Postgres>,
    expense_id: i64,
) -> Result<Vec<Uuid>> {
    let links: Vec<(i64, Uuid)> = sqlx::query_as(
        "SELECT transaction_entry_id, user_id FROM groups.expense_transaction_links \
         WHERE shared_expense_id=$1",
    )
    .bind(expense_id)
    .fetch_all(&mut **tx)
    .await?;
    let mut users = Vec::new();
    for (entry_id, user) in links {
        sqlx::query(
            "UPDATE finance.transaction_entries SET deleted_at=now(), updated_at=now() \
             WHERE id=$1 AND deleted_at IS NULL",
        )
        .bind(entry_id)
        .execute(&mut **tx)
        .await?;
        sqlx::query(
            "INSERT INTO finance.outbox_events (user_id,event_type,entity_id,status,created_at) \
             VALUES ($1,'DELETE',$2,'PENDING',now())",
        )
        .bind(user)
        .bind(entry_id)
        .execute(&mut **tx)
        .await?;
        users.push(user);
    }
    sqlx::query("DELETE FROM groups.expense_transaction_links WHERE shared_expense_id=$1")
        .bind(expense_id)
        .execute(&mut **tx)
        .await?;
    Ok(users)
}

#[allow(clippy::too_many_arguments)]
async fn insert_expense_row(
    tx: &mut Transaction<'_, Postgres>,
    group: i64,
    description: &str,
    amount: Decimal,
    currency: &str,
    paid_by: Uuid,
    split_type: &str,
    category: Option<&str>,
    date: Option<NaiveDateTime>,
    receipt: Option<&str>,
) -> Result<ExpenseRow> {
    Ok(sqlx::query_as::<_, ExpenseRow>(&format!(
        "INSERT INTO groups.shared_expenses \
         (group_id,description,amount,currency,paid_by,split_type,expense_category,\
          expense_date,created_at,receipt_url) \
         VALUES ($1,$2,$3,$4,$5,$6,$7,COALESCE($8::timestamp,now()),now(),$9) \
         RETURNING {EXPENSE_COLS}"
    ))
    .bind(group)
    .bind(description)
    .bind(amount)
    .bind(currency)
    .bind(paid_by)
    .bind(split_type)
    .bind(category)
    .bind(date)
    .bind(receipt)
    .fetch_one(&mut **tx)
    .await?)
}

struct ExpenseOutcome {
    json: Value,
    evict_users: Vec<Uuid>,
    affected: Vec<Uuid>,
    notices: Vec<Notice>,
}

async fn add_expense_core(
    tx: &mut Transaction<'_, Postgres>,
    group_id: i64,
    body: &ExpenseRequest,
    user: Uuid,
) -> Result<ExpenseOutcome> {
    let g = group(tx, group_id).await?;
    let all = members(tx, group_id).await?;
    let actor_row = all
        .iter()
        .find(|m| m.user_id == user)
        .cloned()
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    let paid_by = body.paid_by.ok_or_else(|| bad("paidBy is required"))?;
    if !all.iter().any(|m| m.user_id == paid_by) {
        return Err(forbidden("Payer is not a member of this group"));
    }
    let description = body.description.clone().unwrap_or_default().trim().to_string();
    if description.is_empty() {
        return Err(bad("Description is required"));
    }
    if description.chars().count() > 100 {
        return Err(bad("Description cannot exceed 100 characters"));
    }
    let amount = parse_amount(body.amount.as_ref().ok_or_else(|| bad("Amount is required"))?)?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    let split_type = body
        .split_type
        .clone()
        .unwrap_or_else(|| "EQUAL".into())
        .to_uppercase();
    if !["EQUAL", "PERCENTAGE", "EXACT", "SETTLEMENT"].contains(&split_type.as_str()) {
        return Err(bad("Invalid split type"));
    }
    let currency = body.currency.clone().unwrap_or_else(|| "INR".into());
    receipt_owned(body.receipt_url.as_deref(), user)?;
    let category = body
        .expense_category
        .clone()
        .map(|c| c.to_uppercase())
        .filter(|c| crate::entries::valid_category(c));

    let expense = insert_expense_row(
        tx,
        group_id,
        &description,
        amount,
        &currency,
        paid_by,
        &split_type,
        category.as_deref(),
        body.expense_date.as_deref().map(crate::datetime).transpose()?,
        body.receipt_url.as_deref(),
    )
    .await?;
    let splits = insert_splits(tx, &expense, &all, body).await?;
    let payer_name = all
        .iter()
        .find(|m| m.user_id == paid_by)
        .map(|m| m.name.clone())
        .unwrap_or_default();
    create_personal_transactions(tx, &expense, &splits, &g.name, &payer_name).await?;

    let (kind, message) = if split_type == "SETTLEMENT" {
        (
            "SETTLEMENT_RECORDED",
            format!("@{} recorded payment: {}", actor_row.name, description),
        )
    } else {
        (
            "EXPENSE_ADDED",
            format!(
                "@{} added expense \"{}\" ({} {})",
                actor_row.name,
                description,
                amount.normalize(),
                currency
            ),
        )
    };
    log_activity(tx, group_id, user, &actor_row.name, kind, &message, expense.id).await?;

    let affected: Vec<Uuid> = all.iter().map(|m| m.user_id).collect();
    let mut evict_users: Vec<Uuid> = splits
        .iter()
        .filter(|s| s.amount > Decimal::ZERO)
        .map(|s| s.user_id)
        .collect();
    evict_users.push(paid_by);
    let recipient_message = format!("{} added a new expense: {description}", actor_row.name);
    let notices = splits
        .iter()
        .filter(|s| s.user_id != user && s.amount > Decimal::ZERO)
        .map(|s| s.user_id)
        .collect::<HashSet<_>>()
        .into_iter()
        .map(|u| Notice::new(u, group_id, "expense-added", recipient_message.clone(), "INFO"))
        .collect();
    Ok(ExpenseOutcome {
        json: expense_json(&expense),
        evict_users,
        affected,
        notices,
    })
}

async fn apply_outcome(a: &App, group_id: i64, outcome: ExpenseOutcome) {
    cache::evict_users(a, &outcome.evict_users).await;
    cache::evict_group(a, group_id, &outcome.affected).await;
    send_notices(a, outcome.notices).await;
}

async fn add_expense(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    JsonBody(body): JsonBody<ExpenseRequest>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let outcome = add_expense_core(&mut tx, group_id, &body, user).await?;
    let json = outcome.json.clone();
    tx.commit().await?;
    apply_outcome(&a, group_id, outcome).await;
    Ok((StatusCode::CREATED, Json(json)).into_response())
}

async fn update_expense(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, expense_id)): Path<(i64, i64)>,
    JsonBody(body): JsonBody<ExpenseRequest>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let g = group(&mut tx, group_id).await?;
    let all = members(&mut tx, group_id).await?;
    let actor_row = all
        .iter()
        .find(|m| m.user_id == user)
        .cloned()
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    let old = sqlx::query_as::<_, ExpenseRow>(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE id=$1"
    ))
    .bind(expense_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Expense {expense_id} not found")))?;
    if old.group_id != group_id {
        return Err(bad("Expense does not belong to this group"));
    }
    if old.split_type == "SETTLEMENT" {
        return Err(bad("Settlements cannot be edited"));
    }
    let paid_by = body.paid_by.ok_or_else(|| bad("paidBy is required"))?;
    if !all.iter().any(|m| m.user_id == paid_by) {
        return Err(forbidden("Payer is not a member of this group"));
    }
    let old_splits = sqlx::query_as::<_, SplitRow>(&format!(
        "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id=$1"
    ))
    .bind(expense_id)
    .fetch_all(&mut *tx)
    .await?;
    if old_splits
        .iter()
        .any(|s| s.is_settled && s.user_id != old.paid_by)
    {
        return Err(bad("Cannot edit an expense with recorded settlements"));
    }
    let mut evict_users = delete_personal_transactions(&mut tx, expense_id).await?;
    sqlx::query("DELETE FROM groups.expense_splits WHERE shared_expense_id=$1")
        .bind(expense_id)
        .execute(&mut *tx)
        .await?;

    let description = body.description.clone().unwrap_or_default().trim().to_string();
    if description.is_empty() {
        return Err(bad("Description is required"));
    }
    if description.chars().count() > 100 {
        return Err(bad("Description cannot exceed 100 characters"));
    }
    let amount = parse_amount(body.amount.as_ref().ok_or_else(|| bad("Amount is required"))?)?;
    if amount <= Decimal::ZERO {
        return Err(bad("Amount must be greater than zero"));
    }
    let split_type = body
        .split_type
        .clone()
        .unwrap_or_else(|| "EQUAL".into())
        .to_uppercase();
    if !["EQUAL", "PERCENTAGE", "EXACT"].contains(&split_type.as_str()) {
        return Err(bad("Invalid split type"));
    }
    let currency = body.currency.clone().unwrap_or_else(|| "INR".into());
    receipt_owned(body.receipt_url.as_deref(), user)?;
    let category = body
        .expense_category
        .clone()
        .map(|c| c.to_uppercase())
        .filter(|c| crate::entries::valid_category(c))
        .or(old.expense_category.clone());

    let expense = sqlx::query_as::<_, ExpenseRow>(&format!(
        "UPDATE groups.shared_expenses SET description=$2,amount=$3,currency=$4,paid_by=$5,\
         split_type=$6,expense_category=$7,expense_date=COALESCE($8::timestamp,expense_date),\
         receipt_url=$9 WHERE id=$1 RETURNING {EXPENSE_COLS}"
    ))
    .bind(expense_id)
    .bind(&description)
    .bind(amount)
    .bind(&currency)
    .bind(paid_by)
    .bind(&split_type)
    .bind(&category)
    .bind(body.expense_date.as_deref().map(crate::datetime).transpose()?)
    .bind(&body.receipt_url)
    .fetch_one(&mut *tx)
    .await?;
    let splits = insert_splits(&mut tx, &expense, &all, &body).await?;
    let payer_name = all
        .iter()
        .find(|m| m.user_id == paid_by)
        .map(|m| m.name.clone())
        .unwrap_or_default();
    create_personal_transactions(&mut tx, &expense, &splits, &g.name, &payer_name).await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &actor_row.name,
        "EXPENSE_UPDATED",
        &format!(
            "@{} updated expense \"{}\" ({} {})",
            actor_row.name,
            description,
            amount.normalize(),
            currency
        ),
        expense_id,
    )
    .await?;
    let affected: Vec<Uuid> = all.iter().map(|m| m.user_id).collect();
    let notices: Vec<Notice> = splits
        .iter()
        .filter(|s| s.user_id != user && s.amount > Decimal::ZERO)
        .map(|s| {
            Notice::new(
                s.user_id,
                group_id,
                "expense-updated",
                format!("{} updated an expense: {description}", actor_row.name),
                "INFO",
            )
        })
        .collect();
    tx.commit().await?;
    evict_users.extend(splits.iter().map(|s| s.user_id));
    evict_users.push(paid_by);
    cache::evict_users(&a, &evict_users).await;
    cache::evict_group(&a, group_id, &affected).await;
    send_notices(&a, notices).await;
    Ok(Json(expense_json(&expense)).into_response())
}

async fn delete_expense(
    State(a): State<App>,
    h: HeaderMap,
    Path((group_id, expense_id)): Path<(i64, i64)>,
) -> Result<Response> {
    let user = actor(&h)?;
    let mut tx = a.db.begin().await?;
    let _ = group(&mut tx, group_id).await?;
    let all = members(&mut tx, group_id).await?;
    let actor_row = all
        .iter()
        .find(|m| m.user_id == user)
        .cloned()
        .ok_or_else(|| forbidden("User is not a member of this group"))?;
    let expense = sqlx::query_as::<_, ExpenseRow>(&format!(
        "SELECT {EXPENSE_COLS} FROM groups.shared_expenses WHERE id=$1"
    ))
    .bind(expense_id)
    .fetch_optional(&mut *tx)
    .await?
    .ok_or_else(|| not_found(&format!("Expense {expense_id} not found")))?;
    if expense.group_id != group_id {
        return Err(bad("Expense does not belong to this group"));
    }
    let splits = sqlx::query_as::<_, SplitRow>(&format!(
        "SELECT {SPLIT_COLS} FROM groups.expense_splits WHERE shared_expense_id=$1"
    ))
    .bind(expense_id)
    .fetch_all(&mut *tx)
    .await?;
    if splits
        .iter()
        .any(|s| s.is_settled && s.user_id != expense.paid_by)
    {
        return Err(bad("Cannot delete an expense with recorded settlements"));
    }
    let mut evict_users = delete_personal_transactions(&mut tx, expense_id).await?;
    sqlx::query("DELETE FROM groups.expense_splits WHERE shared_expense_id=$1")
        .bind(expense_id)
        .execute(&mut *tx)
        .await?;
    sqlx::query("DELETE FROM groups.shared_expenses WHERE id=$1")
        .bind(expense_id)
        .execute(&mut *tx)
        .await?;
    log_activity(
        &mut tx,
        group_id,
        user,
        &actor_row.name,
        "EXPENSE_DELETED",
        &format!(
            "@{} deleted expense \"{}\" ({} {})",
            actor_row.name,
            expense.description,
            expense.amount.normalize(),
            expense.currency
        ),
        expense_id,
    )
    .await?;
    let affected: Vec<Uuid> = all.iter().map(|m| m.user_id).collect();
    tx.commit().await?;
    evict_users.extend(splits.iter().map(|s| s.user_id));
    evict_users.push(expense.paid_by);
    cache::evict_users(&a, &evict_users).await;
    cache::evict_group(&a, group_id, &affected).await;
    Ok(Json(json!({"message":"Expense deleted successfully"})).into_response())
}

async fn settle_debt_core(
    tx: &mut Transaction<'_, Postgres>,
    group_id: i64,
    from: Uuid,
    to: Uuid,
    actor: Uuid,
) -> Result<Option<ExpenseOutcome>> {
    let bals = balances(tx, group_id).await?;
    let currency = currency_of(tx, group_id).await?;
    let total: Decimal = minimize_debts(&bals, &currency)
        .into_iter()
        .filter(|s| s.from == from && s.to == to)
        .map(|s| s.amount)
        .sum();
    if total <= Decimal::ZERO {
        return Ok(None);
    }
    let from_name = bals
        .iter()
        .find(|b| b.user_id == from)
        .map(|b| b.user_name.clone())
        .unwrap_or_else(|| from.to_string());
    let to_name = bals
        .iter()
        .find(|b| b.user_id == to)
        .map(|b| b.user_name.clone())
        .unwrap_or_else(|| to.to_string());
    let body = ExpenseRequest {
        description: Some(format!("Payment from {from_name} to {to_name}")),
        amount: Some(json!(total)),
        currency: Some(currency),
        paid_by: Some(from),
        split_type: Some("SETTLEMENT".into()),
        expense_category: Some("SETTLEMENT".into()),
        expense_date: None,
        split_details: Some(vec![SplitDetail {
            user_id: Some(to),
            value: Some(json!(total)),
        }]),
        receipt_url: None,
    };
    let mut outcome = add_expense_core(tx, group_id, &body, actor).await?;
    let target = if from == actor { to } else { from };
    outcome.notices.push(Notice::new(
        target,
        group_id,
        "debt-settled",
        format!(
            "{from_name} recorded a ₹{} settlement with {to_name}",
            total.round_dp(2)
        ),
        "SUCCESS",
    ));
    Ok(Some(outcome))
}

async fn settle_debt(
    State(a): State<App>,
    h: HeaderMap,
    Path(group_id): Path<i64>,
    Query(q): Query<Params>,
) -> Result<Response> {
    let user = actor(&h)?;
    let from = q_uuid(&q, "fromUserId")?;
    let to = q_uuid(&q, "toUserId")?;
    let mut tx = a.db.begin().await?;
    let _ = require_member(&mut tx, group_id, user).await?;
    let outcome = settle_debt_core(&mut tx, group_id, from, to, user).await?;
    tx.commit().await?;
    if let Some(outcome) = outcome {
        apply_outcome(&a, group_id, outcome).await;
    }
    Ok(Json(json!({"message":"Settlement recorded successfully"})).into_response())
}
