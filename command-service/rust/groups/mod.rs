use super::*;
use axum::{
    Router,
    routing as rt,
};

pub(crate) const GROUP_COLS: &str = "id,name,description,created_by,currency,created_at,is_deleted";
pub(crate) const MEMBER_COLS: &str =
    "id,group_id,user_id,name,joined_at,status::text AS status,is_archived,role::text AS role";
pub(crate) const EXPENSE_COLS: &str =
    "id,group_id,description,amount,currency,paid_by,split_type::text AS split_type,\
expense_category::text AS expense_category,expense_date,created_at,receipt_url";
pub(crate) const SPLIT_COLS: &str =
    "id,shared_expense_id,user_id,user_name,amount,is_settled,settled_at";


mod models;
mod helpers;
mod balance;
mod splits;
mod membership;
mod expenses;

pub(crate) use models::*;
pub(crate) use helpers::*;
pub(crate) use balance::*;
pub(crate) use splits::*;
pub(crate) use membership::*;
pub(crate) use expenses::*;

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

