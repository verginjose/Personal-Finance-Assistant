use crate::app::App;
use crate::error::{Error, Result};

pub fn currency(value: &str) -> Result<String> {
    let c = value.trim().to_ascii_uppercase();
    if c.len() != 3 || !c.bytes().all(|b| b.is_ascii_uppercase()) {
        return Err(Error::bad("Default currency must be a 3-letter ISO code"));
    }
    Ok(c)
}
pub(crate) async fn hash(app: &App, password: String) -> Result<String> {
    let permit = app
        .hash_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(Error::internal)?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        bcrypt::hash(password, 10)
    })
    .await
    .map_err(Error::internal)?
    .map_err(Error::internal)
}
pub(crate) async fn matches(app: &App, password: String, encoded: String) -> Result<bool> {
    let permit = app
        .hash_slots
        .clone()
        .acquire_owned()
        .await
        .map_err(Error::internal)?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        bcrypt::verify(password, &encoded).unwrap_or(false)
    })
    .await
    .map_err(Error::internal)
}
