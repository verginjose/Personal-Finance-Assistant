use super::*;
use crate::{bad, cache, forbidden, not_found};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use rust_decimal::Decimal;
use serde_json::json;
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
        vec![Notice::new(target, group_id, "group-invite", message, "INFO")],
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

pub(crate) async fn remove_or_leave(
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

pub(crate) async fn set_admin(
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


