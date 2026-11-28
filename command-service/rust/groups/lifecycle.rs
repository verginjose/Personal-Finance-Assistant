use super::GROUP_COLS;
use super::balance::balances;
use super::helpers::{
    actor, display_name, group, group_json, log_activity, member, members, q_bool, require_admin,
};
use super::models::{CreateGroup, GroupRow, UpdateGroup};
use crate::{App, JsonBody, Params, Result};
use crate::{bad, cache, forbidden, not_found};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use rust_decimal::Decimal;
use uuid::Uuid;

pub(crate) async fn create_group(
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
    if body
        .description
        .as_ref()
        .is_some_and(|d| d.chars().count() > 300)
    {
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

pub(crate) async fn update_group(
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
    if body
        .description
        .as_ref()
        .is_some_and(|d| d.chars().count() > 300)
    {
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

pub(crate) async fn delete_group(
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

pub(crate) async fn archive_group(
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
