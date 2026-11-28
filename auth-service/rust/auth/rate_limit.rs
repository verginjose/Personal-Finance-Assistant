//! Redis token buckets and the trusted loopback test-listener exemption.

use crate::{
    app::App,
    error::{Error, Result},
};
use axum::http::{HeaderMap, StatusCode};

/// Traefik strips this header on every public request and sets it only on
/// its loopback-only test listener. /internal/verify admits only edge traffic.
pub fn is_local_test(headers: &HeaderMap) -> bool {
    headers
        .get("x-pfa-local-test")
        .is_some_and(|value| value == "1")
}

pub(crate) async fn rate(app: &App, key: &str, replenish: f64, burst: f64) -> Result<()> {
    if app.rate_limit_disabled {
        return Ok(());
    }
    let allowed: i32 = redis::Script::new(r#"
        local t=redis.call('TIME'); local now=tonumber(t[1])+tonumber(t[2])/1000000
        local data=redis.call('HMGET',KEYS[1],'tokens','at'); local tokens=tonumber(data[1]) or tonumber(ARGV[2]); local at=tonumber(data[2]) or now
        tokens=math.min(tonumber(ARGV[2]),tokens+math.max(0,now-at)*tonumber(ARGV[1])); local ok=0
        if tokens>=1 then tokens=tokens-1; ok=1 end
        redis.call('HSET',KEYS[1],'tokens',tokens,'at',now); redis.call('EXPIRE',KEYS[1],math.ceil(2*tonumber(ARGV[2])/tonumber(ARGV[1]))); return ok
    "#).key(format!("native_rate:{key}")).arg(replenish).arg(burst).invoke_async(&mut app.redis.clone()).await.map_err(Error::internal)?;
    if allowed == 0 {
        return Err(Error(
            StatusCode::TOO_MANY_REQUESTS,
            "Rate limit exceeded".into(),
        ));
    }
    Ok(())
}
