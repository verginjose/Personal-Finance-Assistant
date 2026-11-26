use super::*;
use crate::ledger::{decimal, money};
use rust_decimal::Decimal;

// Aggregate each group's expenses once, rather than repeating the scans for
// every member. Both totals keep PostgreSQL numeric arithmetic and the same
// settlement treatment as the previous lateral aggregates.
const BALANCES_SQL: &str = r#"
WITH paid AS (
    SELECT paid_by AS user_id, sum(amount) AS paid,
           sum(amount) FILTER (WHERE split_type <> 'SETTLEMENT') AS spent
    FROM groups.shared_expenses WHERE group_id = $1 GROUP BY paid_by
), owed AS (
    SELECT s.user_id, sum(s.amount) AS owed
    FROM groups.expense_splits s
    JOIN groups.shared_expenses e ON e.id = s.shared_expense_id
    WHERE e.group_id = $1 GROUP BY s.user_id
)
SELECT jsonb_build_object(
    'userId', m.user_id, 'userName', m.name,
    'totalPaid', round(coalesce(p.paid, 0), 2),
    'totalSpent', round(coalesce(p.spent, 0), 2),
    'totalOwed', round(coalesce(o.owed, 0), 2),
    'netBalance', round(coalesce(p.paid, 0) - coalesce(o.owed, 0), 2)
)
FROM groups.group_members m
LEFT JOIN paid p ON p.user_id = m.user_id
LEFT JOIN owed o ON o.user_id = m.user_id
WHERE m.group_id = $1
"#;
pub async fn membership(a: &App, id: Uuid, g: i64, accepted: bool) -> Result<()> {
    let ok=sqlx::query_scalar::<_,bool>("SELECT EXISTS(SELECT 1 FROM groups.group_members m JOIN groups.expense_groups g ON g.id=m.group_id WHERE m.user_id=$1 AND m.group_id=$2 AND NOT g.is_deleted AND (NOT $3 OR m.status='ACCEPTED'))").bind(id).bind(g).bind(accepted).fetch_one(&a.db).await?;
    if !ok {
        return Err(Error(
            StatusCode::FORBIDDEN,
            "Group membership required".into(),
        ));
    }
    Ok(())
}
async fn members(a: &App, g: i64) -> Result<Vec<Value>> {
    Ok(sqlx::query_scalar::<_,Value>("SELECT (to_jsonb(m)-'is_archived')||jsonb_build_object('archived',is_archived,'admin',role='ADMIN') FROM groups.group_members m WHERE group_id=$1").bind(g).fetch_all(&a.db).await?.into_iter().map(camel).collect())
}
pub async fn balances(a: &App, id: Uuid, g: i64) -> Result<Value> {
    membership(a, id, g, true).await?;
    let group = sqlx::query_scalar::<_, Value>(
        "SELECT to_jsonb(g) FROM groups.expense_groups g WHERE id=$1",
    )
    .bind(g)
    .fetch_one(&a.db)
    .await?;
    let members: Vec<Value> = sqlx::query_scalar(BALANCES_SQL)
        .bind(g)
        .fetch_all(&a.db)
        .await?;
    let mut debts: Vec<(String, String, Decimal)> = Vec::new();
    let mut credits = Vec::new();
    let mut spent = Decimal::ZERO;
    for m in &members {
        let net = decimal(&m["netBalance"]).unwrap_or_default();
        spent += decimal(&m["totalSpent"]).unwrap_or_default();
        let v = (
            m["userId"].as_str().unwrap_or("").to_string(),
            m["userName"].as_str().unwrap_or("").to_string(),
            net.abs(),
        );
        if net < Decimal::ZERO {
            debts.push(v)
        } else if net > Decimal::ZERO {
            credits.push(v)
        }
    }
    debts.sort_by(|a, b| b.2.cmp(&a.2).then(a.0.cmp(&b.0)));
    credits.sort_by(|a, b| b.2.cmp(&a.2).then(a.0.cmp(&b.0)));
    let mut suggestions = Vec::new();
    let (mut di, mut ci) = (0, 0);
    while di < debts.len() && ci < credits.len() {
        let amount = debts[di].2.min(credits[ci].2);
        suggestions.push(json!({"fromUserId":debts[di].0,"fromUserName":debts[di].1,"toUserId":credits[ci].0,"toUserName":credits[ci].1,"amount":money(amount),"currency":group["currency"]}));
        debts[di].2 -= amount;
        credits[ci].2 -= amount;
        if debts[di].2.is_zero() {
            di += 1
        }
        if credits[ci].2.is_zero() {
            ci += 1
        }
    }
    Ok(
        json!({"groupId":g,"groupName":group["name"],"memberBalances":members,"simplifiedDebts":suggestions,"totalSpend":money(spent)}),
    )
}
fn group_shape(mut value: Value) -> Value {
    if let Some(o) = value.as_object_mut()
        && let Some(deleted) = o.remove("isDeleted")
    {
        o.insert("deleted".into(), deleted);
    }
    value
}
pub async fn handle(a: &App, id: Uuid, path: &str, p: &Params) -> Result<Value> {
    if path == "/upsert/groups" {
        return Ok(Value::Array(list(&a.db,"SELECT to_jsonb(g)||jsonb_build_object('is_archived',m.is_archived,'current_user_status',m.status,'current_user_role',coalesce(m.role,'MEMBER'),'current_user_net_balance',coalesce((SELECT sum(amount) FROM groups.shared_expenses WHERE group_id=g.id AND paid_by=$1),0)-coalesce((SELECT sum(s.amount) FROM groups.expense_splits s JOIN groups.shared_expenses e ON e.id=s.shared_expense_id WHERE e.group_id=g.id AND s.user_id=$1),0)) FROM groups.expense_groups g JOIN groups.group_members m ON m.group_id=g.id AND m.user_id=$1 WHERE NOT g.is_deleted ORDER BY g.created_at DESC",id).await?.into_iter().map(group_shape).collect()));
    }
    if path == "/upsert/groups/shared-people" {
        return Ok(Value::Array(list(&a.db,"SELECT jsonb_build_object('userId',peer.user_id,'name',min(peer.name),'sharedGroupCount',count(*),'sharedGroups',jsonb_agg(g.name ORDER BY g.name)) FROM groups.group_members mine JOIN groups.group_members peer ON mine.group_id=peer.group_id JOIN groups.expense_groups g ON g.id=mine.group_id WHERE mine.user_id=$1 AND mine.status='ACCEPTED' AND peer.status='ACCEPTED' AND peer.user_id<>$1 GROUP BY peer.user_id ORDER BY lower(min(peer.name))",id).await?));
    }
    if path == "/upsert/groups/members" {
        let raw = p
            .get("groupIds")
            .ok_or_else(|| bad("Supply 1 to 50 positive group IDs"))?;
        let ids = if raw.is_empty() {
            Vec::new()
        } else {
            raw.split(',')
                .map(|s| s.parse().map_err(|_| bad("Invalid group ID")))
                .collect::<Result<Vec<i64>>>()?
        };
        if ids.is_empty() || ids.len() > 50 || ids.iter().any(|g| *g <= 0) {
            return Err(bad("Supply 1 to 50 positive group IDs"));
        }
        let mut out = serde_json::Map::new();
        for g in ids {
            membership(a, id, g, true).await?;
            out.insert(g.to_string(), Value::Array(members(a, g).await?));
        }
        return Ok(Value::Object(out));
    }
    let parts: Vec<_> = path
        .trim_start_matches("/upsert/groups/")
        .split('/')
        .collect();
    let g = parts[0]
        .parse::<i64>()
        .map_err(|_| bad("Invalid group ID"))?;
    membership(a, id, g, parts.len() > 1).await?;
    if parts.len() == 1 {
        let result=camel(sqlx::query_scalar::<_,Value>("SELECT to_jsonb(g)||jsonb_build_object('is_archived',m.is_archived,'current_user_status',m.status,'current_user_role',coalesce(m.role,'MEMBER'),'current_user_net_balance',null) FROM groups.expense_groups g JOIN groups.group_members m ON m.group_id=g.id AND m.user_id=$1 WHERE g.id=$2").bind(id).bind(g).fetch_one(&a.db).await?);
        return Ok(group_shape(result));
    }
    match parts[1] {
        "members" => Ok(Value::Array(members(a, g).await?)),
        "balances" => balances(a, id, g).await,
        "expenses" | "activity" => {
            let (n, size) = paging(p, 20)?;
            let activity = parts[1] == "activity";
            let table = if activity {
                "activity.group_activities"
            } else {
                "groups.shared_expenses"
            };
            let sql = if activity {
                format!(
                    "SELECT to_jsonb(e) FROM {table} e WHERE group_id=$1 ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3"
                )
            } else {
                "SELECT to_jsonb(e)||jsonb_build_object('my_share',(SELECT sum(amount) FROM groups.expense_splits WHERE shared_expense_id=e.id AND user_id=$4),'paid_by_name',(SELECT name FROM groups.group_members WHERE group_id=e.group_id AND user_id=e.paid_by LIMIT 1),'settlement_to_name',(SELECT user_name FROM groups.expense_splits WHERE shared_expense_id=e.id AND user_id<>e.paid_by AND e.split_type='SETTLEMENT' ORDER BY id DESC LIMIT 1)) FROM groups.shared_expenses e WHERE group_id=$1 ORDER BY created_at DESC,id DESC LIMIT $2 OFFSET $3".into()
            };
            let query = sqlx::query_scalar::<_, Value>(&sql)
                .bind(g)
                .bind(size)
                .bind(n.saturating_mul(size));
            let rows = if activity {
                query.fetch_all(&a.db).await?
            } else {
                query.bind(id).fetch_all(&a.db).await?
            };
            let count = sqlx::query_scalar::<_, i64>(&format!(
                "SELECT count(*) FROM {table} WHERE group_id=$1"
            ))
            .bind(g)
            .fetch_one(&a.db)
            .await?;
            Ok(page(rows.into_iter().map(camel).collect(), count, n, size))
        }
        _ => Err(Error(StatusCode::NOT_FOUND, "Not found".into())),
    }
}
