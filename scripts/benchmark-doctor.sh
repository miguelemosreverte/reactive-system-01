#!/usr/bin/env bash
# Benchmark Doctor - Validates observability chain for benchmarks
# Checks: services health, trace propagation, log correlation, component coverage
#
# Usage:
#   ./benchmark-doctor.sh           # Interactive mode with colored output
#   ./benchmark-doctor.sh --json    # JSON output for programmatic use
#   ./benchmark-doctor.sh --api     # Use the new diagnostic API endpoint

# Don't exit on error - we want to collect all diagnostics
# set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Parse arguments
JSON_OUTPUT=false
USE_API=false
for arg in "$@"; do
    case $arg in
        --json)
            JSON_OUTPUT=true
            ;;
        --api)
            USE_API=true
            ;;
    esac
done

# Disable colors for JSON output
if $JSON_OUTPUT; then
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    CYAN=''
    NC=''
fi

# Expected services in traces
EXPECTED_TRACE_SERVICES=("counter-application" "flink-taskmanager" "drools")

# Counters
PASS=0
FAIL=0
WARN=0

# JSON result storage (use indexed array for bash 3.x compatibility)
JSON_CHECKS=()
TRACE_VALIDATION_RESULT=""

print_header() {
    if ! $JSON_OUTPUT; then
        echo -e "\n${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${BLUE}║${NC}  ${CYAN}Benchmark Doctor${NC}"
        echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}\n"
    fi
}

add_json_check() {
    local category="$1"
    local name="$2"
    local status="$3"
    local message="$4"
    JSON_CHECKS+=("{\"category\":\"$category\",\"name\":\"$name\",\"status\":\"$status\",\"message\":\"$message\"}")
}

check_pass() {
    if ! $JSON_OUTPUT; then
        echo -e "  ${GREEN}✓${NC} $1"
    fi
    add_json_check "${CURRENT_CATEGORY:-general}" "$1" "pass" "$1"
    ((PASS++))
}

check_fail() {
    if ! $JSON_OUTPUT; then
        echo -e "  ${RED}✗${NC} $1"
    fi
    add_json_check "${CURRENT_CATEGORY:-general}" "$1" "fail" "$1"
    ((FAIL++))
}

check_warn() {
    if ! $JSON_OUTPUT; then
        echo -e "  ${YELLOW}⚠${NC} $1"
    fi
    add_json_check "${CURRENT_CATEGORY:-general}" "$1" "warn" "$1"
    ((WARN++))
}

check_info() {
    if ! $JSON_OUTPUT; then
        echo -e "  ${BLUE}→${NC} $1"
    fi
}

# Run diagnostic via API endpoint
run_api_diagnostic() {
    CURRENT_CATEGORY="api_diagnostic"
    if ! $JSON_OUTPUT; then
        echo -e "\n${CYAN}API Diagnostic Test${NC}"
    fi

    check_info "Running E2E diagnostic via API..."

    result=$(curl -sf --max-time 120 -X POST "http://localhost:8080/api/diagnostic/run" 2>/dev/null)

    if [ -z "$result" ]; then
        check_fail "Diagnostic API not responding"
        return
    fi

    # Parse result
    success=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('success', False))" 2>/dev/null)
    trace_id=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('actualTraceId', ''))" 2>/dev/null)
    span_count=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('validation',{}).get('spanCount', 0))" 2>/dev/null)
    is_complete=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('validation',{}).get('isComplete', False))" 2>/dev/null)
    present_services=$(echo "$result" | python3 -c "import json,sys; print(','.join(json.load(sys.stdin).get('validation',{}).get('presentServices', [])))" 2>/dev/null)
    missing_services=$(echo "$result" | python3 -c "import json,sys; print(','.join(json.load(sys.stdin).get('validation',{}).get('missingServices', [])))" 2>/dev/null)
    log_count=$(echo "$result" | python3 -c "import json,sys; print(json.load(sys.stdin).get('logCount', 0))" 2>/dev/null)

    check_info "Trace ID: ${trace_id:0:16}..."
    check_info "Span count: $span_count"
    check_info "Services: $present_services"

    if [ "$is_complete" = "True" ]; then
        check_pass "E2E trace complete - all services present"
    else
        check_fail "E2E trace incomplete - missing: $missing_services"
    fi

    if [ "$log_count" -gt 0 ] 2>/dev/null; then
        check_pass "Found $log_count correlated logs"
    else
        check_warn "No correlated logs found"
    fi

    # Store for JSON output
    TRACE_VALIDATION_RESULT="$result"
}

# 1. Check service health
check_services() {
    CURRENT_CATEGORY="services"
    if ! $JSON_OUTPUT; then
        echo -e "${CYAN}1. Service Health${NC}"
    fi

    services=("gateway:8080/actuator/health" "drools:8180/health" "jaeger:16686" "loki:3100/ready" "otel-collector:13133")

    for svc in "${services[@]}"; do
        name="${svc%%:*}"
        endpoint="${svc#*:}"
        if curl -sf --max-time 5 "http://localhost:${endpoint}" > /dev/null 2>&1; then
            check_pass "$name is healthy"
        else
            check_fail "$name is not responding"
        fi
    done

    # Check Flink job
    running_jobs=$(curl -sf --max-time 5 "http://localhost:8081/jobs/overview" 2>/dev/null | python3 -c "import json,sys; d=json.load(sys.stdin); print(len([j for j in d.get('jobs',[]) if j.get('state')=='RUNNING']))" 2>/dev/null || echo "0")
    if [ "$running_jobs" -gt 0 ]; then
        check_pass "Flink has $running_jobs running job(s)"
    else
        check_fail "No Flink jobs running"
    fi
}

# 2. Check trace propagation
check_trace_propagation() {
    CURRENT_CATEGORY="trace_propagation"
    if ! $JSON_OUTPUT; then
        echo -e "\n${CYAN}2. Trace Propagation${NC}"
    fi

    # Send test request
    check_info "Sending test request..."
    response=$(curl -sf --max-time 10 -X POST "http://localhost:8080/api/counter" \
        -H "Content-Type: application/json" \
        -d '{"sessionId": "doctor-test-'$(date +%s)'", "action": "increment", "value": 100}' 2>/dev/null)

    if [ -z "$response" ]; then
        check_fail "Gateway not responding"
        return
    fi

    request_id=$(echo "$response" | python3 -c "import json,sys; print(json.load(sys.stdin).get('requestId',''))" 2>/dev/null)
    otel_trace_id=$(echo "$response" | python3 -c "import json,sys; print(json.load(sys.stdin).get('otelTraceId',''))" 2>/dev/null)

    if [ -z "$request_id" ]; then
        check_fail "No requestId in response"
        return
    fi

    if [ -z "$otel_trace_id" ]; then
        check_fail "No otelTraceId in response"
        return
    fi

    check_pass "Got requestId: ${request_id:0:16}..., otelTraceId: ${otel_trace_id:0:16}..."

    # Wait for trace to propagate
    check_info "Waiting 8s for full pipeline propagation..."
    sleep 8

    # Fetch trace from Jaeger using otelTraceId
    trace_data=$(curl -sf --max-time 10 "http://localhost:16686/api/traces/$otel_trace_id" 2>/dev/null)

    if [ -z "$trace_data" ]; then
        check_fail "Could not fetch trace from Jaeger"
        return
    fi

    # Analyze trace
    analysis=$(echo "$trace_data" | python3 -c "
import json, sys
data = json.load(sys.stdin)
if not data.get('data') or not data['data']:
    print('NO_DATA')
    sys.exit(0)

trace = data['data'][0]
spans = trace.get('spans', [])
processes = trace.get('processes', {})

# Get services
services = set()
operations = []
for span in spans:
    proc_id = span.get('processID', '')
    svc = processes.get(proc_id, {}).get('serviceName', 'unknown')
    services.add(svc)
    operations.append(f\"{svc}:{span.get('operationName', '?')}\")

print(f'SPANS:{len(spans)}')
print(f'SERVICES:{\"|\".join(sorted(services))}')
print(f'OPS:{\"|\".join(operations[:15])}')
" 2>/dev/null)

    if [ "$analysis" = "NO_DATA" ]; then
        check_fail "Trace has no data"
        return
    fi

    span_count=$(echo "$analysis" | grep "SPANS:" | cut -d: -f2)
    services_str=$(echo "$analysis" | grep "SERVICES:" | cut -d: -f2)

    check_info "Found $span_count spans"

    # Check each expected service
    for expected in "${EXPECTED_TRACE_SERVICES[@]}"; do
        if echo "$services_str" | grep -q "$expected"; then
            check_pass "Trace includes $expected spans"
        else
            check_fail "Trace MISSING $expected spans"
        fi
    done

    # Show operations
    if ! $JSON_OUTPUT; then
        ops=$(echo "$analysis" | grep "OPS:" | cut -d: -f2 | tr '|' '\n')
        echo -e "  ${BLUE}→${NC} Operations in trace:"
        echo "$ops" | head -12 | while read op; do
            echo -e "      $op"
        done
    fi
}

# 3. Check Jaeger services
check_jaeger_services() {
    CURRENT_CATEGORY="jaeger_services"
    if ! $JSON_OUTPUT; then
        echo -e "\n${CYAN}3. Jaeger Service Registry${NC}"
    fi

    services=$(curl -sf --max-time 5 "http://localhost:16686/api/services" 2>/dev/null | python3 -c "import json,sys; print('\n'.join(json.load(sys.stdin).get('data',[])))" 2>/dev/null)

    if [ -z "$services" ]; then
        check_fail "Cannot fetch Jaeger services"
        return
    fi

    for expected in "${EXPECTED_TRACE_SERVICES[@]}"; do
        if echo "$services" | grep -q "$expected"; then
            check_pass "$expected registered in Jaeger"
        else
            check_fail "$expected NOT in Jaeger (no spans received)"
        fi
    done
}

# 4. Check log correlation
check_log_correlation() {
    CURRENT_CATEGORY="log_correlation"
    if ! $JSON_OUTPUT; then
        echo -e "\n${CYAN}4. Log Correlation (Loki)${NC}"
    fi

    # Check if Loki is ready
    if ! curl -sf --max-time 5 "http://localhost:3100/ready" > /dev/null 2>&1; then
        check_fail "Loki not ready"
        return
    fi

    check_pass "Loki is ready"

    # Check for logs with requestId from each service
    end_time=$(date +%s)000000000
    start_time=$((end_time - 300000000000))  # Last 5 minutes

    # Check each service (display_name:loki_service_name)
    for entry in "gateway:application" "flink:flink-taskmanager" "drools:drools"; do
        display_name="${entry%%:*}"
        svc="${entry#*:}"
        # Query for logs
        query="{service=\"$svc\"}"
        result=$(curl -sf --max-time 10 "http://localhost:3100/loki/api/v1/query_range" \
            --data-urlencode "query=$query" \
            --data-urlencode "start=$start_time" \
            --data-urlencode "end=$end_time" \
            --data-urlencode "limit=10" 2>/dev/null)

        log_count=$(echo "$result" | python3 -c "import json,sys; r=json.load(sys.stdin).get('data',{}).get('result',[]); print(sum(len(s.get('values',[])) for s in r))" 2>/dev/null || echo "0")

        if [ -n "$log_count" ] && [ "$log_count" -gt 0 ] 2>/dev/null; then
            # Check if logs have requestId (for business correlation)
            has_request_id=$(echo "$result" | grep -c "requestId" 2>/dev/null || echo "0")
            has_request_id="${has_request_id%%$'\n'*}"  # Get first line only
            if [ "$has_request_id" -gt 0 ]; then
                check_pass "$display_name logs have requestId correlation ($log_count logs)"
            else
                check_warn "$display_name logs found but missing requestId ($log_count logs)"
            fi
        else
            check_warn "$display_name has no recent logs in Loki"
        fi
    done
}

# 5. Check OTEL Collector health
check_otel_collector() {
    CURRENT_CATEGORY="otel_collector"
    if ! $JSON_OUTPUT; then
        echo -e "\n${CYAN}5. OTEL Collector Status${NC}"
    fi

    # Check health
    if curl -sf --max-time 5 "http://localhost:13133/" > /dev/null 2>&1; then
        check_pass "OTEL Collector healthy"
    else
        check_fail "OTEL Collector not healthy"
        return
    fi

    # Check metrics for dropped data
    metrics=$(curl -sf --max-time 10 "http://localhost:8889/metrics" 2>/dev/null)

    if [ -n "$metrics" ]; then
        # Check for refused spans
        refused=$(echo "$metrics" | grep "otelcol_exporter_refused_spans" | grep -v "^#" | awk '{sum+=$2} END {print sum}')
        if [ -n "$refused" ] && [ "$refused" != "0" ]; then
            check_warn "OTEL Collector has refused $refused spans (memory pressure)"
        else
            check_pass "No refused spans"
        fi

        # Check queue size
        queue=$(echo "$metrics" | grep "otelcol_exporter_queue_size" | grep -v "^#" | head -1 | awk '{print $2}')
        if [ -n "$queue" ]; then
            check_info "Export queue size: $queue"
        fi
    fi
}

# 6. Check latest benchmark report
check_benchmark_report() {
    CURRENT_CATEGORY="benchmark_report"
    if ! $JSON_OUTPUT; then
        echo -e "\n${CYAN}6. Benchmark Report Quality${NC}"
    fi

    report_file="reports/full/index.html"

    if [ ! -f "$report_file" ]; then
        check_warn "No benchmark report found at $report_file"
        return
    fi

    check_pass "Report file exists"

    # Extract sample events and analyze coverage
    sample_analysis=$(python3 -c "
import re
import json
import sys

# Read the report file
with open('$report_file', 'r') as f:
    content = f.read()

# Extract sampleEvents JSON array
match = re.search(r'const sampleEvents = (\[.*?\]);', content, re.DOTALL)
if not match:
    print('NO_SAMPLES')
    sys.exit(0)

try:
    samples = json.loads(match.group(1))
except:
    print('PARSE_ERROR')
    sys.exit(0)

total = len(samples)
with_traces = 0
with_logs = 0
has_flink = False
has_drools = False

for s in samples:
    trace_data = s.get('traceData', {})
    trace = trace_data.get('trace', {})
    logs = trace_data.get('logs', [])

    if trace and trace.get('spans'):
        with_traces += 1
        # Check for Flink spans
        processes = trace.get('processes', {})
        for proc in processes.values():
            svc = proc.get('serviceName', '')
            if 'flink' in svc.lower():
                has_flink = True
            if 'drools' in svc.lower():
                has_drools = True

    if logs and len(logs) > 0:
        with_logs += 1

print(f'TOTAL:{total}')
print(f'WITH_TRACES:{with_traces}')
print(f'WITH_LOGS:{with_logs}')
print(f'HAS_FLINK:{has_flink}')
print(f'HAS_DROOLS:{has_drools}')
" 2>/dev/null)

    if [ "$sample_analysis" = "NO_SAMPLES" ]; then
        check_fail "No sample events found in report"
        return
    fi

    if [ "$sample_analysis" = "PARSE_ERROR" ]; then
        check_fail "Failed to parse sample events from report"
        return
    fi

    total_samples=$(echo "$sample_analysis" | grep "TOTAL:" | cut -d: -f2)
    with_traces=$(echo "$sample_analysis" | grep "WITH_TRACES:" | cut -d: -f2)
    with_logs=$(echo "$sample_analysis" | grep "WITH_LOGS:" | cut -d: -f2)
    has_flink=$(echo "$sample_analysis" | grep "HAS_FLINK:" | cut -d: -f2)
    has_drools=$(echo "$sample_analysis" | grep "HAS_DROOLS:" | cut -d: -f2)

    check_info "Found $total_samples sample events in report"

    # Check trace coverage
    if [ "$with_traces" -eq "$total_samples" ] && [ "$total_samples" -gt 0 ]; then
        check_pass "All $total_samples samples have embedded trace data"
    elif [ "$with_traces" -gt 0 ]; then
        check_fail "Only $with_traces of $total_samples samples have trace data"
    else
        check_fail "NO samples have embedded trace data"
    fi

    # Check log coverage
    if [ "$with_logs" -eq "$total_samples" ] && [ "$total_samples" -gt 0 ]; then
        check_pass "All $total_samples samples have embedded log data"
    elif [ "$with_logs" -gt 0 ]; then
        check_fail "Only $with_logs of $total_samples samples have log data"
    else
        check_fail "NO samples have embedded log data"
    fi

    # Check for Flink spans in traces
    if [ "$has_flink" = "True" ]; then
        check_pass "Report traces include Flink spans"
    else
        check_fail "Report traces MISSING Flink spans"
    fi

    # Check for Drools spans in traces
    if [ "$has_drools" = "True" ]; then
        check_pass "Report traces include Drools spans"
    else
        check_warn "Report traces MISSING Drools spans (async evaluation)"
    fi
}

# Print JSON output
print_json() {
    local status="pass"
    if [ $FAIL -gt 0 ]; then
        status="fail"
    elif [ $WARN -gt 0 ]; then
        status="warn"
    fi

    # Build checks array
    local checks_json=""
    for check in "${JSON_CHECKS[@]}"; do
        if [ -n "$checks_json" ]; then
            checks_json="$checks_json,$check"
        else
            checks_json="$check"
        fi
    done

    cat <<EOF
{
  "status": "$status",
  "summary": {
    "passed": $PASS,
    "failed": $FAIL,
    "warnings": $WARN
  },
  "checks": [$checks_json],
  "traceValidation": ${TRACE_VALIDATION_RESULT:-null},
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
}

# Summary
print_summary() {
    if $JSON_OUTPUT; then
        print_json
        if [ $FAIL -gt 0 ]; then
            exit 1
        else
            exit 0
        fi
    fi

    echo -e "\n${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}Summary${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
    echo -e "  ${GREEN}Passed:${NC}  $PASS"
    echo -e "  ${RED}Failed:${NC}  $FAIL"
    echo -e "  ${YELLOW}Warnings:${NC} $WARN"

    if [ $FAIL -gt 0 ]; then
        echo -e "\n${RED}⚠ Benchmark observability has issues that need fixing${NC}"
        exit 1
    elif [ $WARN -gt 0 ]; then
        echo -e "\n${YELLOW}⚠ Benchmark observability works but has warnings${NC}"
        exit 0
    else
        echo -e "\n${GREEN}✓ Benchmark observability is fully operational${NC}"
        exit 0
    fi
}

# Main
print_header
check_services
check_jaeger_services

if $USE_API; then
    # Use the diagnostic API for trace validation
    run_api_diagnostic
else
    # Use manual trace propagation check
    check_trace_propagation
fi

check_log_correlation
check_otel_collector
check_benchmark_report
print_summary
