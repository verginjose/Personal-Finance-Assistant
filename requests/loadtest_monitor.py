#!/usr/bin/env python3
"""Sample per-pod CPU/memory from in-cluster Prometheus while a load test runs.

    python3 requests/loadtest_monitor.py --prom-url http://localhost:19090 \
        --out /tmp/loadtest-pods.csv --interval 5

Writes one CSV row per pod per sample, plus a synthetic `__node__` row carrying
whole-host CPU and available memory so client-side saturation is visible.
Stop with Ctrl-C; the file is flushed every sample.
"""

from __future__ import annotations

import argparse
import csv
import sys
import time
import urllib.parse
import urllib.request

CPU_Q = ('sum by (pod) (rate(container_cpu_usage_seconds_total'
         '{namespace="pfa",container!="",container!="POD"}[30s]))')
MEM_Q = ('sum by (pod) (container_memory_working_set_bytes'
         '{namespace="pfa",container!="",container!="POD"})')
NODE_CPU_Q = '1 - avg(rate(node_cpu_seconds_total{mode="idle"}[30s]))'
NODE_MEM_Q = 'node_memory_MemAvailable_bytes'
GW_RPS_Q = ('sum(rate(http_server_requests_seconds_count'
            '{application="api-gateway"}[15s]))')


def query(base, q):
    url = f"{base}/api/v1/query?" + urllib.parse.urlencode({"query": q})
    with urllib.request.urlopen(url, timeout=10) as resp:
        data = __import__("json").loads(resp.read())
    return data.get("data", {}).get("result", [])


def scalar(results):
    if not results:
        return 0.0
    try:
        return float(results[0]["value"][1])
    except (KeyError, IndexError, ValueError):
        return 0.0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--prom-url", default="http://localhost:19090")
    p.add_argument("--out", default="/tmp/loadtest-pods.csv")
    p.add_argument("--interval", type=float, default=5.0)
    p.add_argument("--namespace", default="pfa")
    args = p.parse_args()

    with open(args.out, "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["ts", "iso", "pod", "cpu_cores", "mem_mib",
                         "node_cpu_pct", "node_mem_avail_mib", "gateway_rps"])
        print(f"monitoring -> {args.out} (Ctrl-C to stop)")
        try:
            while True:
                ts = time.time()
                iso = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ts))
                node_cpu = scalar(query(args.prom_url, NODE_CPU_Q)) * 100
                node_mem = scalar(query(args.prom_url, NODE_MEM_Q)) / 2**20
                rps = scalar(query(args.prom_url, GW_RPS_Q))
                cpu = {r["metric"].get("pod", "?"): float(r["value"][1])
                       for r in query(args.prom_url, CPU_Q)}
                mem = {r["metric"].get("pod", "?"): float(r["value"][1])
                       for r in query(args.prom_url, MEM_Q)}
                for pod in sorted(set(cpu) | set(mem)):
                    writer.writerow([f"{ts:.0f}", iso, pod,
                                     f"{cpu.get(pod, 0):.3f}",
                                     f"{mem.get(pod, 0)/2**20:.1f}",
                                     f"{node_cpu:.1f}", f"{node_mem:.0f}",
                                     f"{rps:.1f}"])
                writer.writerow([f"{ts:.0f}", iso, "__node__",
                                 f"{node_cpu/100*16:.2f}", "", f"{node_cpu:.1f}",
                                 f"{node_mem:.0f}", f"{rps:.1f}"])
                fh.flush()
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print("\nstopped")


if __name__ == "__main__":
    sys.exit(main())
