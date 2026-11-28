//! Environment settings used while starting the service and its backends.

pub(crate) fn required(key: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| panic!("{key} must be configured"))
}

pub(crate) fn env(key: &str, fallback: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| fallback.into())
}
