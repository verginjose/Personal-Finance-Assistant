#!/bin/bash
# start.sh — Single command to start the Finance Assistant stack from scratch.
# Usage:  ./start.sh          → clean build + start
#         ./start.sh --clean  → nuke volumes (wipes DB) then build + start
set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
cd "$(dirname "$0")"

echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║       Finance Assistant — Start Stack        ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"

# ─── Optional: nuke all volumes (fresh DB) ────────────────────────────────────
if [[ "${1:-}" == "--clean" ]]; then
    echo -e "\n${RED}[--clean] Removing all volumes (fresh database)...${NC}"
    docker compose down --volumes --remove-orphans 2>/dev/null || true
else
    docker compose down --remove-orphans 2>/dev/null || true
fi

# ─── Build & start ────────────────────────────────────────────────────────────
echo -e "\n${YELLOW}[1/3] Building images and starting containers...${NC}"
docker compose up -d --build

# ─── Wait for core services ───────────────────────────────────────────────────
echo -e "\n${YELLOW}[2/3] Waiting for services to become healthy...${NC}"

wait_healthy() {
    local svc=$1 max=60 n=0
    while [[ $n -lt $max ]]; do
        status=$(docker inspect --format='{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "none")
        [[ "$status" == "healthy" || "$status" == "none" ]] && return 0
        echo -e "  ${YELLOW}⏳ $svc: $status${NC}"
        sleep 3; (( n+=3 ))
    done
    echo -e "  ${RED}✗ $svc did not become healthy in ${max}s${NC}"
}

wait_healthy postgres-db
wait_healthy redis
echo -e "  ${GREEN}✔ postgres-db and redis healthy${NC}"

# Wait for Loki to be ready
echo -n "  Waiting for Loki..."
for i in $(seq 1 20); do
    if curl -sf http://localhost:3100/ready > /dev/null 2>&1; then
        echo -e " ${GREEN}ready${NC}"; break
    fi
    sleep 2; echo -n "."
done

# Wait for Grafana
echo -n "  Waiting for Grafana..."
for i in $(seq 1 20); do
    if curl -sf http://localhost:3000/api/health > /dev/null 2>&1; then
        echo -e " ${GREEN}ready${NC}"; break
    fi
    sleep 2; echo -n "."
done

# ─── Status report ────────────────────────────────────────────────────────────
echo -e "\n${YELLOW}[3/3] Service status:${NC}"
echo ""

SERVICES=(api-gateway auth-service upsert-service analytics-service ocr-parser-service)
for svc in "${SERVICES[@]}"; do
    running=$(docker inspect --format='{{.State.Running}}' "$svc" 2>/dev/null || echo "false")
    if [[ "$running" == "true" ]]; then
        echo -e "  ${GREEN}✔${NC} $svc"
    else
        echo -e "  ${RED}✗${NC} $svc  ← not running"
    fi
done

echo ""
INFRA=(postgres-db redis clickhouse loki promtail prometheus grafana)
for svc in "${INFRA[@]}"; do
    running=$(docker inspect --format='{{.State.Running}}' "$svc" 2>/dev/null || echo "false")
    if [[ "$running" == "true" ]]; then
        echo -e "  ${GREEN}✔${NC} $svc"
    else
        echo -e "  ${RED}✗${NC} $svc  ← not running"
    fi
done

# ─── Verify Loki is receiving logs ───────────────────────────────────────────
echo ""
echo -e "${YELLOW}Verifying Loki log ingestion...${NC}"
sleep 5
LABELS=$(curl -sf "http://localhost:3100/loki/api/v1/labels" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(', '.join(d.get('data', [])))" 2>/dev/null || echo "not ready yet")
echo -e "  Loki labels: ${GREEN}${LABELS}${NC}"

echo ""
echo -e "${GREEN}═══════════════════════════════════════════════${NC}"
echo -e "${GREEN}  Stack is UP                                  ${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════${NC}"
echo -e ""
echo -e "  ${CYAN}Grafana  →  http://localhost:3000${NC}  (admin / admin)"
echo -e "  ${CYAN}Prometheus  →  http://localhost:9090${NC}"
echo -e "  ${CYAN}Loki ready  →  http://localhost:3100/ready${NC}"
echo -e ""
echo -e "  ${YELLOW}Dashboard:${NC}  Dashboards → Finance Assistant"
echo -e "             Pick a service, type search term, see live logs"
echo -e ""
echo -e "  ${YELLOW}To wipe DB and start fresh:${NC}  ./start.sh --clean"
