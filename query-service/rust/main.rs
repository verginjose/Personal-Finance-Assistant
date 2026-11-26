mod analytics;
mod groups;
mod ledger;
mod planning;
use axum::{
    Json, Router,
    extract::{OriginalUri, Query, Request, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::get,
};
use chrono::{NaiveDate, NaiveDateTime};
use serde_json::{Value, json};
use sqlx::{PgPool, postgres::PgPoolOptions};
use std::{collections::HashMap, sync::Arc, time::Duration};
use uuid::Uuid;
type Params = HashMap<String, String>;
#[derive(Clone)]
struct App {
    db: PgPool,
    http: reqwest::Client,
    fx: Arc<tokio::sync::Mutex<HashMap<String, (std::time::Instant, Value)>>>,
    fx_locks: Arc<tokio::sync::Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    metrics: metrics_exporter_prometheus::PrometheusHandle,
    redis: redis::aio::ConnectionManager,
    advice: Arc<tokio::sync::Mutex<HashMap<String, (std::time::Instant, Value)>>>,
}
#[derive(Debug)]
struct Error(StatusCode, String);
type Result<T> = std::result::Result<T, Error>;
impl IntoResponse for Error {
    fn into_response(self) -> Response {
        (
            self.0,
            Json(json!({"error":self.0.canonical_reason(),"message":self.1})),
        )
            .into_response()
    }
}
impl From<sqlx::Error> for Error {
    fn from(e: sqlx::Error) -> Self {
        tracing::error!(error=%e,"query failed");
        Self(
            StatusCode::SERVICE_UNAVAILABLE,
            "Service temporarily unavailable".into(),
        )
    }
}
fn bad(m: &str) -> Error {
    Error(StatusCode::BAD_REQUEST, m.into())
}
fn env(k: &str, d: &str) -> String {
    std::env::var(k).unwrap_or_else(|_| d.into())
}
fn user(h: &HeaderMap, p: &Params) -> Result<Uuid> {
    let raw = h
        .get("x-user-id")
        .and_then(|s| s.to_str().ok())
        .ok_or_else(|| Error(StatusCode::UNAUTHORIZED, "Missing user context".into()))?;
    // Match Spring's SecurityInterceptor: a supplied userId that differs from
    // the header is rejected as forbidden before its format is even considered.
    if let Some(other) = p.get("userId")
        && !other.eq_ignore_ascii_case(raw)
    {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "User ID mismatch".into(),
        ));
    }
    Uuid::parse_str(raw).map_err(|_| bad("Invalid user ID"))
}
fn required(p: &Params, keys: &[&str]) -> Result<()> {
    for key in keys {
        if !p.contains_key(*key) {
            return Err(bad(&format!(
                "Required request parameter '{key}' is missing"
            )));
        }
    }
    Ok(())
}
// Spring's Pageable resolver rejects these rather than clamping, so clients
// see the same 400s against either implementation.
fn paging(p: &Params, default_size: i64) -> Result<(i64, i64)> {
    let page = integer(p, "page", 0)?;
    let size = integer(p, "size", default_size)?;
    if page < 0 {
        return Err(bad("Page index must not be less than zero"));
    }
    if size < 1 {
        return Err(bad("Page size must not be less than one"));
    }
    Ok((page, size))
}
fn integer(p: &Params, k: &str, d: i64) -> Result<i64> {
    p.get(k)
        .map(|v| v.parse().map_err(|_| bad("Invalid integer")))
        .unwrap_or(Ok(d))
}
fn camel(v: Value) -> Value {
    match v {
        Value::Array(v) => Value::Array(v.into_iter().map(camel).collect()),
        Value::Object(o) => Value::Object(
            o.into_iter()
                .map(|(k, v)| {
                    let mut next = false;
                    let name: String = k
                        .chars()
                        .filter_map(|c| {
                            if c == '_' {
                                next = true;
                                None
                            } else if next {
                                next = false;
                                Some(c.to_ascii_uppercase())
                            } else {
                                Some(c)
                            }
                        })
                        .collect();
                    {
                        let mut value = camel(v);
                        if [
                            "createdAt",
                            "updatedAt",
                            "joinedAt",
                            "nextRunDate",
                            "expenseDate",
                            "settledAt",
                            "completedAt",
                            "deletedAt",
                            "detectedAt",
                            "lastSeenAt",
                        ]
                        .contains(&name.as_str())
                            && let Some(text) = value.as_str()
                            && text.contains('T')
                            && !text.ends_with('Z')
                        {
                            value = json!(format!("{text}Z"));
                        }
                        (name, value)
                    }
                })
                .collect(),
        ),
        v => v,
    }
}
async fn list(db: &PgPool, sql: &str, id: Uuid) -> Result<Vec<Value>> {
    Ok(sqlx::query_scalar::<_, Value>(sql)
        .bind(id)
        .fetch_all(db)
        .await?
        .into_iter()
        .map(camel)
        .collect())
}
fn page(rows: Vec<Value>, count: i64, number: i64, size: i64) -> Value {
    let pages = (count + size - 1) / size;
    json!({"number":number,"size":size,"totalElements":count,"totalPages":pages,"first":number==0,"last":number+1>=pages,"numberOfElements":rows.len(),"empty":rows.is_empty(),"content":rows})
}
fn dates(p: &Params, shift: bool) -> Result<(Option<NaiveDateTime>, Option<NaiveDateTime>)> {
    let offset = if shift {
        integer(p, "tzOffsetMinutes", 0)?.clamp(-1080, 1080)
    } else {
        0
    };
    let parse = |k: &str, end: bool| -> Result<Option<NaiveDateTime>> {
        match p.get(k) {
            None => Ok(None),
            Some(v) => {
                let t = if v.len() == 10 {
                    NaiveDate::parse_from_str(v, "%Y-%m-%d")
                        .map_err(|_| bad("Invalid date"))?
                        .and_hms_opt(
                            if end { 23 } else { 0 },
                            if end { 59 } else { 0 },
                            if end { 59 } else { 0 },
                        )
                        .unwrap()
                } else {
                    NaiveDateTime::parse_from_str(v, "%Y-%m-%dT%H:%M:%S%.f")
                        .map_err(|_| bad("Invalid date"))?
                };
                Ok(Some(t - chrono::Duration::minutes(offset)))
            }
        }
    };
    Ok((parse("startDate", false)?, parse("endDate", true)?))
}
async fn handle(
    State(a): State<App>,
    h: HeaderMap,
    OriginalUri(uri): OriginalUri,
    Query(mut p): Query<Params>,
) -> Result<Response> {
    let path = uri.path();
    if path == "/upsert/groups/members" {
        let ids: Vec<_> = url::form_urlencoded::parse(uri.query().unwrap_or("").as_bytes())
            .filter(|(k, _)| k == "groupIds")
            .map(|(_, v)| v.into_owned())
            .collect();
        if !ids.is_empty() {
            p.insert("groupIds".into(), ids.join(","));
        }
    }

    if path.ends_with("/health") {
        return Ok(Json(json!({"status":"UP","service":"query-service"})).into_response());
    }
    let id = user(&h, &p)?;
    // Spring requires ?userId on every ledger/planning read, plus these
    // analytics reads; group reads are header-only. Mirror that contract so a
    // caller that omits the parameter gets the same 400 in both services.
    let needs_user_param = (path.starts_with("/upsert/") && !path.starts_with("/upsert/groups"))
        || path == "/analytics/transaction-entries"
        || path.starts_with("/analytics/transactions/")
        || path == "/analytics/budgets/trends"
        || path == "/analytics/ai-insights"
        || (path.starts_with("/analytics/goals/") && path.ends_with("/forecast"));
    if needs_user_param {
        required(&p, &["userId"])?;
    }
    if path == "/upsert/search" {
        required(&p, &["q"])?;
    }
    if path == "/analytics/transactions/income-by-category" {
        required(&p, &["categoryName", "startDate", "endDate"])?;
    }
    if path == "/analytics/transactions/by-type" {
        required(&p, &["transactionType", "startDate", "endDate"])?;
    }
    for key in ["type", "transactionType", "transactionFilter"] {
        if let Some(value) = p.get(key)
            && !["INCOME", "EXPENSE"].contains(&value.as_str()) {
                return Err(bad("Invalid transaction type"));
            }
    }
    for key in ["category", "categoryName"] {
        if let Some(value) = p.get(key)
            && !CATEGORIES.contains(&value.as_str()) {
                return Err(bad("Invalid category"));
            }
    }
    let mut scopes = vec![format!("user:{id}")];
    if let Some(group) = path
        .strip_prefix("/upsert/groups/")
        .and_then(|s| s.split('/').next())
        .and_then(|v| v.parse::<i64>().ok())
    {
        scopes.push(format!("group:{group}"));
    }
    if path == "/upsert/groups/members"
        && let Some(ids) = p.get("groupIds")
    {
        for id in ids.split(',').take(50) {
            if let Ok(id) = id.parse::<i64>() {
                scopes.push(format!("group:{id}"));
            }
        }
    }
    wait_replica(&a, &scopes).await?;
    let result = if path.starts_with("/upsert/groups") {
        groups::handle(&a, id, path, &p).await?
    } else if path.starts_with("/analytics") {
        analytics::handle(&a, id, path, &p).await?
    } else if path.starts_with("/upsert/goals")
        || path == "/upsert/budgets"
        || path == "/upsert/subscriptions"
    {
        planning::handle(&a, id, path, &p).await?
    } else {
        return ledger::handle(&a, id, path, &p).await;
    };
    Ok(Json(result).into_response())
}
async fn health(State(a): State<App>) -> Result<Json<Value>> {
    sqlx::query("SELECT 1").execute(&a.db).await?;
    Ok(Json(json!({"status":"UP"})))
}
async fn observe(req: Request, next: Next) -> Response {
    let now = std::time::Instant::now();
    let path = req.uri().path();
    let route = if path.starts_with("/upsert/groups/") {
        "/upsert/groups/{id}"
    } else if path.starts_with("/upsert/entries/") {
        "/upsert/entries/{id}"
    } else if path.starts_with("/analytics/goals/") {
        "/analytics/goals/{id}/forecast"
    } else {
        path
    }
    .to_string();
    let method = req.method().to_string();
    let r = next.run(req).await;
    metrics::histogram!("http_server_requests_seconds","uri"=>route,"method"=>method,"status"=>r.status().as_u16().to_string()).record(now.elapsed().as_secs_f64());
    r
}
#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .json()
        .with_env_filter("info")
        .init();
    let metrics = metrics_exporter_prometheus::PrometheusBuilder::new()
        .set_buckets(&[0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1., 2.5, 5., 10.])?
        .add_global_label("application", "query-service")
        .install_recorder()?;
    let options = sqlx::postgres::PgConnectOptions::new()
        .host(&env("DB_HOST", "postgres-replica"))
        .port(env("DB_PORT", "5432").parse()?)
        .database(&env("DB_NAME", "pfa"))
        .username(&std::env::var("DB_USER")?)
        .password(&std::env::var("DB_PASSWORD")?)
        .options([("default_transaction_read_only", "on")]);
    let db = PgPoolOptions::new()
        .max_connections(20)
        .acquire_timeout(Duration::from_secs(10))
        .connect_with(options)
        .await?;
    let mut redis_url = url::Url::parse(&format!(
        "redis://{}:{}/",
        env("REDIS_HOST", "redis"),
        env("REDIS_SERVER_PORT", "6379")
    ))?;
    let password = env("REDIS_PASSWORD", "");
    if !password.is_empty() {
        redis_url.set_password(Some(&password)).unwrap();
    }
    let redis = redis::aio::ConnectionManager::new_with_config(
        redis::Client::open(redis_url.as_str())?,
        redis::aio::ConnectionManagerConfig::new()
            .set_connection_timeout(Duration::from_secs(3))
            .set_response_timeout(Duration::from_secs(2)),
    )
    .await?;
    let app = App {
        db,
        redis,
        advice: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        http: reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(3))
            .timeout(Duration::from_secs(30))
            .build()?,
        fx: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        fx_locks: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        metrics,
    };
    // The deployed manifest probes and scrapes the service port (8084), as the
    // Spring service did; keep the management listener for direct access too.
    let router = Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/health/readiness", get(health))
        .route(
            "/actuator/health/liveness",
            get(|| async { Json(json!({"status":"UP"})) }),
        )
        .route(
            "/actuator/prometheus",
            get(|State(a): State<App>| async move { a.metrics.render() }),
        )
        .route("/{*path}", get(handle))
        .layer(middleware::from_fn(observe))
        .layer(tower::limit::ConcurrencyLimitLayer::new(200))
        .with_state(app.clone());
    let management = Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/health/readiness", get(health))
        .route(
            "/actuator/health/liveness",
            get(|| async { Json(json!({"status":"UP"})) }),
        )
        .route(
            "/actuator/prometheus",
            get(|State(a): State<App>| async move { a.metrics.render() }),
        )
        .with_state(app);
    let api = tokio::net::TcpListener::bind(format!("0.0.0.0:{}", env("PORT", "8084"))).await?;
    let mgmt = tokio::net::TcpListener::bind(format!("0.0.0.0:{}", env("MANAGEMENT_PORT", "9090")))
        .await?;
    tokio::try_join!(
        async {
            axum::serve(api, router)
                .with_graceful_shutdown(shutdown())
                .await
        },
        async {
            axum::serve(mgmt, management)
                .with_graceful_shutdown(shutdown())
                .await
        }
    )?;
    Ok(())
}
async fn shutdown() {
    let mut signal =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).unwrap();
    tokio::select! {_=signal.recv()=>{},_=tokio::signal::ctrl_c()=>{}}
}

async fn wait_replica(a: &App, scopes: &[String]) -> Result<()> {
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

const CATEGORIES: &[&str] = &[
    "SALARY",
    "COMMISSION",
    "CAPITAL_GAINS",
    "FREELANCE",
    "BUSINESS",
    "INVESTMENTS",
    "RENTAL_INCOME",
    "DIVIDENDS",
    "INTEREST",
    "BONUS",
    "PENSION",
    "GOVT_BENEFITS",
    "CASHBACK_REWARDS",
    "GIFTS_RECEIVED",
    "TAX_REFUND",
    "SIDE_HUSTLE",
    "OTHER_INCOME",
    "RENT",
    "HOME_LOAN_EMI",
    "FURNITURE",
    "HOUSEHOLD_SUPPLIES",
    "DOMESTIC_HELP",
    "SOCIETY_MAINTENANCE",
    "HOME_MAINTENANCE",
    "PROPERTY_TAX",
    "ELECTRICITY",
    "WATER",
    "GAS",
    "INTERNET",
    "MOBILE_PHONE",
    "OTT_SUBSCRIPTIONS",
    "GROCERIES",
    "RESTAURANTS",
    "FOOD_DELIVERY",
    "COFFEE_AND_SNACKS",
    "FUEL",
    "PUBLIC_TRANSPORT",
    "CAB_AND_AUTO",
    "VEHICLE_EMI",
    "VEHICLE_MAINTENANCE",
    "PARKING_AND_TOLLS",
    "FLIGHT_AND_TRAIN",
    "DOCTOR_AND_CLINIC",
    "MEDICINES",
    "HEALTH_INSURANCE",
    "GYM_AND_FITNESS",
    "MENTAL_WELLNESS",
    "TUITION_AND_FEES",
    "BOOKS_AND_COURSES",
    "COACHING",
    "STUDENT_LOAN_EMI",
    "CLOTHING",
    "ELECTRONICS",
    "HOME_APPLIANCES",
    "PERSONAL_CARE",
    "GIFTS_GIVEN",
    "MOVIES_AND_EVENTS",
    "GAMING",
    "SPORTS_AND_HOBBIES",
    "BOOKS_AND_MAGAZINES",
    "TRAVEL_VACATION",
    "HOTEL_AND_STAYS",
    "BANK_CHARGES",
    "CARD_FEES",
    "LOAN_INTEREST",
    "SOFTWARE_SUBSCRIPTIONS",
    "CLOUD_STORAGE",
    "PROFESSIONAL_MEMBERSHIPS",
    "LIFE_INSURANCE",
    "VEHICLE_INSURANCE",
    "CREDIT_CARD_PAYMENT",
    "LOAN_REPAYMENT",
    "MUTUAL_FUNDS_SIP",
    "STOCKS_AND_TRADING",
    "CRYPTO",
    "EMERGENCY_FUND",
    "FIXED_DEPOSIT",
    "CHARITY_AND_DONATIONS",
    "TAXES",
    "FINES_AND_PENALTIES",
    "PETS",
    "CHILDCARE",
    "ELDER_CARE",
    "GOAL",
    "SETTLEMENT",
    "OTHERS",
    "OWN_ACCOUNT_TRANSFER",
    "BORROWING",
    "LENDING",
    "LOAN_PRINCIPAL",
];
