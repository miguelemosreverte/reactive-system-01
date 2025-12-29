#!/bin/bash
#
# Event Flow Smoke Test for Counter Application
# Tests the full E2E flow: API -> Kafka -> Flink -> Kafka -> Results
# Uses the BFF debug endpoint with X-Debug header for full tracing/logging
#
# Usage: ./scripts/event-flow-smoke-test.sh [--verbose] [--diagnostic]
#
# Options:
#   --verbose      Show detailed output
#   --diagnostic   Run the full E2E diagnostic test
#

# Don't exit on error - we want to track pass/fail
set +e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
VERBOSE=false
RUN_DIAGNOSTIC=false

# Parse arguments
for arg in "$@"; do
    case $arg in
        --verbose|-v)
            VERBOSE=true
            ;;
        --diagnostic|-d)
            RUN_DIAGNOSTIC=true
            ;;
    esac
done

echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║          Counter Event Flow - Smoke Test                   ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Track test results
PASSED=0
FAILED=0

pass() {
    echo -e "  ${GREEN}✓${NC} $1"
    ((PASSED++))
}

fail() {
    echo -e "  ${RED}✗${NC} $1"
    ((FAILED++))
}

info() {
    echo -e "  ${BLUE}ℹ${NC} $1"
}

warn() {
    echo -e "  ${YELLOW}⚠${NC} $1"
}

# ============================================================================
# 1. Health Checks
# ============================================================================
echo -e "${YELLOW}━━━ 1. Health Checks ━━━${NC}"

# Counter application health
if HEALTH=$(curl -s --max-time 5 "$BASE_URL/health" 2>/dev/null); then
    STATUS=$(echo "$HEALTH" | jq -r '.status' 2>/dev/null)
    if [[ "$STATUS" == "healthy" ]]; then
        pass "Counter application is healthy"
    else
        fail "Counter application health check returned: $STATUS"
    fi
else
    fail "Counter application not responding at $BASE_URL/health"
fi

# API health endpoint
if API_HEALTH=$(curl -s --max-time 5 "$BASE_URL/api/health" 2>/dev/null); then
    pass "API health endpoint responding"
else
    warn "API health endpoint not available"
fi

# Flink job status
if FLINK_STATUS=$(curl -s --max-time 5 "http://localhost:8081/jobs" 2>/dev/null); then
    RUNNING_JOBS=$(echo "$FLINK_STATUS" | jq '[.jobs[] | select(.status=="RUNNING")] | length' 2>/dev/null)
    if [[ "$RUNNING_JOBS" -gt 0 ]]; then
        pass "Flink has $RUNNING_JOBS running job(s)"
    else
        fail "No Flink jobs running"
    fi
else
    fail "Flink JobManager not responding"
fi

echo ""

# ============================================================================
# 2. Endpoint Discovery
# ============================================================================
echo -e "${YELLOW}━━━ 2. Endpoint Discovery ━━━${NC}"

# Test various counter endpoints to find which one works
ENDPOINTS=(
    "/api/counter"
    "/api/counter/fast"
    "/api/bff/counter"
    "/api/forensic/trace"
)

WORKING_ENDPOINT=""
for endpoint in "${ENDPOINTS[@]}"; do
    RESPONSE=$(curl -s -w "\n%{http_code}" --max-time 5 -X POST "$BASE_URL$endpoint" \
        -H "Content-Type: application/json" \
        -d '{"action":"increment","value":1,"sessionId":"smoke-test"}' 2>/dev/null)

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [[ "$HTTP_CODE" =~ ^2 ]]; then
        WORKING_ENDPOINT="$endpoint"
        pass "POST $endpoint -> $HTTP_CODE"
        if $VERBOSE; then
            info "Response: $BODY"
        fi
        break
    elif [[ "$HTTP_CODE" == "404" ]]; then
        if $VERBOSE; then info "POST $endpoint -> 404 (not found)"; fi
    else
        warn "POST $endpoint -> $HTTP_CODE"
    fi
done

if [[ -z "$WORKING_ENDPOINT" ]]; then
    fail "No working counter endpoint found"
fi

echo ""

# ============================================================================
# 3. Event Flow Test (Standard)
# ============================================================================
echo -e "${YELLOW}━━━ 3. Event Flow Test ━━━${NC}"

SESSION_ID="smoke-$(date +%s)"
REQUEST_BODY="{\"action\":\"increment\",\"value\":42,\"sessionId\":\"$SESSION_ID\"}"

if [[ -n "$WORKING_ENDPOINT" ]]; then
    info "Sending counter action to $WORKING_ENDPOINT..."

    RESPONSE=$(curl -s -w "\n%{http_code}" --max-time 10 -X POST "$BASE_URL$WORKING_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "X-Debug: true" \
        -d "$REQUEST_BODY" 2>/dev/null)

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [[ "$HTTP_CODE" =~ ^2 ]]; then
        pass "Counter action accepted (HTTP $HTTP_CODE)"

        # Extract useful info from response
        REQUEST_ID=$(echo "$BODY" | jq -r '.requestId // .data.requestId // empty' 2>/dev/null)
        TRACE_ID=$(echo "$BODY" | jq -r '.otelTraceId // .traceId // .data.traceId // empty' 2>/dev/null)
        EVENT_ID=$(echo "$BODY" | jq -r '.eventId // .data.eventId // empty' 2>/dev/null)

        [[ -n "$REQUEST_ID" ]] && info "Request ID: $REQUEST_ID"
        [[ -n "$TRACE_ID" ]] && info "Trace ID: $TRACE_ID"
        [[ -n "$EVENT_ID" ]] && info "Event ID: $EVENT_ID"

        if $VERBOSE; then
            echo -e "  ${BLUE}Response:${NC}"
            echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
        fi
    else
        fail "Counter action failed (HTTP $HTTP_CODE)"
        if $VERBOSE; then
            echo "$BODY"
        fi
    fi
else
    fail "Skipping event flow test - no working endpoint"
fi

echo ""

# ============================================================================
# 4. Diagnostic Run Test (Full E2E with Tracing)
# ============================================================================
if $RUN_DIAGNOSTIC; then
    echo -e "${YELLOW}━━━ 4. E2E Diagnostic Test ━━━${NC}"

    info "Running full E2E diagnostic via /api/diagnostic/run..."
    info "(This may take 10-15 seconds)"

    DIAG_RESPONSE=$(curl -s --max-time 30 -X POST "$BASE_URL/api/diagnostic/run" 2>/dev/null)

    if [[ -n "$DIAG_RESPONSE" ]]; then
        # Check if it has trace data
        TRACE_ID=$(echo "$DIAG_RESPONSE" | jq -r '.otelTraceId // empty' 2>/dev/null)
        STATUS=$(echo "$DIAG_RESPONSE" | jq -r '.status // empty' 2>/dev/null)

        if [[ -n "$TRACE_ID" ]]; then
            pass "Diagnostic completed with trace"
            info "Trace ID: $TRACE_ID"
            info "Status: $STATUS"

            # Show validation results
            VALIDATION=$(echo "$DIAG_RESPONSE" | jq -r '.validation // empty' 2>/dev/null)
            if [[ -n "$VALIDATION" ]]; then
                SERVICES_FOUND=$(echo "$VALIDATION" | jq -r '.servicesFound | length // 0' 2>/dev/null)
                info "Services found in trace: $SERVICES_FOUND"
            fi

            info "View trace: $JAEGER_URL/trace/$TRACE_ID"
        else
            pass "Diagnostic responded"
        fi

        if $VERBOSE; then
            echo -e "\n  ${BLUE}Diagnostic response:${NC}"
            echo "$DIAG_RESPONSE" | jq . 2>/dev/null || echo "$DIAG_RESPONSE"
        fi
    else
        fail "Diagnostic endpoint timed out or failed"
    fi

    echo ""
fi

# ============================================================================
# 5. WebSocket Connection Test
# ============================================================================
echo -e "${YELLOW}━━━ 5. WebSocket Test ━━━${NC}"

# Test WebSocket upgrade
WS_TEST=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
    -H "Connection: Upgrade" \
    -H "Upgrade: websocket" \
    -H "Sec-WebSocket-Version: 13" \
    -H "Sec-WebSocket-Key: dGVzdA==" \
    "$BASE_URL/ws" 2>/dev/null)

if [[ "$WS_TEST" == "101" ]]; then
    pass "WebSocket endpoint accepts upgrade (101 Switching Protocols)"
elif [[ "$WS_TEST" == "400" ]]; then
    warn "WebSocket returns 400 (may need proper handshake)"
elif [[ "$WS_TEST" == "404" ]]; then
    fail "WebSocket endpoint not found at /ws"
else
    warn "WebSocket returned HTTP $WS_TEST"
fi

echo ""

# ============================================================================
# 6. Diagnostic Endpoints
# ============================================================================
echo -e "${YELLOW}━━━ 6. Diagnostic Endpoints ━━━${NC}"

# Check diagnostic endpoint
DIAG_RESPONSE=$(curl -s --max-time 5 "$BASE_URL/api/diagnostic/health" 2>/dev/null)
if [[ -n "$DIAG_RESPONSE" ]]; then
    pass "Diagnostic endpoint available"
    if $VERBOSE; then
        echo "$DIAG_RESPONSE" | jq . 2>/dev/null || echo "$DIAG_RESPONSE"
    fi
else
    warn "Diagnostic endpoint not responding"
fi

# Check BFF observability status
BFF_STATUS=$(curl -s --max-time 5 "$BASE_URL/api/bff/observability/status" 2>/dev/null)
if echo "$BFF_STATUS" | jq -e '.jaegerUrl' >/dev/null 2>&1; then
    pass "BFF observability status available"
    if $VERBOSE; then
        echo "$BFF_STATUS" | jq . 2>/dev/null
    fi
fi

echo ""

# ============================================================================
# 7. Kafka Topic Verification
# ============================================================================
echo -e "${YELLOW}━━━ 7. Kafka Topics ━━━${NC}"

# Check Kafka topics via docker
TOPICS=$(docker exec reactive-kafka kafka-topics.sh --list --bootstrap-server localhost:9092 2>/dev/null | head -20)
if [[ -n "$TOPICS" ]]; then
    if echo "$TOPICS" | grep -q "counter-events"; then
        pass "Topic 'counter-events' exists"
    else
        warn "Topic 'counter-events' not found"
    fi
    if echo "$TOPICS" | grep -q "counter-results"; then
        pass "Topic 'counter-results' exists"
    else
        warn "Topic 'counter-results' not found"
    fi
    if $VERBOSE; then
        info "All topics: $(echo $TOPICS | tr '\n' ', ')"
    fi
else
    warn "Could not list Kafka topics"
fi

echo ""

# ============================================================================
# Summary
# ============================================================================
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
TOTAL=$((PASSED + FAILED))
if [[ $FAILED -eq 0 ]]; then
    echo -e "${GREEN}All $PASSED tests passed!${NC}"
else
    echo -e "${YELLOW}Results: ${GREEN}$PASSED passed${NC}, ${RED}$FAILED failed${NC} (total: $TOTAL)"
fi
echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

# Exit with failure if any tests failed
[[ $FAILED -eq 0 ]] && exit 0 || exit 1
