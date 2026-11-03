#!/usr/bin/env python3
"""Canary probe for the group-balances cache (evict -> refill -> poll).

Logs in as one group member, reads balances, posts a canary expense, samples
the Redis cache key and the tracking set, then polls balances until the canary
appears, deletes it, and polls until balances return to the baseline.

Read-only apart from the canary expense, which it deletes again.

Usage:
    python3 probe_canary.py --group 102 --email mihir@example.com
    python3 probe_canary.py --group 102 --email mihir@example.com --repeat 5

The canary prints POST_EPOCH / CANARY_ID markers so a Redis MONITOR capture
(see redis_monitor.sh) can be aligned with the probe's timeline.
"""
import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request


def kubectl_redis(args, ns, pod):
    script = 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli "$@"'
    out = subprocess.run(
        ["kubectl", "-n", ns, "exec", pod, "--", "sh", "-c", script, "sh", *args],
        capture_output=True, text=True, timeout=20)
    return out.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--group", type=int, required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--password", default="GoldenTest!")
    ap.add_argument("--base-url", default="http://localhost:18090/api")
    ap.add_argument("--amount", type=float, default=2016.00)
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--poll", type=float, default=0.3, help="seconds between balance polls")
    ap.add_argument("--poll-seconds", type=float, default=18.0)
    ap.add_argument("--namespace", default="pfa")
    ap.add_argument("--redis-pod", default="redis-0")
    a = ap.parse_args()

    key = f"finance:analytics:v1:group-balances:{a.group}"
    tset = f"finance:query:v1:group-keys:{a.group}"

    def redis(*args):
        return kubectl_redis(args, a.namespace, a.redis_pod)

    def call(method, path, token=None, uid=None, body=None):
        hdrs = {"Content-Type": "application/json", "User-Agent": "probe-canary"}
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
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, raw

    def sig(body):
        if not isinstance(body, dict):
            return body
        return tuple(sorted((str(m.get("userId")), round(float(m.get("netBalance", 0)), 2))
                            for m in body.get("memberBalances", [])))

    for run in range(1, a.repeat + 1):
        print(f"### run {run}/{a.repeat}")
        s, login = call("POST", "/auth/login", body={"email": a.email, "password": a.password})
        token, uid = login.get("token"), login.get("userId")
        print(f"login={s} user={uid}")

        s, bal = call("GET", f"/upsert/groups/{a.group}/balances", token=token, uid=uid)
        base = sig(bal)
        print(f"warm balances={s} members={len(base)}")

        print("redis key exists  :", redis("EXISTS", key), "ttl:", redis("TTL", key))
        members = redis("SMEMBERS", tset).split()
        print("tracking set size :", len(members))
        print("key tracked       :", key in members or key.rsplit(":", 1)[-1] in members)

        s, resp = call("POST", f"/upsert/groups/{a.group}/expenses", token=token, uid=uid, body={
            "amount": a.amount,
            "description": "probe canary · eviction test",
            "paidBy": uid, "splitType": "EQUAL",
            "expenseCategory": "RESTAURANTS", "currency": "INR",
        })
        canary = resp.get("id") if isinstance(resp, dict) else None
        print(f"canary POST={s} id={canary}")
        print("POST_EPOCH", time.time())
        print("CANARY_ID", canary)

        for delay in (0.0, 0.1, 0.5, 1.0, 2.0):
            if delay:
                time.sleep(delay)
            print(f"  +{delay:.1f}s key_exists={redis('EXISTS', key)} "
                  f"set_size={redis('SCARD', tset)}")

        started = time.time()
        changed = None
        deadline = started + a.poll_seconds
        while time.time() < deadline:
            s, bal = call("GET", f"/upsert/groups/{a.group}/balances", token=token, uid=uid)
            if s == 200 and sig(bal) != base:
                changed = time.time() - started
                break
            time.sleep(a.poll)
        print(f"balances changed after: {changed if changed is not None else 'TIMEOUT'}")
        print("key exists after poll :", redis("EXISTS", key), "ttl:", redis("TTL", key))

        if canary:
            s, _ = call("DELETE", f"/upsert/groups/{a.group}/expenses/{canary}", token=token, uid=uid)
            print(f"canary DELETE={s}")
            started = time.time()
            back = None
            deadline = started + a.poll_seconds
            while time.time() < deadline:
                s, bal = call("GET", f"/upsert/groups/{a.group}/balances", token=token, uid=uid)
                if s == 200 and sig(bal) == base:
                    back = time.time() - started
                    break
                time.sleep(a.poll)
            print(f"balances restored after: {back if back is not None else 'TIMEOUT'}")


if __name__ == "__main__":
    main()
