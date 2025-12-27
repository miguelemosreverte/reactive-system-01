#!/bin/bash
# Quick Smoke Test for Reactive System
# Tests each component module independently

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

DURATION=${1:-5}
PASSED=0
FAILED=0
RESULTS=()

echo "╔══════════════════════════════════════════════════════════════════╗"
echo "║           REACTIVE SYSTEM - QUICK SMOKE TEST                     ║"
echo "╠══════════════════════════════════════════════════════════════════╣"
echo "║  Duration per test: ${DURATION}s                                          ║"
echo "╚══════════════════════════════════════════════════════════════════╝"
echo ""

run_test() {
    local name="$1"
    local cmd="$2"

    echo -n "  Testing $name... "

    if output=$(eval "$cmd" 2>&1); then
        # Extract throughput if available
        throughput=$(echo "$output" | grep -oE '[0-9,]+\s*(req/s|ops/sec)' | head -1 || echo "")
        if [ -n "$throughput" ]; then
            echo -e "${GREEN}PASS${NC} ($throughput)"
            RESULTS+=("$name: $throughput")
        else
            echo -e "${GREEN}PASS${NC}"
        fi
        ((PASSED++))
        return 0
    else
        echo -e "${RED}FAIL${NC}"
        ((FAILED++))
        return 1
    fi
}

# Test 1: HTTP Server (Rocket)
echo "▸ HTTP Server Module"
run_test "RocketHttpServer" "docker run --rm -v '$PROJECT_ROOT':/work -w /work maven:3.9-eclipse-temurin-21 java -cp 'platform/target/classes:platform/target/dependency/*' com.reactive.platform.benchmark.UnifiedHttpBenchmark ROCKET $DURATION 100 /tmp 2>&1 | grep -q 'req/s' && echo 'OK'"

# Test 2: Gateway (HTTP + Kafka)
echo ""
echo "▸ Kafka Publisher Module (Gateway)"
NETWORK=$(docker network ls --format '{{.Name}}' | grep reactive | head -1)
if [ -n "$NETWORK" ]; then
    run_test "MicrobatchingGateway" "docker run --rm --network $NETWORK -v '$PROJECT_ROOT':/work -w /work -e KAFKA_BOOTSTRAP_SERVERS=kafka:29092 maven:3.9-eclipse-temurin-21 java -cp 'platform/target/classes:platform/target/dependency/*' com.reactive.platform.gateway.microbatch.GatewayComparisonBenchmark MICROBATCH $DURATION 100 kafka:29092 2>&1 | grep -q 'ops/sec' && echo 'OK'"
else
    echo -e "  ${YELLOW}SKIP${NC} - No reactive network found (start docker-compose first)"
fi

# Test 3: Multi-Module Build (with dependency resolution)
echo ""
echo "▸ Multi-Module Build"
run_test "all-modules" "docker run --rm -v '$PROJECT_ROOT':/work -w /work maven:3.9-eclipse-temurin-21 mvn compile -pl platform/base,platform/http-server,platform/kafka,platform/flink,platform/integration -q 2>&1"

# Summary
echo ""
echo "══════════════════════════════════════════════════════════════════"
echo -e "  PASSED: ${GREEN}$PASSED${NC}  FAILED: ${RED}$FAILED${NC}"
echo "══════════════════════════════════════════════════════════════════"

if [ ${#RESULTS[@]} -gt 0 ]; then
    echo ""
    echo "  Performance Results:"
    for r in "${RESULTS[@]}"; do
        echo "    • $r"
    done
fi

echo ""
if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed.${NC}"
    exit 1
fi
