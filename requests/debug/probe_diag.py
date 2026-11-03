#!/usr/bin/env python3
"""Canary diagnostic: API balances vs the replica's rows, step by step.

Posts a canary expense, then at several delays prints:
  * whether the row is visible on the read replica *before* the API call,
  * the API balance deltas versus the baseline,
  * whether the Redis cache key exists and its TTL.

This is the script that isolated the current bug: in failing runs the row is on
the replica, the cache key is re-created with a 600 s TTL, and the API still
returns the pre-canary balances.

Usage:
    python3 probe_diag.py --group 102 --email mihir@example.com
"""
import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", type=int, required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", default="GoldenTest!")
    ap.add_argument("--base-url", default="http://localhost:18090/api")
    ap.add_argument("--amount", type=float, default=2016.00)
    ap.add_argument("--description", default="diag canary")
    ap.add_argument("--namespace", default="pfa")
    ap.add_argument("--redis-pod", default="redis-0")
    ap.add_argument("--sql-pod", default="postgres-replica-0")
    ap.add_argument("--db-user", default="pfa_admin")
    ap.add_argument("--db-name", default="pfa_db")
    a = ap.parse_args()

    key = f"finance:analytics:v1:group-balances:{a.group}"
    tset = f"finance:query:v1:group-keys:{a.group}"

    def redis(*cmd):
        return subprocess.run(
            ["kubectl", "-n", a.namespace, "exec", a.redis_pod, "--", "sh", "-c",
             'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli "$@"', "sh", *cmd],
            capture_output=True, text=True, timeout=20).stdout.strip()

    def sql(query):
        return subprocess.run(
            ["kubectl", "-n", a.namespace, "exec", a.sql_pod, "-c", "postgres", "--",
             "psql", "-U", a.db_user, "-d", a.db_name, "-Atc", query],
            capture_output=True, text=True, timeout=30).stdout.strip()

    def call(method, path, token=None, uid=None, body=None):
        hdrs = {"Content-Type": "application/json"}
        if token:
            hdrs["Authorization"] = f"Bearer {token}"
            hdrs["X-User-Id"] = uid
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{a.base_url}{path}", data=data, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()[:200]

    def nets(body):
        mbs = body.get("memberBalances", []) if isinstance(body, dict) else []
        if isinstance(mbs, list) and len(mbs) == 2 and isinstance(mbs[0], str):
            mbs = mbs[1]
        return {str(m["userId"])[:8]: float(m["netBalance"]) for m in mbs}

    s, login = call("POST", "/auth/login", body={"email": a.email, "password": a.password})
    token, uid = login["token"], login["userId"]
    print("user", uid[:8])

    s, bal = call("GET", f"/upsert/groups/{a.group}/balances", token=token, uid=uid)
    base = nets(bal)
    print("baseline:", base)

    s, resp = call("POST", f"/upsert/groups/{a.group}/expenses", token=token, uid=uid, body={
        "amount": a.amount, "description": a.description, "paidBy": uid,
        "splitType": "EQUAL", "expenseCategory": "RESTAURANTS", "currency": "INR",
    })
    cid = resp.get("id") if isinstance(resp, dict) else None
    print("POST", s, "id", cid)
    print("POST_EPOCH", time.time())
    print("after POST: exists=", redis("EXISTS", key), "ttl=", redis("TTL", key),
          "set=", redis("SCARD", tset))

    row = ("select count(*) from groups.shared_expenses "
           f"where group_id={a.group} and description='{a.description}'")
    for wait in (0.5, 1.5, 3.0, 6.0):
        time.sleep(wait)
        visible = sql(row)
        s, bal = call("GET", f"/upsert/groups/{a.group}/balances", token=token, uid=uid)
        now = nets(bal)
        delta = {k: round(now.get(k, 0) - base.get(k, 0), 2) for k in base}
        print(f"t+{wait}s sql_before={visible} api:", delta,
              "| exists=", redis("EXISTS", key), "ttl=", redis("TTL", key))

    if cid:
        print("DELETE", call("DELETE", f"/upsert/groups/{a.group}/expenses/{cid}",
                             token=token, uid=uid)[0])


if __name__ == "__main__":
    main()
