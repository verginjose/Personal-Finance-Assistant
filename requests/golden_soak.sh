#!/usr/bin/env bash
# Golden soak: one full golden scenario every 10 minutes for 5 hours.
# Keeps the gateway port-forward alive between runs; a dead tunnel costs a run.
set -u

cd "$(dirname "$0")"
STAMP=$(date +%Y%m%d-%H%M%S)
LOG="$PWD/golden-soak-$STAMP.log"
GATEWAY=http://localhost:18090
RUNS=30
INTERVAL=600

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

ensure_gateway() {
  for _ in 1 2 3; do
    if curl -fsS -m 5 "$GATEWAY/actuator/health" >/dev/null 2>&1; then return 0; fi
    log "gateway not reachable; (re)starting port-forward"
    pkill -f 'port-forward -n pfa svc/api-gateway 18090:8080' 2>/dev/null || true
    nohup kubectl port-forward -n pfa svc/api-gateway 18090:8080 >>"$LOG" 2>&1 &
    for _ in $(seq 1 30); do
      sleep 1
      curl -fsS -m 2 "$GATEWAY/actuator/health" >/dev/null 2>&1 && return 0
    done
  done
  return 1
}

log "golden soak started: $RUNS runs, every ${INTERVAL}s, gateway $GATEWAY"
for n in $(seq 1 "$RUNS"); do
  START=$(date +%s)
  REPORT="golden-report.soak-$STAMP-run$(printf '%02d' "$n").json"
  log "=== run $n/$RUNS ==="
  if ensure_gateway; then
    python3 -u run_golden_test.py --users 100 --groups 10 --think-time 1.0 \
      --bills 10 --base-url "$GATEWAY" --out "$REPORT" >> "$LOG" 2>&1
    log "run $n exit=$? report=$REPORT"
  else
    log "run $n SKIPPED: gateway unreachable"
  fi
  python3 - "$REPORT" >> "$LOG" 2>&1 <<'PY' || true
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    t = d.get('totals', {})
    print("SUMMARY verdict=%s requests=%s 2xx=%s 4xx=%s 5xx=%s duration=%ss "
          "retries=%s breaker503=%s throttled429=%s"
          % (d.get('verdict'), t.get('n'), t.get('2xx'), t.get('4xx'),
             t.get('5xx'), d.get('duration_s'), d.get('retries'),
             d.get('breaker_503'), d.get('throttled_429')))
except Exception as e:
    print("SUMMARY read failed:", e)
PY
  END=$(date +%s)
  SLEEP=$(( INTERVAL - (END - START) ))
  [ "$SLEEP" -gt 0 ] && sleep "$SLEEP"
done
log "golden soak complete"
