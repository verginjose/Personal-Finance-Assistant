#!/bin/bash
# run.sh — Control script for starting, testing, cleaning, and shutting down the stack.
# Usage:
#   ./run.sh --start      → build + start stack and wait for health
#   ./run.sh --test       → run integration tests
#   ./run.sh --clean      → wipe ClickHouse + Postgres data (asks for confirmation)
#   ./run.sh --shutdown   → shut down the Docker stack
#   ./run.sh --all        → start, test, then shutdown sequentially (default if no flag passed)
set -euo pipefail

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

cd "$(dirname "$0")"

show_usage() {
    echo -e "Usage: $0 [flag] [options]"
    echo ""
    echo "Flags:"
    echo "  --freshStart         Builds and starts the stack from scratch using start.sh"
    echo "  --start              Starts the existing stack containers"
    echo "  --test               Runs the integration test suite"
    echo "  --clean              Wipes all data in ClickHouse + Postgres with confirmation (keeps schemas/users)"
    echo "  --shutdown           Stops and shuts down the Docker compose stack (--remove-orphans)"
    echo "  --stop               Stops the stack without removing containers"
    echo "  --all                Starts the stack, runs all integration tests, and then stops the stack automatically"
    echo "  --restart <service>  Rebuilds and restarts a specific service"
    echo "                       (Options: api-gateway, auth-service, upsert-service, analytics-service, ocr-parser-service)"
    echo ""
    echo "If no flag is passed, defaults to --all."
}

fresh_stack() {
    echo -e "\n${CYAN}══ STARTING STACK ══${NC}"
    ./start.sh
}

start_stack() {
    echo -e "\n${CYAN}══ STARTING STACK ══${NC}"
    docker compose start
    echo -e "  ${GREEN}✔ Stack started successfully${NC}"
}

run_tests() {
    echo -e "\n${CYAN}══ RUNNING INTEGRATION TESTS ══${NC}"
    ./test.sh all
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

restart_service() {
    local service=$1
    echo -e "\n${CYAN}══ REBUILDING & RESTARTING $service ══${NC}"
    docker compose up -d --build "$service"
    echo -e "  ${GREEN}✔ $service restarted successfully${NC}"
}

MODE="${1:-}"

case "$MODE" in
    --freshStart|freshStart)
        fresh_stack
        ;;
    --test|test)
        run_tests
        ;;
    --clean|clean)
        ./truncate-all.sh
        ;;
    --shutdown|shutdown|down)
        shutdown_stack
        ;;
    --stop|stop)
        stop_stack
        ;;
    --restart|restart)
        if [ -z "${2:-}" ]; then
            echo -e "${RED}Error: Service name required for restart (e.g., ./run.sh --restart api-gateway)${NC}"
            show_usage
            exit 1
        fi
        restart_service "$2"
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
    --start|start)
        start_stack
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
