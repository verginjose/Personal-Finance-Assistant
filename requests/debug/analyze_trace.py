#!/usr/bin/env python3
"""Correlate GOLDEN_TRACE events with a Redis MONITOR capture, per group.

The golden test writes timestamped markers when GOLDEN_TRACE is set:
    GROUP / BASELINE / CANARY_POST / CANARY_CREATED / BALANCES_CHANGED /
    CANARY_DELETED / BALANCES_RESTORED
This script merges them with every Redis command touching
`group-balances:<id>` or `group-keys:<id>` so cache evictions, refills and
token writes line up with the test's own timeline.

Usage:
    GOLDEN_TRACE=/tmp/golden-trace.log python3 requests/run_golden_test.py ...
    requests/debug/redis_monitor.sh /tmp/redis-monitor.log   # run concurrently
    python3 requests/debug/analyze_trace.py /tmp/golden-trace.log /tmp/redis-monitor.log
"""
import re
import sys

trace_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/golden-trace.log"
mon_path = sys.argv[2] if len(sys.argv) > 2 else "/tmp/redis-monitor.log"

trace = []
for line in open(trace_path, errors="replace"):
    parts = line.split()
    if len(parts) >= 2:
        try:
            trace.append((float(parts[0]), " ".join(parts[1:])))
        except ValueError:
            pass
trace.sort()

groups = {}
for ts, msg in trace:
    p = msg.split()
    if p[0] == "GROUP":
        groups[p[2]] = {"index": p[1], "start": ts, "events": [(ts, msg)]}
    elif len(p) >= 2 and p[1] in groups:
        groups[p[1]]["events"].append((ts, msg))

print(f"groups: {[(g, v['index']) for g, v in groups.items()]}")
print(f"trace events: {len(trace)}")

mon_re = re.compile(r'^([\d.]+) \[[^\]]+\] (.*)$')
cmds = {}
for line in open(mon_path, errors="replace"):
    m = mon_re.match(line.strip())
    if not m:
        continue
    ts = float(m.group(1))
    cmds.setdefault(ts, []).append(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2)))

for gid, info in sorted(groups.items(), key=lambda kv: kv[1]["start"]):
    start = info["start"]
    print(f"\n=== group {gid} (index {info['index']}) ===")
    merged = [(ts, "TRACE " + msg) for ts, msg in info["events"]]
    for ts, arglists in sorted(cmds.items()):
        for args in arglists:
            joined = " ".join(args)
            if f"group-balances:{gid}" in joined or f"group-keys:{gid}" in joined:
                merged.append((ts, "REDIS " + joined))
    merged.sort()
    for ts, what in merged:
        print(f"  {ts - start:8.3f}s  {what[:150]}")
