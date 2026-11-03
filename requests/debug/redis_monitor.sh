#!/bin/bash
# Capture every Redis command (MONITOR) for correlation with the test timeline.
# Leaves the capture running in the foreground; Ctrl-C / kill when done.
#
# Usage: requests/debug/redis_monitor.sh /tmp/redis-monitor.log
set -u
out="${1:-/tmp/redis-monitor.log}"
ns="${2:-pfa}"
kubectl -n "$ns" exec redis-0 -- sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli MONITOR' > "$out" 2>&1
