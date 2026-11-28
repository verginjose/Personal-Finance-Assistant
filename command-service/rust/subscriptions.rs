use chrono::{Days, Months, NaiveDate, Utc};
use rust_decimal::Decimal;
use sqlx::PgPool;
use uuid::Uuid;

/// Detect/refresh a subscription from a newly created recurring expense.
/// Mirrors Java's `processRecurringTransactions` upsert path. Runs after the
/// create transaction commits and is best-effort (failures are logged by the
/// caller), so it can never roll back the user's transaction.
pub async fn upsert_for_created(
    db: &PgPool,
    user: Uuid,
    name: &str,
    amount: Decimal,
    currency: &str,
    period: &str,
    last_charge: NaiveDate,
) -> sqlx::Result<()> {
    let existing: Option<(i64, String, bool, bool)> = sqlx::query_as(
        "SELECT id, period::text, period_locked, active FROM finance.subscriptions \
         WHERE user_id=$1 AND lower(name)=lower($2) ORDER BY id ASC LIMIT 1",
    )
    .bind(user)
    .bind(name)
    .fetch_optional(db)
    .await?;

    // A frequency chosen in the UI outranks the detector's guess.
    let (effective, dismissed) = match &existing {
        Some((_, stored, locked, active)) => (
            if *locked {
                stored.clone()
            } else {
                period.to_string()
            },
            !*active,
        ),
        None => (period.to_string(), false),
    };

    let next = calculate_next_charge(last_charge, &effective);
    let days = (next - Utc::now().date_naive()).num_days().max(0) as i32;

    if let Some((id, _, _, _)) = existing {
        sqlx::query(
            "UPDATE finance.subscriptions SET name=$2, amount=$3, currency=$4, period=$5, \
             next_charge_date=$6, days_until_charge=$7, active=$8, last_seen_at=now() WHERE id=$1",
        )
        .bind(id)
        .bind(name)
        .bind(amount)
        .bind(currency)
        .bind(&effective)
        .bind(next)
        .bind(days)
        .bind(!dismissed)
        .execute(db)
        .await?;
    } else {
        sqlx::query(
            "INSERT INTO finance.subscriptions \
             (user_id, name, amount, currency, period, next_charge_date, days_until_charge, \
              active, period_locked, detected_at, last_seen_at) \
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,false,now(),now())",
        )
        .bind(user)
        .bind(name)
        .bind(amount)
        .bind(currency)
        .bind(&effective)
        .bind(next)
        .bind(days)
        .bind(!dismissed)
        .execute(db)
        .await?;
    }
    Ok(())
}

fn add_period(date: NaiveDate, period: &str) -> NaiveDate {
    match period {
        "DAILY" => date + Days::new(1),
        "WEEKLY" => date + Days::new(7),
        "MONTHLY" => date.checked_add_months(Months::new(1)).unwrap_or(date),
        "YEARLY" => date.checked_add_months(Months::new(12)).unwrap_or(date),
        _ => date.checked_add_months(Months::new(1)).unwrap_or(date),
    }
}

/// Roll the cadence forward until the next charge is strictly after today,
/// matching the Java detector's `do/while` loop.
pub fn calculate_next_charge(last_charge: NaiveDate, period: &str) -> NaiveDate {
    let today = Utc::now().date_naive();
    let mut next = last_charge;
    for _ in 0..10_000 {
        next = add_period(next, period);
        if next > today {
            break;
        }
    }
    next
}

/// The occurrence immediately before `next_charge_date` for the old cadence.
pub fn previous_charge_date(next_charge: NaiveDate, period: &str) -> NaiveDate {
    match period {
        "DAILY" => next_charge - Days::new(1),
        "WEEKLY" => next_charge - Days::new(7),
        "MONTHLY" => next_charge
            .checked_sub_months(Months::new(1))
            .unwrap_or(next_charge),
        "YEARLY" => next_charge
            .checked_sub_months(Months::new(12))
            .unwrap_or(next_charge),
        _ => next_charge,
    }
}
