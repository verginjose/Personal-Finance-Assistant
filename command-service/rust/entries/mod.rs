//! Transaction entry writes. Models, validation and database operations live in separate modules.

mod categories;

pub(crate) const ENTRY_COLS: &str = "id,user_id,name,amount,type::text AS type,category::text AS category,\
currency,description,created_at,updated_at,recurring,recurring_period::text AS recurring_period,\
next_run_date,deleted_at,receipt_url,transaction_kind::text AS transaction_kind,\
original_transaction_id,subcategory";

mod models;
mod operations;
mod validation;

pub(crate) use models::EntryRequest;
pub(crate) use operations::{create, create_bulk, delete, next_run, patch_amount, update};
pub(crate) use validation::{outbox, valid_category};
