pub(crate) fn env(key: &str, fallback: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| fallback.into())
}
pub(crate) fn required(key: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| panic!("{key} must be configured"))
}
pub(crate) fn now() -> u64 {
    chrono::Utc::now().timestamp() as u64
}
