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
    echo -e "Usage: $0 [flag]"
    echo ""
    echo "Flags:"
    echo "  --start      Builds and starts the stack, waiting for all services to become healthy"
    echo "  --test       Runs the integration test suite"
    echo "  --clean      Wipes all data in ClickHouse + Postgres with confirmation (keeps schemas/users)"
    echo "  --shutdown   Stops and shuts down the Docker compose stack"
    echo "  --all        Starts the stack, runs all integration tests, and then shuts down the stack automatically"
    echo ""
    echo "If no flag is passed, defaults to --all."
}

start_stack() {
    echo -e "\n${CYAN}══ STARTING STACK ══${NC}"
    ./start.sh
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

MODE="${1:-}"

case "$MODE" in
    --start|start)
        start_stack
        ;;
    --test|test)
        run_tests
        ;;
    --clean|clean)
        ./truncate-all.sh
        ;;
    --shutdown|shutdown|stop|down)
        shutdown_stack
        ;;
    --all|all|"")
        start_stack
        set +e
        run_tests
        TEST_EXIT_CODE=$?
        set -e
        shutdown_stack
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
