use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    #[serde(rename = "userId")]
    pub user_id: String,
    pub role: String,
    #[serde(default)]
    pub sv: i64,
    pub exp: u64,
    pub iat: u64,
    pub jti: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub purpose: Option<String>,
}
#[derive(sqlx::FromRow)]
pub(crate) struct User {
    pub(crate) id: Uuid,
    pub(crate) email: String,
    pub(crate) username: String,
    pub(crate) password: String,
    pub(crate) role: String,
    pub(crate) enabled: bool,
    pub(crate) account_non_locked: bool,
    pub(crate) profile_picture: Option<String>,
    pub(crate) default_currency: String,
}
