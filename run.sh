#!/bin/bash
# run.sh — Control script for starting, testing, cleaning, and shutting down the stack.
# Usage:
#   ./run.sh --start              → start existing stack containers
#   ./run.sh --freshStart         → build + start stack from scratch
#   ./run.sh --test               → run integration tests
#   ./run.sh --clean              → wipe ClickHouse + Postgres data (asks for confirmation)
#   ./run.sh --shutdown           → shut down the Docker stack
#   ./run.sh --stop               → stop stack without removing containers
#   ./run.sh --restart <service>  → rebuild + restart a specific service
#   ./run.sh --restart-kafka      → restart only Zookeeper + Kafka (keeps everything else running)
#   ./run.sh --all                → start, test, then shutdown sequentially (default if no flag passed)
set -euo pipefail

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

cd "$(dirname "$0")"

# ─── Usage ────────────────────────────────────────────────────────────────────
show_usage() {
    echo -e "Usage: $0 [flag] [options]"
    echo ""
    echo "Flags:"
    echo "  --freshStart              Builds and starts the stack from scratch using start.sh"
    echo "  --start                   Starts the existing stack containers"
    echo "  --test                    Runs the integration test suite"
    echo "  --clean                   Wipes all data in ClickHouse + Postgres with confirmation (keeps schemas/users)"
    echo "  --shutdown                Stops and shuts down the Docker compose stack (--remove-orphans)"
    echo "  --stop                    Stops the stack without removing containers"
    echo "  --all                     Starts the stack, runs all integration tests, then stops the stack automatically"
    echo "  --restart <service>       Rebuilds and restarts a specific service"
    echo "                            (Options: api-gateway, auth-service, upsert-service, analytics-service,"
    echo "                                      ocr-parser-service, kafka, zookeeper)"
    echo "  --restart-kafka           Restarts only Zookeeper + Kafka in the correct order (keeps everything else running)"
    echo ""
    echo "If no flag is passed, defaults to --all."
}

# ─── Stack lifecycle ──────────────────────────────────────────────────────────
fresh_start_stack() {
    echo -e "\n${CYAN}══ FRESH START — BUILDING & STARTING STACK ══${NC}"
    ./start.sh
}

start_stack() {
    echo -e "\n${CYAN}══ STARTING STACK ══${NC}"
    docker compose start
    echo -e "  ${GREEN}✔ Stack started successfully${NC}"
}

shutdown_stack() {
    echo -e "\n${CYAN}══ SHUTTING DOWN STACK ══${NC}"
    docker compose down --remove-orphans
    echo -e "  ${GREEN}✔ Stack stopped successfully${NC}"
}

stop_stack() {
    echo -e "\n${CYAN}══ STOPPING STACK ══${NC}"
    docker compose stop
    echo -e "  ${GREEN}✔ Stack stopped successfully${NC}"
}

# ─── Tests & cleanup ─────────────────────────────────────────────────────────
run_tests() {
    echo -e "\n${CYAN}══ RUNNING INTEGRATION TESTS ══${NC}"
    ./test.sh all
}

clean_data() {
    echo -e "\n${CYAN}══ CLEANING DATA ══${NC}"
    ./truncate-all.sh
}

# ─── Service restart ──────────────────────────────────────────────────────────
restart_service() {
    local service=$1
    local valid_services=(api-gateway auth-service upsert-service analytics-service ocr-parser-service kafka zookeeper)

    # Validate service name
    local valid=false
    for s in "${valid_services[@]}"; do
        [[ "$s" == "$service" ]] && valid=true && break
    done
    if [[ "$valid" == false ]]; then
        echo -e "${RED}Error: Unknown service '$service'${NC}"
        echo -e "Valid options: ${valid_services[*]}"
        exit 1
    fi

    # Zookeeper and Kafka need ordered handling
    if [[ "$service" == "zookeeper" ]]; then
        echo -e "\n${YELLOW}⚠ Restarting Zookeeper will also restart Kafka (depends on it).${NC}"
        echo -e "  Use ${CYAN}./run.sh --restart-kafka${NC} to restart both in the correct order."
        echo -e "  Continuing with Zookeeper restart only...\n"
    fi

    echo -e "\n${CYAN}══ REBUILDING & RESTARTING $service ══${NC}"
    docker compose up -d --build "$service"
    echo -e "  ${GREEN}✔ $service restarted successfully${NC}"
}

restart_kafka_stack() {
    echo -e "\n${CYAN}══ RESTARTING ZOOKEEPER + KAFKA ══${NC}"
    ./start.sh --restart-kafka
}

# ─── Argument parsing ─────────────────────────────────────────────────────────
MODE="${1:-}"

case "$MODE" in
    --freshStart|freshStart)
        fresh_start_stack
        ;;
    --start|start)
        start_stack
        ;;
    --test|test)
        run_tests
        ;;
    --clean|clean)
        clean_data
        ;;
    --shutdown|shutdown|down)
        shutdown_stack
        ;;
    --stop|stop)
        stop_stack
        ;;
    --restart|restart)
        if [[ -z "${2:-}" ]]; then
            echo -e "${RED}Error: Service name required (e.g., ./run.sh --restart api-gateway)${NC}"
            show_usage
            exit 1
        fi
        restart_service "$2"
        ;;
    --restart-kafka|restart-kafka)
        restart_kafka_stack
        ;;
    --all|all|"")
        start_stack
        set +e
        run_tests
        TEST_EXIT_CODE=$?
        set -e
        stop_stack
        exit $TEST_EXIT_CODE
        ;;
    --help|-h|help)
        show_usage
        exit 0
        ;;
    *)
        echo -e "${RED}Error: Unknown flag '$MODE'${NC}"
        show_usage
        exit 1
        ;;
esac