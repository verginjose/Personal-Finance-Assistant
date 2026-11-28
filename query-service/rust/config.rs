//! Environment settings used during startup and by external clients.

pub(crate) fn env(k: &str, d: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| d.into())
}
