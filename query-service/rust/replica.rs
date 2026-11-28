//! Read-your-writes guard: wait briefly for the replica to reach Command’s WAL watermark.

use crate::{app::App, error::Result};
use std::time::Duration;

pub(crate) async fn wait_replica(a: &App, scopes: &[String]) -> Result<()> {
    use redis::AsyncCommands;
    let keys: Vec<_> = scopes.iter().map(|s| format!("finance:wal:{s}")).collect();
    let wals: Vec<Option<String>> = match a.redis.clone().mget(keys).await {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!(error=%e,"replica watermark unavailable");
            return Ok(());
        }
    };
    let deadline = std::time::Instant::now() + Duration::from_secs(1);
    for wal in wals.into_iter().flatten() {
        loop {
            let ready = sqlx::query_scalar::<_, bool>(
                "SELECT coalesce(pg_last_wal_replay_lsn()>=$1::text::pg_lsn,true)",
            )
            .bind(&wal)
            .fetch_one(&a.db)
            .await?;
            if ready || std::time::Instant::now() >= deadline {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }
    Ok(())
}
