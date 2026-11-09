#!/usr/bin/env python3
"""Open-loop load generator for the Personal Finance Assistant.

Unlike run_golden_test.py (a correctness suite paced per user), this drives a
*request rate* against the gateway and measures what the pods do at that rate.

Setup once creates a pool of real accounts + a small ledger per account:

    python3 requests/loadgen.py --base-url http://localhost:18090 \
        --users 200 --setup-only --state /tmp/loadgen-state.json

Then run a staged ramp (100 -> 300 -> 600 -> 1000 req/s, 60 s each):

    python3 requests/loadgen.py --base-url http://localhost:18090 \
        --state /tmp/loadgen-state.json --stages 100,300,600,1000 \
        --stage-seconds 60 --out /tmp/loadgen-report.json

Traffic mix (share of the offered rate, OCR pinned to a fixed 2/s):
    query-service reads    ~68%   entries list, summary, analytics
    command-service writes ~20%   create transaction
    auth-service reads     ~12%   /auth/me
    OCR upload             fixed 2/s through /api/bill/process (MinIO + Rabbit
                          -> ocr-service), kept low on purpose: it is async and
                          an image upload is 100x the work of a JSON read.

Stdlib + aiohttp. Exit code 0 when no 5xx/transport errors were seen.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import statistics
import sys
import time
import uuid
from collections import Counter, defaultdict

import aiohttp

CATEGORIES = ["GROCERIES", "RESTAURANTS", "FUEL", "CAB_AND_AUTO", "CLOTHING",
              "MEDICINES", "INTERNET", "MOVIES_AND_EVENTS"]
MERCHANTS = ["BigBasket weekly groceries", "Swiggy dinner order", "HP petrol top-up",
             "Uber ride to office", "Myntra clothing order", "Apollo Pharmacy order"]


class Stats:
    def __init__(self):
        self.status = Counter()
        self.lat = defaultdict(list)          # endpoint -> [ms]
        self.errors = Counter()               # endpoint -> transport/5xx
        self.per_sec = Counter()              # second index -> requests
        self.started = time.time()
        self.retries = 0
        self.throttled = 0

    def record(self, endpoint, status, ms, err=None):
        self.status[status] += 1
        self.lat[endpoint].append(ms)
        if err:
            self.errors[f"{endpoint}: {err}"] += 1
        if status == 429:
            self.throttled += 1
        if status >= 500 or status == 0:
            self.errors[endpoint] += 1

    def summary(self):
        out = {"status": {str(k): v for k, v in sorted(self.status.items())},
               "retries": self.retries, "throttled_429": self.throttled,
               "errors": dict(self.errors), "endpoints": {}}
        for ep, vals in self.lat.items():
            if not vals:
                continue
            vals = sorted(vals)
            def pct(p):
                return round(vals[min(len(vals) - 1, int(len(vals) * p))], 1)
            out["endpoints"][ep] = {
                "n": len(vals), "p50": pct(0.50), "p95": pct(0.95),
                "p99": pct(0.99), "max": round(vals[-1], 1),
                "mean": round(statistics.fmean(vals), 1),
            }
        return out


class Client:
    def __init__(self, base, session, timeout):
        self.base = base.rstrip("/")
        self.session = session
        self.timeout = aiohttp.ClientTimeout(total=timeout)

    async def request(self, endpoint, method, path, headers=None, json_body=None,
                      form=None, stats=None, retry=(429, 502, 503, 504)):
        url = f"{self.base}{path}"
        started = time.perf_counter()
        status = 0
        err = None
        attempts = 0
        while True:
            try:
                kwargs = {"headers": headers, "timeout": self.timeout}
                if json_body is not None:
                    kwargs["json"] = json_body
                if form is not None:
                    kwargs["data"] = form
                async with self.session.request(method, url, **kwargs) as resp:
                    status = resp.status
                    await resp.read()
            except asyncio.TimeoutError:
                status, err = 0, "timeout"
            except aiohttp.ClientError as exc:
                status, err = 0, type(exc).__name__
            if status in retry and attempts < 2:
                if stats is not None:
                    stats.retries += 1
                attempts += 1
                await asyncio.sleep(0.25 * (2 ** attempts))
                continue
            break
        ms = (time.perf_counter() - started) * 1000.0
        if stats is not None:
            stats.record(endpoint, status, ms, err)
        return status


# ── setup ────────────────────────────────────────────────────────────────────
async def setup_user(session, base, idx, password, sem, txns):
    async with sem:
        client = Client(base, session, 30)
        # username column is varchar(30): keep the whole name short.
        tag = f"{int(time.time()) % 1000000}{uuid.uuid4().hex[:4]}"
        email = f"load.{tag}.{idx}@example.com"
        body = {"email": email, "username": f"ld{tag}_{idx}"[:30],
                "password": password, "role": "USER"}
        for _ in range(8):
            status = await client.request("setup", "POST", "/api/auth/register",
                                          json_body=body, stats=None)
            if status in (200, 201, 409):
                break
            await asyncio.sleep(0.6)
        data = {}
        for _ in range(8):
            async with session.post(f"{base}/api/auth/login",
                                    json={"email": email, "password": password}) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    break
                await resp.read()
                await asyncio.sleep(0.6)
        token = data.get("token")
        user_id = data.get("userId")
        if not token or not user_id:
            return None
        headers = {"Authorization": f"Bearer {token}", "X-User-Id": str(user_id)}
        rng = random.Random(idx + 7)
        for n in range(txns):
            amount = round(rng.uniform(80, 4500), 2)
            entry = {
                "userId": str(user_id),
                "name": rng.choice(MERCHANTS),
                "amount": amount,
                "type": "INCOME" if rng.random() < 0.12 else "EXPENSE",
                "category": rng.choice(CATEGORIES),
                "currency": "INR",
                "description": "load seed",
                "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
            }
            await client.request("seed", "POST", "/api/upsert/create",
                                 headers={**headers, "X-Idempotency-Key": str(uuid.uuid4())},
                                 json_body=entry, stats=None)
        return {"id": str(user_id), "token": token}


# ── traffic ──────────────────────────────────────────────────────────────────
# (name, weight) — reads dominate, as in a real ledger.
MIX = [
    ("entries", 0.30),
    ("summary", 0.24),
    ("pie", 0.09),
    ("comprehensive", 0.05),
    ("write", 0.20),
    ("me", 0.12),
]


def pick(rng):
    r = rng.random()
    acc = 0.0
    for name, weight in MIX:
        acc += weight
        if r <= acc:
            return name
    return MIX[-1][0]


async def one_request(client, users, rng, stats):
    user = users[rng.randrange(len(users))]
    auth = {"Authorization": f"Bearer {user['token']}", "X-User-Id": user["id"]}
    kind = pick(rng)
    uid = user["id"]
    if kind == "entries":
        await client.request("entries", "GET",
                             f"/api/upsert/entries?userId={uid}&page=0&size=20",
                             headers=auth, stats=stats)
    elif kind == "summary":
        await client.request("summary", "GET",
                             f"/api/upsert/summary?userId={uid}", headers=auth, stats=stats)
    elif kind == "pie":
        await client.request("pie", "GET",
                             f"/api/analytics/category-pie-chart?userId={uid}&tzOffsetMinutes=330",
                             headers=auth, stats=stats)
    elif kind == "comprehensive":
        await client.request("comprehensive", "GET",
                             f"/api/analytics/comprehensive?userId={uid}&tzOffsetMinutes=330",
                             headers=auth, stats=stats)
    elif kind == "write":
        entry = {"userId": uid, "name": rng.choice(MERCHANTS),
                 "amount": round(rng.uniform(50, 3000), 2), "type": "EXPENSE",
                 "category": rng.choice(CATEGORIES), "currency": "INR",
                 "description": "load", "createdAt": time.strftime("%Y-%m-%dT%H:%M:%S")}
        await client.request("write", "POST", "/api/upsert/create",
                             headers={**auth, "X-Idempotency-Key": str(uuid.uuid4())},
                             json_body=entry, stats=stats)
    elif kind == "me":
        await client.request("me", "GET", "/api/auth/me", headers=auth, stats=stats)


def resolve_samples(path):
    if not path or not os.path.exists(path):
        return []
    if os.path.isfile(path):
        return [path]
    return [os.path.join(path, n) for n in sorted(os.listdir(path))
            if os.path.isfile(os.path.join(path, n))
            and n.lower().endswith((".jpg", ".jpeg", ".png", ".pdf"))]


async def ocr_loop(client, users, samples, sem, stats, stop):
    rng = random.Random(99)
    while not stop.is_set():
        user = users[rng.randrange(len(users))]
        sample = rng.choice(samples)
        with open(sample, "rb") as fh:
            payload = fh.read()
        form = aiohttp.FormData()
        form.add_field("file", payload, filename=os.path.basename(sample),
                       content_type="image/jpeg")
        await client.request("ocr", "POST", f"/api/bill/process/{user['id']}",
                             headers={"Authorization": f"Bearer {user['token']}"},
                             form=form, stats=stats)
        await asyncio.sleep(0.5)


async def run_stage(client, users, rate, seconds, stats, sample):
    stage_stats = Stats()
    stop = asyncio.Event()
    sem = asyncio.Semaphore(4000)
    ocr_task = None
    if sample:
        ocr_task = asyncio.create_task(
            ocr_loop(client, users, sample, sem, stage_stats, stop))
    interval = 1.0 / rate
    loop = asyncio.get_running_loop()
    end = loop.time() + seconds
    next_at = loop.time()
    tasks = set()
    rng = random.Random(int(rate) + 3)

    async def guarded():
        async with sem:
            await one_request(client, users, rng, stage_stats)

    while loop.time() < end:
        now = loop.time()
        if now < next_at:
            await asyncio.sleep(next_at - now)
        else:
            next_at = now
        task = asyncio.create_task(guarded())
        tasks.add(task)
        task.add_done_callback(tasks.discard)
        next_at += interval
        if len(tasks) > 6000:            # back-pressure when the server stalls
            await asyncio.gather(*list(tasks))
    if tasks:
        await asyncio.wait(tasks)
    stop.set()
    if ocr_task:
        await ocr_task
    return stage_stats


def percentile(vals, p):
    if not vals:
        return 0.0
    vals = sorted(vals)
    return round(vals[min(len(vals) - 1, int(len(vals) * p))], 1)


async def main_async(args):
    rng = random.Random(args.seed)
    users = None
    if args.state and os.path.exists(args.state):
        with open(args.state) as fh:
            users = json.load(fh)
        print(f"loaded {len(users)} users from {args.state}")

    samples = resolve_samples(args.samples)
    print(f"OCR samples: {len(samples)}")

    connector = aiohttp.TCPConnector(limit=4000, limit_per_host=4000,
                                     ttl_dns_cache=300, enable_cleanup_closed=True)
    async with aiohttp.ClientSession(connector=connector) as session:
        client = Client(args.base_url, session, args.timeout)

        if not users:
            setup_stats = Stats()
            sem = asyncio.Semaphore(args.setup_concurrency)
            print(f"setting up {args.users} users ({args.txns} txns each) ...")
            t0 = time.time()
            results = await asyncio.gather(*[
                setup_user(session, args.base_url, i, args.password, sem, args.txns)
                for i in range(args.users)])
            users = [u for u in results if u]
            print(f"setup: {len(users)}/{args.users} ready in {time.time()-t0:.0f}s")
            if not users:
                print("no users could be created", file=sys.stderr)
                return 2
            if args.state:
                with open(args.state, "w") as fh:
                    json.dump(users, fh)

        if args.setup_only:
            return 0

        report = {"gateway": args.base_url, "users": len(users), "stages": []}
        totals = Stats()
        for rate in args.stages:
            print(f"\n--- {rate:>5} req/s for {args.stage_seconds}s ---", flush=True)
            stats = await run_stage(client, users, rate, args.stage_seconds, totals,
                                    samples)
            n = sum(stats.status.values())
            achieved = n / args.stage_seconds
            row = {"target_rate": rate, "achieved_rate": round(achieved, 1),
                   "duration_s": args.stage_seconds, **stats.summary()}
            report["stages"].append(row)
            ok = sum(v for k, v in stats.status.items() if 200 <= k < 400)
            print(f"    achieved {achieved:.0f} req/s  ok={ok}  "
                  f"4xx={sum(v for k,v in stats.status.items() if 400<=k<500)} "
                  f"5xx={sum(v for k,v in stats.status.items() if k>=500)} "
                  f"throttled={stats.throttled} retries={stats.retries}", flush=True)
            for ep, m in row["endpoints"].items():
                print(f"      {ep:<14} n={m['n']:<7} p50={m['p50']:>7}ms "
                      f"p95={m['p95']:>7}ms p99={m['p99']:>7}ms max={m['max']:>8}ms",
                      flush=True)
            await asyncio.sleep(5)          # let queues drain between stages

        report["totals"] = totals.summary()
        failures = sum(v for k, v in totals.status.items() if k >= 500) + totals.status.get(0, 0)
        report["verdict"] = "PASS" if failures == 0 else "FAIL"
        if args.out:
            with open(args.out, "w") as fh:
                json.dump(report, fh, indent=2)
            print(f"\nreport written to {args.out}")
        return 0 if failures == 0 else 1


def parse_args(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description="Open-loop rate-driven load test")
    p.add_argument("--base-url", default=os.environ.get("GATEWAY_URL",
                                                        "http://localhost:18090"))
    p.add_argument("--users", type=int, default=200)
    p.add_argument("--txns", type=int, default=15, help="seed transactions per user")
    p.add_argument("--password", default="GoldenTest!")
    p.add_argument("--setup-concurrency", type=int, default=4)
    p.add_argument("--setup-only", action="store_true")
    p.add_argument("--state", default="/tmp/loadgen-state.json")
    p.add_argument("--stages", type=lambda s: [int(x) for x in s.split(",")],
                   default=[100, 300, 600, 1000])
    p.add_argument("--stage-seconds", type=float, default=60.0)
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument("--seed", type=int, default=11)
    p.add_argument("--samples", default=os.path.join(here, "large-receipt-image-dataset-SRD"))
    p.add_argument("--out", default=os.path.join(here, "loadgen-report.json"))
    return p.parse_args(argv)


if __name__ == "__main__":
    sys.exit(asyncio.run(main_async(parse_args(sys.argv[1:]))) )
