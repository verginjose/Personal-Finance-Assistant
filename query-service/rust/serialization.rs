use serde_json::{Value, json};

pub fn camel(v: Value) -> Value {
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
pub fn page(rows: Vec<Value>, count: i64, number: i64, size: i64) -> Value {
    let pages = (count + size - 1) / size;
    json!({"number":number,"size":size,"totalElements":count,"totalPages":pages,"first":number==0,"last":number+1>=pages,"numberOfElements":rows.len(),"empty":rows.is_empty(),"content":rows})
}
