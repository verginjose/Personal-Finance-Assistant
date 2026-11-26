use super::*;
use axum::routing::{get, post, put};

pub(crate) fn db(app: &App) -> &sqlx::PgPool {
    app.db.as_ref().expect("auth database")
}

mod models;
mod tokens;
mod password;
mod handlers;
mod verify;

pub(crate) use models::*;
pub(crate) use tokens::*;
pub(crate) use password::*;
pub(crate) use handlers::*;
pub(crate) use verify::*;

pub fn routes() -> Router<App> {
    Router::new()
        .route(
            "/api/auth/health",
            get(|| async { Json(json!({"status":"UP","service":"auth-service"})) }),
        )
        .route("/api/auth/register", post(register))
        .route("/api/auth/login", post(login))
        .route("/api/auth/refresh", post(refresh))
        .route("/api/auth/logout", post(logout))
        .route("/api/auth/me", get(me))
        .route("/api/auth/profile", put(profile))
        .route("/api/auth/change-password", post(change_password))
        .route("/api/auth/stream-ticket", post(ticket))
        .route("/api/auth/users/search", get(search))
        .route("/api/auth/users/bulk", post(bulk))
        .route("/api/auth/users/{id}", get(get_user))
        .route("/internal/verify", get(verify))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::{HeaderMap, HeaderValue};
    use base64::Engine;
    use jsonwebtoken::Algorithm;
    #[test]
    fn spring_key_selection() {
        assert_eq!(algorithm(&[0; 32]), Algorithm::HS256);
        assert_eq!(algorithm(&[0; 48]), Algorithm::HS384);
        assert_eq!(algorithm(&[0; 64]), Algorithm::HS512);
        assert!(resolve_key("weak").is_err());
        assert_eq!(
            resolve_key(&base64::engine::general_purpose::STANDARD.encode([7; 64])).unwrap(),
            vec![7; 64]
        );
    }
    #[test]
    fn cookie_overrides_body() {
        let mut h = HeaderMap::new();
        h.insert(
            "cookie",
            HeaderValue::from_static("a=b; pfa_refresh=cookie"),
        );
        assert_eq!(refresh_input(&h, &json!({"refreshToken":"body"})), "cookie");
    }
    #[test]
    fn currency_validation() {
        assert_eq!(currency(" inr ").unwrap(), "INR");
        assert!(currency("<x>").is_err());
    }
    #[test]
    fn bcrypt_spring_compatibility() {
        let encoded = bcrypt::hash("Password123!", 4)
            .unwrap()
            .replacen("$2b$", "$2a$", 1);
        assert!(bcrypt::verify("Password123!", &encoded).unwrap());
        assert!(!bcrypt::verify("wrong", &encoded).unwrap());
    }
}
