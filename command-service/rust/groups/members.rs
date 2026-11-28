use super::balance::{Suggestion, balances, currency_of, group_name, minimize_debts};
use super::helpers::{
    Notice, actor, count_other_admins, log_activity, member, members, q_bool, require_admin,
    send_notices,
};
use super::settlements::settle_debt_core;
use crate::{App, Params, Result};
use crate::{bad, cache, not_found};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::HeaderMap,
    response::{IntoResponse, Response},
};
use rust_decimal::Decimal;
use serde_json::json;
use uuid::Uuid;

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
