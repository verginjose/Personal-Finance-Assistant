use super::MEMBER_COLS;
use super::helpers::{
    Notice, actor, display_name, group, log_activity, member, member_json, members, require_admin,
    send_notices,
};
use super::models::{AddMember, MemberRow};
use crate::{App, JsonBody, Result};
use crate::{bad, cache, forbidden, not_found};
use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use serde_json::json;
use uuid::Uuid;

pub(crate) async fn add_member(
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
        vec![Notice::new(
            target,
            group_id,
            "group-invite",
            message,
            "INFO",
        )],
    )
    .await;
    Ok((StatusCode::CREATED, Json(member_json(&row))).into_response())
}

pub(crate) async fn accept_invitation(
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

pub(crate) async fn reject_invitation(
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
