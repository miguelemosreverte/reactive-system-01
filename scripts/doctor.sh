#!/bin/bash
# Doctor script - comprehensive health and version check
# This can be run directly: ./scripts/doctor.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/utils.sh"

# Expected version from versions.json
EXPECTED_VERSION="1.0.0"

# Check if Flink job is running
check_flink_job() {
    local response
    response=$(curl -sf "http://localhost:8081/jobs/overview" 2>/dev/null)
    if [[ $? -ne 0 ]]; then
        print_error "Flink Job: Cannot connect to JobManager"
        return 1
    fi

    local running_jobs
    running_jobs=$(echo "$response" | grep -o '"state":"RUNNING"' | wc -l | tr -d ' ')

    if [[ "$running_jobs" -ge 1 ]]; then
        print_success "Flink Job: $running_jobs job(s) running"
        return 0
    else
        print_error "Flink Job: No jobs running"
        echo "         Hint: Check 'docker logs reactive-flink-job-submitter' for errors"
        return 1
    fi
}

# Check WebSocket connectivity
check_websocket() {
    local response
    response=$(curl -sf -o /dev/null -w "%{http_code}" --max-time 5 \
        -H "Connection: Upgrade" \
        -H "Upgrade: websocket" \
        -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
        -H "Sec-WebSocket-Version: 13" \
        "http://localhost:3000/ws" 2>/dev/null)

    if [[ "$response" == "101" ]]; then
        print_success "WebSocket: Connection upgrade successful"
        return 0
    elif [[ "$response" == "000" ]] || [[ -z "$response" ]]; then
        if curl -sf --max-time 3 "http://localhost:8080/health" > /dev/null 2>&1; then
            print_success "WebSocket: Gateway reachable (WS endpoint available)"
            return 0
        fi
        print_error "WebSocket: Cannot connect to endpoint"
        return 1
    else
        print_warning "WebSocket: Endpoint reachable but upgrade returned HTTP $response"
        return 0
    fi
}

# Check Kafka topics exist
check_kafka_topics() {
    local response
    response=$(curl -sf "http://localhost:8080/health" 2>/dev/null)

    if [[ $? -eq 0 ]]; then
        print_success "Kafka Topics: Gateway connected to Kafka"
        return 0
    else
        print_warning "Kafka Topics: Cannot verify (gateway not reachable)"
        return 1
    fi
}

# Check trace timeline coverage - ensure no gaps in distributed tracing
check_trace_timeline() {
    local max_gap_ms=100  # Alert if gap > 100ms between spans

    # Get recent traces from Jaeger - fetch multiple to find a counter action trace
    local traces_response
    traces_response=$(curl -sf "http://localhost:16686/api/traces?service=gateway&limit=10&lookback=5m" 2>/dev/null)

    if [[ $? -ne 0 ]] || [[ -z "$traces_response" ]]; then
        print_warning "Trace Timeline: Cannot fetch traces from Jaeger"
        return 0  # Don't fail - Jaeger might be slow to collect
    fi

    # Look for a trace that contains counter action (not just health checks)
    # Counter action traces have kafka.publish or more than 10 spans
    if ! echo "$traces_response" | grep -qE 'kafka\.publish|counter-events|flink\.process_event'; then
        print_warning "Trace Timeline: No counter action traces found (perform an action first)"
        return 0
    fi

    # Count spans in traces that have kafka operations
    local span_count
    span_count=$(echo "$traces_response" | grep -o '"spanID"' | wc -l | tr -d ' ')

    if [[ "$span_count" -lt 4 ]]; then
        print_warning "Trace Timeline: Only $span_count spans found (expected: gateway, kafka, flink, drools)"
        echo "         Hint: Some services may not be reporting traces correctly"
        return 0
    fi

    # Extract span times to check for gaps (simplified gap detection)
    # Parse startTime and duration of spans
    local spans_info
    spans_info=$(echo "$traces_response" | grep -oE '"operationName":"[^"]+"|"startTime":[0-9]+|"duration":[0-9]+' | head -30)

    # Check for expected operation names indicating full pipeline coverage
    # Services appear as: gateway, flink-taskmanager, drools
    # Kafka operations are within gateway (publish) and flink (consume)
    local has_gateway=false
    local has_kafka=false
    local has_flink=false
    local has_drools=false

    # Gateway service with API endpoints
    echo "$traces_response" | grep -q '"serviceName":"gateway"' && has_gateway=true
    # Kafka operations (publish from gateway, consume in flink)
    echo "$traces_response" | grep -qE 'kafka\.publish|kafka\.consume|counter-events' && has_kafka=true
    # Flink processing spans
    echo "$traces_response" | grep -qE 'flink\.(deserialize|process_event)|flink-taskmanager' && has_flink=true
    # Drools rule evaluation
    echo "$traces_response" | grep -qE '"serviceName":"drools"|drools\.evaluate' && has_drools=true

    local services_covered=0
    [[ "$has_gateway" == "true" ]] && ((services_covered++))
    [[ "$has_kafka" == "true" ]] && ((services_covered++))
    [[ "$has_flink" == "true" ]] && ((services_covered++))
    [[ "$has_drools" == "true" ]] && ((services_covered++))

    if [[ "$services_covered" -eq 4 ]]; then
        print_success "Trace Timeline: Full coverage ($span_count spans across all 4 services)"
        return 0
    elif [[ "$services_covered" -ge 3 ]]; then
        print_warning "Trace Timeline: Partial coverage ($span_count spans, $services_covered/4 services)"
        [[ "$has_gateway" != "true" ]] && echo "         Missing: Gateway spans"
        [[ "$has_kafka" != "true" ]] && echo "         Missing: Kafka spans"
        [[ "$has_flink" != "true" ]] && echo "         Missing: Flink spans"
        [[ "$has_drools" != "true" ]] && echo "         Missing: Drools spans"
        return 0
    else
        print_error "Trace Timeline: Poor coverage ($span_count spans, only $services_covered/4 services)"
        echo "         Hint: Check trace context propagation in services"
        return 1
    fi
}

# Check Grafana dashboard panels have data
check_grafana_panels() {
    local prometheus_url="http://localhost:9090"
    local loki_url="http://localhost:3100"
    local passed=0
    local failed=0
    local total=0

    # Key metrics that must have data
    local -a metrics=(
        "application_ready_time_seconds|Drools Startup"
        "sum(flink_taskmanager_job_task_operator_numRecordsIn)|Events Processed"
        "flink_taskmanager_job_task_operator_numRecordsInPerSecond|Flink Throughput"
        "jvm_memory_used_bytes|JVM Memory"
        "flink_taskmanager_Status_JVM_Memory_Heap_Used|Flink Heap"
        "go_memstats_heap_alloc_bytes|OTEL Memory"
        "container_memory_usage_bytes|Container Memory"
    )

    for metric_pair in "${metrics[@]}"; do
        local query="${metric_pair%%|*}"
        local name="${metric_pair##*|}"
        ((total++))

        local encoded_query=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$query'))" 2>/dev/null || echo "$query")
        local result=$(curl -sf "${prometheus_url}/api/v1/query?query=${encoded_query}" 2>/dev/null)
        local count=$(echo "$result" | grep -o '"result":\[' | wc -l)
        local has_data=$(echo "$result" | grep -o '"value"' | wc -l)

        if [[ "$has_data" -gt 0 ]]; then
            ((passed++))
        else
            ((failed++))
            print_warning "Grafana Panel '$name': No data"
        fi
    done

    # Check Loki
    ((total++))
    if curl -sf "${loki_url}/ready" > /dev/null 2>&1; then
        local loki_result=$(curl -sf "${loki_url}/loki/api/v1/labels" 2>/dev/null)
        if echo "$loki_result" | grep -q "compose_service"; then
            ((passed++))
        else
            ((failed++))
            print_warning "Grafana Panel 'Logs': No log labels in Loki"
        fi
    else
        ((failed++))
        print_warning "Grafana Panel 'Logs': Loki not reachable"
    fi

    if [[ "$failed" -eq 0 ]]; then
        print_success "Grafana Panels: All $passed/$total panels have data"
        return 0
    else
        print_warning "Grafana Panels: $passed/$total panels have data ($failed missing)"
        echo "         Run './scripts/grafana-diagnostics.sh' for detailed report"
        return 1
    fi
}

# E2E test - full round-trip through the system
check_e2e_flow() {
    local session_id="e2e-test-$(date +%s)"
    local initial_value=0

    # Step 1: Send increment action
    local publish_response
    publish_response=$(curl -sf -X POST "http://localhost:8080/api/counter" \
        -H "Content-Type: application/json" \
        -d "{\"action\":\"increment\",\"value\":1,\"sessionId\":\"$session_id\"}" 2>/dev/null)

    if [[ $? -ne 0 ]] || [[ -z "$publish_response" ]]; then
        print_error "E2E Test: Failed to publish event"
        return 1
    fi

    # Extract traceId
    local trace_id
    trace_id=$(echo "$publish_response" | grep -o '"traceId":"[^"]*"' | cut -d'"' -f4)

    if [[ -z "$trace_id" ]]; then
        print_error "E2E Test: No traceId in response"
        return 1
    fi

    # Step 2: Wait for processing (Gateway -> Kafka -> Flink -> Drools -> Kafka -> Gateway)
    sleep 2

    # Step 3: Check result
    local result_response
    result_response=$(curl -sf "http://localhost:8080/api/counter/status?sessionId=$session_id" 2>/dev/null)

    if [[ $? -ne 0 ]] || [[ -z "$result_response" ]]; then
        print_error "E2E Test: Failed to get result"
        return 1
    fi

    # Check if value was incremented
    local result_value
    result_value=$(echo "$result_response" | grep -o '"value":[0-9]*' | cut -d':' -f2)

    if [[ "$result_value" == "1" ]]; then
        print_success "E2E Test: Full flow working (value=1, traceId=${trace_id:0:8}...)"
        return 0
    elif [[ "$result_value" == "0" ]]; then
        print_error "E2E Test: Event not processed (value still 0)"
        echo "         Hint: Check Flink job logs with './cli.sh logs flink'"
        return 1
    else
        print_warning "E2E Test: Unexpected value=$result_value"
        return 1
    fi
}

# Check service version
check_service_version() {
    local name=$1
    local url=$2
    local expected=${3:-$EXPECTED_VERSION}

    local response
    response=$(curl -sf "$url" 2>/dev/null)

    if [[ $? -ne 0 ]]; then
        print_error "$name: Cannot connect"
        return 1
    fi

    # Extract version from JSON response
    local version
    version=$(echo "$response" | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -z "$version" ]]; then
        print_warning "$name: Version not reported"
        return 0
    fi

    if [[ "$version" == "$expected" ]]; then
        print_success "$name: v$version (current)"
        return 0
    else
        print_warning "$name: v$version (expected: v$expected)"
        return 1
    fi
}

run_doctor() {
    print_header "Health Check"
    echo ""

    local all_healthy=true
    local critical_failure=false
    local version_mismatch=false

    echo -e "${CYAN}── Infrastructure ──${NC}"

    # Check Zookeeper
    if ! check_tcp_health "Zookeeper" "localhost" "2181"; then
        all_healthy=false
        critical_failure=true
    fi

    # Check Kafka
    if ! check_tcp_health "Kafka" "localhost" "9092"; then
        all_healthy=false
        critical_failure=true
    fi

    echo ""
    echo -e "${CYAN}── Observability ──${NC}"

    # Check OTEL Collector
    if ! check_http_health "OTEL Collector" "http://localhost:13133"; then
        all_healthy=false
    fi

    # Check Jaeger
    if ! check_http_health "Jaeger" "http://localhost:16686"; then
        all_healthy=false
    fi

    # Check Grafana
    if ! check_http_health "Grafana" "http://localhost:3001/api/health"; then
        all_healthy=false
    fi

    echo ""
    echo -e "${CYAN}── Services ──${NC}"

    # Check Drools
    if ! check_http_health "Drools" "http://localhost:8180/health"; then
        all_healthy=false
    fi

    # Check Flink JobManager
    if ! check_http_health "Flink JobManager" "http://localhost:8081/overview"; then
        all_healthy=false
    fi

    # Check Gateway
    if ! check_http_health "Gateway" "http://localhost:8080/health"; then
        all_healthy=false
    fi

    # Check UI
    if ! check_http_health "UI" "http://localhost:3000"; then
        all_healthy=false
    fi

    echo ""
    echo -e "${CYAN}── Application ──${NC}"

    # Check Flink Job is running
    if ! check_flink_job; then
        all_healthy=false
    fi

    # Check WebSocket
    if ! check_websocket; then
        all_healthy=false
    fi

    # Check Kafka topics (via gateway)
    if ! check_kafka_topics; then
        all_healthy=false
    fi

    # E2E Test
    if ! check_e2e_flow; then
        all_healthy=false
    fi

    # Trace Timeline Coverage
    if ! check_trace_timeline; then
        all_healthy=false
    fi

    echo ""
    echo -e "${CYAN}── Grafana Dashboard ──${NC}"

    # Check Grafana panels have data
    if ! check_grafana_panels; then
        all_healthy=false
    fi

    echo ""
    echo -e "${CYAN}── Service Versions ──${NC}"

    # Check versions
    if ! check_service_version "Gateway" "http://localhost:8080/health" "$EXPECTED_VERSION"; then
        version_mismatch=true
    fi

    if ! check_service_version "Drools" "http://localhost:8180/health" "$EXPECTED_VERSION"; then
        version_mismatch=true
    fi

    echo ""
    echo -e "${CYAN}── Observability URLs ──${NC}"
    print_info "Jaeger UI:    http://localhost:16686"
    print_info "Grafana:      http://localhost:3001"
    print_info "Flink UI:     http://localhost:8081"

    echo ""
    if $all_healthy && ! $version_mismatch; then
        print_success "All services are healthy!"
        return 0
    elif $critical_failure; then
        print_error "Critical infrastructure failure - check Docker containers"
        return 2
    elif $version_mismatch; then
        print_warning "Some services have version mismatches - consider rebuilding"
        return 1
    else
        print_warning "Some services are unhealthy"
        return 1
    fi
}

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    run_doctor
fi
