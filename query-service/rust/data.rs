use crate::{Result, serialization::camel};
use serde_json::Value;
use sqlx::PgPool;
use uuid::Uuid;

pub(crate) async fn list(db: &PgPool, sql: &str, id: Uuid) -> Result<Vec<Value>> {
    Ok(sqlx::query_scalar::<_, Value>(sql)
        .bind(id)
        .fetch_all(db)
        .await?
        .into_iter()
        .map(camel)
        .collect())
}
