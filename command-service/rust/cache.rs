use super::App;
use redis::AsyncCommands;
use std::collections::HashSet;
use uuid::Uuid;

const USER_SET_PREFIX: &str = "finance:analytics:v1:user-keys:";
const GROUP_SET_PREFIX: &str = "finance:query:v1:group-keys:";
const QUERY_PREFIX: &str = "finance:analytics:v1:";

/// Publish this write's generation counter and WAL insert position for every
/// affected scope. The query service checks these before caching a fill.
async fn publish_tokens(a: &App, users: &HashSet<Uuid>, groups: &HashSet<i64>) {
    let lsn: Option<String> = sqlx::query_scalar("select pg_current_wal_insert_lsn()::text")
        .fetch_optional(&a.db)
        .await
        .ok()
        .flatten();
    let mut redis = a.redis.clone();
    for user in users {
        let gen_key = format!("finance:gen:user:{user}");
        if let Err(e) = redis.incr::<_, _, i64>(&gen_key, 1).await {
            tracing::warn!(error = %e, %user, "failed to bump user generation");
        }
        if let Some(lsn) = &lsn {
            let key = format!("finance:wal:user:{user}");
            if let Err(e) = redis.set::<_, _, ()>(&key, lsn).await {
                tracing::warn!(error = %e, %user, "failed to publish user WAL");
            }
        }
    }
    for group in groups {
        let gen_key = format!("finance:gen:group:{group}");
        if let Err(e) = redis.incr::<_, _, i64>(&gen_key, 1).await {
            tracing::warn!(error = %e, group, "failed to bump group generation");
        }
        if let Some(lsn) = &lsn {
            let key = format!("finance:wal:group:{group}");
            if let Err(e) = redis.set::<_, _, ()>(&key, lsn).await {
                tracing::warn!(error = %e, group, "failed to publish group WAL");
            }
        }
    }
}

pub async fn evict_user_keys(a: &App, users: &HashSet<Uuid>) -> bool {
    let mut ok = true;
    let mut redis = a.redis.clone();
    for user in users {
        let set = format!("{USER_SET_PREFIX}{user}");
        let keys: Vec<String> = match redis.smembers(&set).await {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(error = %e, %user, "failed to read user cache registry");
                ok = false;
                continue;
            }
        };
        if !keys.is_empty()
            && let Err(e) = redis.del::<_, i64>(&keys).await
        {
            tracing::warn!(error = %e, %user, "failed to delete user cache keys");
            ok = false;
        }
        if redis.del::<_, i64>(&set).await.is_err() {
            ok = false;
        }
    }
    ok
}

/// Evict the users' cached reads and publish the write tokens. Runs after the
/// transaction commits. Best-effort: Redis failures never fail the write.
pub async fn evict_users(a: &App, users: &[Uuid]) {
    let unique: HashSet<Uuid> = users.iter().copied().collect();
    if unique.is_empty() {
        return;
    }
    publish_tokens(a, &unique, &HashSet::new()).await;
    evict_user_keys(a, &unique).await;
}

/// Evict every group-scoped cache and the members' user-groups catalog
/// entries, then publish tokens for the group and all members.
pub async fn evict_group(a: &App, group: i64, members: &[Uuid]) {
    let users: HashSet<Uuid> = members.iter().copied().collect();
    let groups: HashSet<i64> = HashSet::from([group]);
    publish_tokens(a, &users, &groups).await;

    let mut redis = a.redis.clone();
    let set = format!("{GROUP_SET_PREFIX}{group}");
    let mut keys: Vec<String> = match redis.smembers(&set).await {
        Ok(v) => v,
        Err(e) => {
            tracing::warn!(error = %e, group, "failed to read group cache registry");
            Vec::new()
        }
    };
    // The balances key is deterministic; always delete it even if the registry
    // Set missed the fill.
    keys.push(format!("{QUERY_PREFIX}group-balances:{group}"));
    if let Err(e) = redis.del::<_, i64>(&keys).await {
        tracing::warn!(error = %e, group, "failed to delete group cache keys");
    }
    let _ = redis.del::<_, i64>(&set).await;

    let mut redis = a.redis.clone();
    for user in users {
        let user_set = format!("{USER_SET_PREFIX}{user}");
        let all: Vec<String> = redis.smembers(&user_set).await.unwrap_or_default();
        let user_groups: Vec<String> = all
            .into_iter()
            .filter(|k| k.contains(":user-groups:"))
            .collect();
        if user_groups.is_empty() {
            let _ = redis
                .del::<_, i64>(vec![format!("{QUERY_PREFIX}user-groups:{user}")])
                .await;
        } else {
            let _ = redis.del::<_, i64>(&user_groups).await;
            let _: Result<i64, _> = redis.srem(&user_set, &user_groups).await;
        }
    }
}
