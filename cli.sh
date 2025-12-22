#!/bin/bash
# Reactive System CLI
# Usage: ./cli.sh <command> [service]

set -e

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Source utilities
source "$SCRIPT_DIR/scripts/utils.sh"

# Stats file
STATS_FILE="$SCRIPT_DIR/cli.stats.md"

# Timing functions
start_timer() {
    TIMER_START=$(date +%s.%N)
}

end_timer() {
    local cmd="$1"
    local service="$2"
    local status="$3"
    TIMER_END=$(date +%s.%N)
    local duration=$(echo "$TIMER_END - $TIMER_START" | bc)
    local formatted=$(printf "%.2f" "$duration")

    # Log to stats file
    log_stat "$cmd" "$service" "$formatted" "$status"

    # Print timing
    echo ""
    print_info "Duration: ${formatted}s"
}

log_stat() {
    local cmd="$1"
    local service="${2:-all}"
    local duration="$3"
    local status="${4:-success}"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')

    # Create stats file with header if it doesn't exist
    if [[ ! -f "$STATS_FILE" ]]; then
        cat > "$STATS_FILE" << 'EOF'
# CLI Performance Stats

Track command execution times to identify optimization opportunities.

## Recent Commands

| Timestamp | Command | Service | Duration | Status |
|-----------|---------|---------|----------|--------|
EOF
    fi

    # Append stat (keep last 50 entries)
    echo "| $timestamp | $cmd | $service | ${duration}s | $status |" >> "$STATS_FILE"

    # Trim to last 50 entries (keep header)
    local header_lines=8
    local total_lines=$(wc -l < "$STATS_FILE")
    if [[ $total_lines -gt $((header_lines + 50)) ]]; then
        head -n $header_lines "$STATS_FILE" > "$STATS_FILE.tmp"
        tail -n 50 "$STATS_FILE" >> "$STATS_FILE.tmp"
        mv "$STATS_FILE.tmp" "$STATS_FILE"
    fi
}

# Load environment variables
if [[ -f "$SCRIPT_DIR/.env" ]]; then
    export $(cat "$SCRIPT_DIR/.env" | grep -v '^#' | xargs)
fi

# Set Docker host for Colima if running
if [[ -S "$HOME/.colima/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.colima/docker.sock"
fi

# Version from versions.json
SYSTEM_VERSION="1.0.0"

# Infrastructure services (started first, in parallel)
INFRA_SERVICES="zookeeper kafka"
# Observability services (can start with infra)
OBS_SERVICES="otel-collector jaeger grafana"
# Application services (depend on infra)
APP_SERVICES="drools flink-jobmanager flink-taskmanager gateway ui"

# Help message
show_help() {
    print_header "Reactive System CLI v${SYSTEM_VERSION}"
    echo ""
    echo "Usage: ./cli.sh <namespace> <command> [options]"
    echo "       ./cli.sh <command> [options]           # Lifecycle commands"
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "LIFECYCLE COMMANDS (no namespace)"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "  start [service]     Start all services or a specific service"
    echo "  stop [service]      Stop all services or a specific service"
    echo "  restart [service]   Restart services (no rebuild)"
    echo "  rebuild <service>   Smart rebuild (cached deps, fast)"
    echo "  status              Show running services"
    echo "  logs <service>      View service logs"
    echo "  down                Stop and remove all containers"
    echo "  clean               Remove all containers and volumes"
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "PLATFORM COMMANDS (./cli.sh platform <cmd> or ./cli.sh p <cmd>)"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "  Observability:"
    echo "    traces [id]       Show trace timeline or inspect specific trace"
    echo "    logs <requestId>  Search logs by requestId"
    echo "    jaeger            Open Jaeger UI"
    echo "    grafana           Open Grafana dashboards"
    echo "    loki <query>      Query Loki directly"
    echo ""
    echo "  Debugging:"
    echo "    doctor            Health check all services"
    echo "    memory [cmd]      Memory diagnostics (pressure/risk/crashes/kpis/diagnose)"
    echo "    benchmark [type]  Run benchmarks (http/kafka/drools/full)"
    echo ""
    echo "  Infrastructure:"
    echo "    kafka             Kafka cluster status and tools"
    echo "    flink             Flink job manager status"
    echo "    drools            Drools rule engine status"
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "APPLICATION COMMANDS (./cli.sh app <cmd> or ./cli.sh a <cmd>)"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "  Counter Operations:"
    echo "    send [opts]       Send counter event (--session, --action, --value)"
    echo "    debug [opts]      Send with debug mode (returns trace + logs)"
    echo "    status [session]  Get counter status"
    echo ""
    echo "  Testing:"
    echo "    e2e               Run end-to-end test"
    echo "    load [opts]       Run load test (--duration, --concurrency)"
    echo ""
    echo "  BFF Endpoints:"
    echo "    bff-status        Check BFF API status"
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "DEVELOPMENT COMMANDS"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "  dev                 Start in dev mode (faster rebuilds)"
    echo "  watch <service>     Watch and auto-restart on changes"
    echo "  shell <service>     Enter container shell"
    echo "  compile drools      Recompile Drools rules"
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "URLs"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "  http://localhost:3000    UI Portal"
    echo "  http://localhost:8080    Gateway API"
    echo "  http://localhost:16686   Jaeger (Traces)"
    echo "  http://localhost:3001    Grafana (Dashboards)"
    echo "  http://localhost:8081    Flink Dashboard"
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "Examples"
    echo "═══════════════════════════════════════════════════════════════════"
    echo ""
    echo "  ./cli.sh start                          # Start all services"
    echo "  ./cli.sh rebuild gateway                # Rebuild gateway"
    echo "  ./cli.sh p traces                       # Show recent traces"
    echo "  ./cli.sh p doctor                       # Health check all"
    echo "  ./cli.sh a send --customer acme         # Send event for customer"
    echo "  ./cli.sh a debug --customer acme        # Send with debug (trace+logs)"
    echo "  ./cli.sh p benchmark full               # Run full E2E benchmark"
    echo ""
}

# Platform namespace commands
cmd_platform() {
    local subcmd="${1:-help}"
    shift 2>/dev/null || true

    case "$subcmd" in
        traces)
            cmd_traces "$@"
            ;;
        logs)
            cmd_search "$@"
            ;;
        jaeger)
            open "http://localhost:16686" 2>/dev/null || echo "http://localhost:16686"
            ;;
        grafana)
            open "http://localhost:3001" 2>/dev/null || echo "http://localhost:3001"
            ;;
        loki)
            cmd_loki_query "$@"
            ;;
        doctor)
            cmd_doctor
            ;;
        replay)
            cmd_replay "$@"
            ;;
        memory|mem)
            cmd_memory "$@"
            ;;
        benchmark|bench)
            cmd_benchmark "$@"
            ;;
        kafka)
            cmd_kafka_status
            ;;
        flink)
            cmd_flink_status
            ;;
        drools)
            cmd_drools_status
            ;;
        help|*)
            echo ""
            print_header "Platform Commands"
            echo ""
            echo "Observability:"
            echo "  ./cli.sh p traces [id]      Show traces or inspect by ID"
            echo "  ./cli.sh p logs <requestId> Search logs by requestId"
            echo "  ./cli.sh p jaeger           Open Jaeger UI"
            echo "  ./cli.sh p grafana          Open Grafana"
            echo ""
            echo "Debugging:"
            echo "  ./cli.sh p doctor           Health check all services"
            echo "  ./cli.sh p memory           Memory diagnostics"
            echo "  ./cli.sh p benchmark [type] Run benchmarks"
            echo "  ./cli.sh p replay <session> Replay events with tracing"
            echo ""
            echo "Infrastructure:"
            echo "  ./cli.sh p kafka            Kafka status"
            echo "  ./cli.sh p flink            Flink status"
            echo "  ./cli.sh p drools           Drools status"
            echo ""
            ;;
    esac
}

# Application namespace commands
cmd_app() {
    local subcmd="${1:-help}"
    shift 2>/dev/null || true

    case "$subcmd" in
        send)
            cmd_app_send "$@"
            ;;
        debug)
            cmd_app_debug "$@"
            ;;
        status)
            cmd_app_status "$@"
            ;;
        e2e)
            cmd_e2e
            ;;
        load)
            cmd_app_load "$@"
            ;;
        bff-status)
            curl -s http://localhost:8080/api/bff/observability/status | jq . 2>/dev/null || \
                curl -s http://localhost:8080/api/bff/observability/status
            ;;
        help|*)
            echo ""
            print_header "Application Commands"
            echo ""
            echo "Counter Operations:"
            echo "  ./cli.sh a send [opts]      Send counter event"
            echo "     --customer, -c <id>      Customer ID"
            echo "     --session, -s <id>       Session ID (default: default)"
            echo "     --action, -a <action>    Action: increment/decrement/set"
            echo "     --value, -v <n>          Value (default: 1)"
            echo ""
            echo "  ./cli.sh a debug [opts]     Send with debug mode"
            echo "     (same options as send, returns trace + logs)"
            echo ""
            echo "  ./cli.sh a status [session] Get counter status"
            echo ""
            echo "Testing:"
            echo "  ./cli.sh a e2e              Run end-to-end test"
            echo "  ./cli.sh a load [opts]      Run load test"
            echo "     --duration, -d <sec>     Duration in seconds"
            echo "     --concurrency, -c <n>    Concurrent requests"
            echo ""
            echo "BFF:"
            echo "  ./cli.sh a bff-status       Check BFF API status"
            echo ""
            ;;
    esac
}

# App send command
cmd_app_send() {
    local customer=""
    local session="default"
    local action="increment"
    local value=1

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --customer|-c)
                customer="$2"
                shift 2
                ;;
            --session|-s)
                session="$2"
                shift 2
                ;;
            --action|-a)
                action="$2"
                shift 2
                ;;
            --value|-v)
                value="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    local url="http://localhost:8080/api/counter"
    if [[ -n "$customer" ]]; then
        url="http://localhost:8080/api/customers/${customer}/counter"
    fi

    print_info "Sending: action=$action, value=$value, session=$session, customer=${customer:-<none>}"

    curl -s -X POST "$url" \
        -H "Content-Type: application/json" \
        -d "{\"action\": \"$action\", \"value\": $value, \"sessionId\": \"$session\"}" | \
        jq . 2>/dev/null || \
        curl -s -X POST "$url" \
            -H "Content-Type: application/json" \
            -d "{\"action\": \"$action\", \"value\": $value, \"sessionId\": \"$session\"}"
}

# App debug command (with trace + logs)
cmd_app_debug() {
    local customer=""
    local session="default"
    local action="increment"
    local value=1
    local wait_ms=2000

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --customer|-c)
                customer="$2"
                shift 2
                ;;
            --session|-s)
                session="$2"
                shift 2
                ;;
            --action|-a)
                action="$2"
                shift 2
                ;;
            --value|-v)
                value="$2"
                shift 2
                ;;
            --wait|-w)
                wait_ms="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    local url="http://localhost:8080/api/bff/counter"
    if [[ -n "$customer" ]]; then
        url="http://localhost:8080/api/bff/customers/${customer}/counter"
    fi

    print_info "Sending with debug mode: action=$action, value=$value"
    print_info "Waiting ${wait_ms}ms for trace propagation..."

    curl -s -X POST "$url" \
        -H "Content-Type: application/json" \
        -H "X-Debug: true" \
        -H "X-Debug-Wait-Ms: $wait_ms" \
        -d "{\"action\": \"$action\", \"value\": $value, \"sessionId\": \"$session\"}" | \
        jq . 2>/dev/null || \
        curl -s -X POST "$url" \
            -H "Content-Type: application/json" \
            -H "X-Debug: true" \
            -H "X-Debug-Wait-Ms: $wait_ms" \
            -d "{\"action\": \"$action\", \"value\": $value, \"sessionId\": \"$session\"}"
}

# App status command
cmd_app_status() {
    local session="${1:-default}"
    curl -s "http://localhost:8080/api/counter/status?sessionId=$session" | \
        jq . 2>/dev/null || \
        curl -s "http://localhost:8080/api/counter/status?sessionId=$session"
}

# Loki query command
cmd_loki_query() {
    local query="$1"
    if [[ -z "$query" ]]; then
        print_error "Usage: ./cli.sh p loki '<LogQL query>'"
        echo ""
        echo "Examples:"
        echo "  ./cli.sh p loki '{service=\"gateway\"}'"
        echo "  ./cli.sh p loki '{service=~\".+\"} |= \"error\"'"
        return 1
    fi

    local start=$(( $(date +%s) - 3600 ))000000000
    local end=$(date +%s)000000000

    curl -sG "http://localhost:3100/loki/api/v1/query_range" \
        --data-urlencode "query=$query" \
        --data-urlencode "start=$start" \
        --data-urlencode "end=$end" \
        --data-urlencode "limit=50" | \
        jq '.data.result[] | .values[] | .[1]' 2>/dev/null | head -20
}

# Kafka status command
cmd_kafka_status() {
    print_header "Kafka Status"
    echo ""

    if docker compose ps kafka 2>/dev/null | grep -q "running"; then
        print_success "Kafka is running"
    else
        print_error "Kafka is not running"
        return 1
    fi

    echo ""
    print_info "Topics:"
    docker compose exec -T kafka kafka-topics.sh --list --bootstrap-server localhost:9092 2>/dev/null | head -10
}

# Flink status command
cmd_flink_status() {
    print_header "Flink Status"
    echo ""

    if curl -sf http://localhost:8081/overview >/dev/null 2>&1; then
        print_success "Flink JobManager is running"
        echo ""
        print_info "Jobs:"
        curl -s http://localhost:8081/jobs | jq '.jobs[] | {id: .id, status: .status}' 2>/dev/null
    else
        print_error "Flink is not responding"
    fi
}

# Drools status command
cmd_drools_status() {
    print_header "Drools Status"
    echo ""

    local health=$(curl -s http://localhost:8180/health 2>/dev/null)
    if echo "$health" | grep -q "UP"; then
        print_success "Drools is healthy"
        echo "$health" | jq . 2>/dev/null || echo "$health"
    else
        print_error "Drools is not responding"
    fi
}

# Check if infra is running
infra_running() {
    docker compose ps kafka 2>/dev/null | grep -q "running"
}

# Start infrastructure only (fast, parallel)
start_infra() {
    print_info "Starting infrastructure..."
    docker compose up -d $INFRA_SERVICES $OBS_SERVICES
}

# Wait for infrastructure to be ready
wait_for_infra() {
    print_info "Waiting for infrastructure..."
    local max_wait=60
    local waited=0

    while ! nc -z localhost 9092 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
        if [[ $waited -ge $max_wait ]]; then
            print_error "Infrastructure did not start in time"
            return 1
        fi
    done
    print_success "Infrastructure ready"
}

# Start services (optimized)
cmd_start() {
    local service=$1
    start_timer

    if [[ -z "$service" ]]; then
        print_header "Starting all services"

        # Check if already running
        if infra_running; then
            print_info "Infrastructure already running, starting app services..."
            # --build: rebuild only if source changed (Docker handles caching)
            docker compose up -d --build $APP_SERVICES flink-job-submitter kafka-init
        else
            # Start everything with smart rebuild
            docker compose up -d --build
        fi

        print_success "All services started"
        echo ""
        print_info "UI Portal:        http://localhost:3000"
        print_info "Jaeger (Traces):  http://localhost:16686"
        print_info "Grafana:          http://localhost:3001"
        print_info "Flink Dashboard:  http://localhost:8081"
        echo ""
        print_info "Run './cli.sh doctor' to check health"
        end_timer "start" "all" "success"
    else
        if ! service_exists "$service"; then
            print_error "Unknown service: $service"
            exit 1
        fi
        print_header "Starting $service"
        local services=$(map_service_name "$service")
        # --build: rebuild only if source changed (Docker handles caching)
        docker compose up -d --build $services
        print_success "$service started"
        end_timer "start" "$service" "success"
    fi
}

# Stop services
cmd_stop() {
    local service=$1
    if [[ -z "$service" ]]; then
        print_header "Stopping all services"
        docker compose stop
        print_success "All services stopped"
    else
        if ! service_exists "$service"; then
            print_error "Unknown service: $service"
            exit 1
        fi
        print_header "Stopping $service"
        local services=$(map_service_name "$service")
        docker compose stop $services
        print_success "$service stopped"
    fi
}

# Restart services (with optional rebuild)
cmd_restart() {
    local service=$1
    if [[ -z "$service" ]]; then
        print_header "Restarting all services"
        docker compose restart
        print_success "All services restarted"
    else
        if ! service_exists "$service"; then
            print_error "Unknown service: $service"
            exit 1
        fi
        print_header "Restarting $service"
        local services=$(map_service_name "$service")
        docker compose restart $services
        print_success "$service restarted"
    fi
}

# Quick restart (no rebuild, fastest option)
cmd_quick() {
    local service=$1
    start_timer

    if [[ -z "$service" ]]; then
        print_error "Please specify a service for quick restart"
        print_info "Usage: ./cli.sh quick <service>"
        exit 1
    fi

    if ! service_exists "$service"; then
        print_error "Unknown service: $service"
        exit 1
    fi

    print_header "Quick restart: $service"
    local services=$(map_service_name "$service")

    # Restart (no wait for faster iteration)
    docker compose restart $services

    print_success "$service restarted (no rebuild)"
    end_timer "quick" "$service" "success"
}

# Smart rebuild (uses cache, fast for code-only changes)
cmd_rebuild() {
    local service=$1
    start_timer

    if [[ -z "$service" ]]; then
        print_error "Please specify a service to rebuild"
        print_info "Usage: ./cli.sh rebuild <service>"
        exit 1
    fi

    if ! service_exists "$service"; then
        print_error "Unknown service: $service"
        exit 1
    fi

    print_header "Rebuilding: $service (cached)"
    local services=$(map_service_name "$service")

    # Build WITH cache (BuildKit caches deps, only rebuilds source)
    DOCKER_BUILDKIT=1 docker compose build $services

    # Recreate and start (no wait for faster iteration)
    docker compose up -d --force-recreate $services

    print_success "$service rebuilt and restarted"
    end_timer "rebuild" "$service" "success"
}

# Full rebuild (no cache, for dependency changes)
cmd_full_rebuild() {
    local service=$1
    start_timer

    if [[ -z "$service" ]]; then
        print_error "Please specify a service to rebuild"
        print_info "Usage: ./cli.sh full-rebuild <service>"
        exit 1
    fi

    if ! service_exists "$service"; then
        print_error "Unknown service: $service"
        exit 1
    fi

    print_header "Full rebuild: $service (no cache)"
    local services=$(map_service_name "$service")

    # Build WITHOUT cache
    docker compose build --no-cache $services

    # Recreate and start
    docker compose up -d --force-recreate $services

    print_success "$service fully rebuilt and restarted"
    end_timer "full-rebuild" "$service" "success"
}

# Build services
cmd_build() {
    local service=$1
    if [[ -z "$service" ]]; then
        print_header "Building all services"
        docker compose build
        print_success "All services built"
    else
        if ! service_exists "$service"; then
            print_error "Unknown service: $service"
            exit 1
        fi
        print_header "Building $service"
        local services=$(map_service_name "$service")
        docker compose build $services
        print_success "$service built"
    fi
}

# View logs
cmd_logs() {
    local service=$1
    if [[ -z "$service" ]]; then
        docker compose logs -f
    else
        if ! service_exists "$service"; then
            print_error "Unknown service: $service"
            exit 1
        fi
        local services=$(map_service_name "$service")
        docker compose logs -f $services
    fi
}

# Show status
cmd_status() {
    print_header "Service Status (v${SYSTEM_VERSION})"
    docker compose ps
}

# Enter shell
cmd_shell() {
    local service=$1
    if [[ -z "$service" ]]; then
        print_error "Please specify a service"
        exit 1
    fi

    local container_name
    case $service in
        ui)
            container_name="reactive-ui"
            ;;
        gateway)
            container_name="reactive-gateway"
            ;;
        flink)
            container_name="reactive-flink-jobmanager"
            ;;
        drools)
            container_name="reactive-drools"
            ;;
        kafka)
            container_name="reactive-kafka"
            ;;
        zookeeper)
            container_name="reactive-zookeeper"
            ;;
        *)
            print_error "Unknown service: $service"
            exit 1
            ;;
    esac

    print_info "Entering $service shell..."
    docker exec -it "$container_name" /bin/sh || docker exec -it "$container_name" /bin/bash
}

# Doctor - health check
cmd_doctor() {
    # Use the comprehensive doctor script
    source "$SCRIPT_DIR/scripts/doctor.sh"
    run_doctor
}

# Traces - inspect distributed traces
cmd_traces() {
    local trace_id=$1

    if [[ -n "$trace_id" ]]; then
        # Inspect specific trace
        print_header "Trace: $trace_id"
        echo ""

        local response

        # First try direct lookup
        response=$(curl -sf "http://localhost:16686/api/traces/$trace_id" 2>/dev/null)

        # If not found and looks like our app.traceId, search by tag
        if [[ -z "$response" ]] || echo "$response" | grep -q '"data":\[\]'; then
            if [[ "$trace_id" =~ ^000e ]]; then
                print_info "Searching by app.traceId..."
                local encoded_tags=$(python3 -c "import urllib.parse; print(urllib.parse.quote('{\"app.traceId\":\"$trace_id\"}'))")
                response=$(curl -sf "http://localhost:16686/api/traces?service=counter-application&tags=$encoded_tags&limit=1" 2>/dev/null)
            fi
        fi

        if [[ $? -ne 0 ]] || [[ -z "$response" ]]; then
            print_error "Cannot fetch trace from Jaeger"
            print_info "Make sure Jaeger is running: http://localhost:16686"
            exit 1
        fi

        echo "$response" | python3 -c "
import json, sys
data = json.load(sys.stdin)
if not data.get('data') or len(data['data']) == 0:
    print('Trace not found')
    sys.exit(1)

trace = data['data'][0]
spans = sorted(trace['spans'], key=lambda s: s['startTime'])
base_time = spans[0]['startTime']
prev_end = base_time

print(f'Total spans: {len(spans)}')
print()
print(f'{\"Time\":>8}  {\"Service\":<16} {\"Operation\":<40} {\"Duration\":>10}  Gap')
print('-' * 90)

for span in spans:
    proc = trace['processes'].get(span['processID'], {})
    svc = proc.get('serviceName', 'unknown')[:16]
    start_ms = (span['startTime'] - base_time) / 1000
    dur_ms = span['duration'] / 1000
    end_time = span['startTime'] + span['duration']
    gap_ms = (span['startTime'] - prev_end) / 1000 if span['startTime'] > prev_end else 0
    gap_str = f'{gap_ms:.1f}ms' if gap_ms > 5 else ''
    print(f'{start_ms:7.1f}ms  {svc:<16} {span[\"operationName\"]:<40} {dur_ms:8.2f}ms  {gap_str}')
    prev_end = max(prev_end, end_time)
"
        echo ""
        print_info "View in Jaeger: http://localhost:16686/trace/$trace_id"
    else
        # Show recent traces with coverage summary
        print_header "Recent Traces"
        echo ""

        local response
        response=$(curl -sf "http://localhost:16686/api/traces?service=gateway&limit=10&lookback=10m" 2>/dev/null)

        if [[ $? -ne 0 ]] || [[ -z "$response" ]]; then
            print_error "Cannot fetch traces from Jaeger"
            print_info "Make sure Jaeger is running: http://localhost:16686"
            exit 1
        fi

        echo "$response" | python3 -c "
import json, sys
from datetime import datetime

data = json.load(sys.stdin)
traces = data.get('data', [])

if not traces:
    print('No traces found. Perform some actions in the UI first.')
    sys.exit(0)

print(f'{\"TraceID\":<34} {\"Spans\":>5}  {\"Services\":<45} {\"Time\"}')
print('-' * 100)

for trace in traces:
    services = set()
    ops = []
    for span in trace['spans']:
        proc = trace['processes'].get(span['processID'], {})
        services.add(proc.get('serviceName', 'unknown'))
        ops.append(span['operationName'])

    # Determine trace type
    trace_type = 'health' if len(trace['spans']) < 8 else 'action'
    if 'kafka.publish' in ops or 'flink.process_event' in ops:
        trace_type = 'action'

    svc_list = ', '.join(sorted(services))[:45]
    time_str = datetime.fromtimestamp(trace['spans'][0]['startTime']/1000000).strftime('%H:%M:%S')

    marker = '*' if trace_type == 'action' else ' '
    print(f'{marker}{trace[\"traceID\"]:<33} {len(trace[\"spans\"]):>5}  {svc_list:<45} {time_str}')

print()
print('* = counter action trace (full pipeline)')
"
        echo ""
        print_info "Inspect a trace: ./cli.sh traces <traceId>"
        print_info "Open Jaeger UI: http://localhost:16686"
    fi
}

# Send - send a test event through the pipeline
cmd_send() {
    local session="${1:-cli-test}"
    local action="${2:-increment}"
    local value="${3:-1}"

    print_header "Sending Event"
    echo ""
    print_info "Session: $session | Action: $action | Value: $value"
    echo ""

    local response
    response=$(curl -sf -X POST "http://localhost:8080/api/counter" \
        -H "Content-Type: application/json" \
        -d "{\"sessionId\":\"$session\",\"action\":\"$action\",\"value\":$value}" 2>/dev/null)

    if [[ $? -ne 0 ]] || [[ -z "$response" ]]; then
        print_error "Cannot connect to gateway"
        print_info "Make sure the system is running: ./cli.sh start"
        exit 1
    fi

    local event_id trace_id otel_trace_id
    event_id=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('eventId',''))" 2>/dev/null)
    trace_id=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('traceId',''))" 2>/dev/null)
    otel_trace_id=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('otelTraceId',''))" 2>/dev/null)

    print_success "Event sent!"
    echo ""
    echo "  Event ID:      $event_id"
    echo "  App Trace ID:  $trace_id"
    echo "  OTel Trace ID: $otel_trace_id"
    echo ""
    print_info "Inspect trace in Jaeger:"
    echo "  ./cli.sh traces $otel_trace_id"
    echo ""
    print_info "Search logs by app.traceId:"
    echo "  ./cli.sh search $trace_id"
}

# Search - find logs by traceId in Loki
cmd_search() {
    local trace_id="$1"
    local service="${2:-all}"

    if [[ -z "$trace_id" ]]; then
        # Show recent logs
        print_header "Recent Logs"
        echo ""

        local query='{compose_service=~"gateway|flink-taskmanager|drools|application"}'
        local end_ns=$(($(date +%s) * 1000000000))
        local start_ns=$(( ($(date +%s) - 300) * 1000000000 ))

        local response
        response=$(curl -sf -G "http://localhost:3100/loki/api/v1/query_range" \
            --data-urlencode "query=$query" \
            --data-urlencode "start=$start_ns" \
            --data-urlencode "end=$end_ns" \
            --data-urlencode "limit=20" 2>/dev/null)

        if [[ $? -ne 0 ]] || [[ -z "$response" ]]; then
            print_error "Cannot connect to Loki"
            print_info "Make sure Loki is running: ./cli.sh start"
            exit 1
        fi

        echo "$response" | python3 -c "
import json, sys
from datetime import datetime

data = json.load(sys.stdin)
if data.get('status') != 'success':
    print('Error:', data)
    sys.exit(1)

entries = []
for stream in data.get('data', {}).get('result', []):
    svc = stream.get('stream', {}).get('compose_service', 'unknown')
    for ts, line in stream.get('values', []):
        entries.append((int(ts), svc, line))

entries.sort()
if not entries:
    print('No recent logs found')
    sys.exit(0)

print(f'Found {len(entries)} log entries')
print()
print(f'{\"Time\":<12} {\"Service\":<20} Message')
print('-' * 80)

for ts, svc, line in entries[-20:]:
    t = datetime.fromtimestamp(int(ts) / 1e9).strftime('%H:%M:%S.%f')[:12]
    # Try to extract message from JSON
    try:
        log = json.loads(line)
        msg = log.get('message', log.get('msg', line))[:60]
        level = log.get('level', '')[:5]
        if level:
            msg = f'[{level.upper()}] {msg}'
    except:
        msg = line[:60]
    print(f'{t:<12} {svc[:20]:<20} {msg}')
"
        echo ""
        print_info "Search by traceId: ./cli.sh logs <traceId>"
    else
        # Search for specific traceId
        print_header "Logs for Trace: $trace_id"
        echo ""

        local query="{compose_service=~\"gateway|flink-taskmanager|drools|application\"} |~ \"$trace_id\""
        local end_ns=$(($(date +%s) * 1000000000))
        local start_ns=$(( ($(date +%s) - 600) * 1000000000 ))

        local response
        response=$(curl -sf -G "http://localhost:3100/loki/api/v1/query_range" \
            --data-urlencode "query=$query" \
            --data-urlencode "start=$start_ns" \
            --data-urlencode "end=$end_ns" \
            --data-urlencode "limit=50" 2>/dev/null)

        if [[ $? -ne 0 ]] || [[ -z "$response" ]]; then
            print_error "Cannot connect to Loki"
            exit 1
        fi

        echo "$response" | python3 -c "
import json, sys
from datetime import datetime

data = json.load(sys.stdin)
if data.get('status') != 'success':
    print('Error querying Loki')
    sys.exit(1)

entries = []
services = set()
for stream in data.get('data', {}).get('result', []):
    svc = stream.get('stream', {}).get('compose_service', 'unknown')
    services.add(svc)
    for ts, line in stream.get('values', []):
        entries.append((int(ts), svc, line))

entries.sort()
if not entries:
    print('No logs found for this traceId')
    print()
    print('Tips:')
    print('  - Check if the trace is recent (within 10 minutes)')
    print('  - Verify the traceId is correct')
    sys.exit(0)

svc_list = \", \".join(sorted(services))
print(f'Found {len(entries)} logs from {len(services)} services: {svc_list}')
print()
print(f'{\"Time\":<12} {\"Service\":<20} Message')
print('-' * 80)

for ts, svc, line in entries:
    t = datetime.fromtimestamp(int(ts) / 1e9).strftime('%H:%M:%S.%f')[:12]
    try:
        log = json.loads(line)
        msg = log.get('message', log.get('msg', line))[:60]
        level = log.get('level', '')[:5]
        if level:
            msg = f'[{level.upper()}] {msg}'
    except:
        msg = line[:60]
    print(f'{t:<12} {svc[:20]:<20} {msg}')
"
        echo ""
    fi
}

# E2E Test
cmd_e2e() {
    print_header "End-to-End Test"
    echo ""

    # First run doctor to ensure all services are up
    print_info "Checking service health..."
    if ! cmd_doctor &>/dev/null; then
        print_error "Services are not healthy. Run './cli.sh doctor' for details"
        exit 1
    fi
    print_success "All services are healthy"
    echo ""

    # Run the e2e test script
    if [[ -f "$SCRIPT_DIR/scripts/e2e-test.sh" ]]; then
        source "$SCRIPT_DIR/scripts/e2e-test.sh"
        run_e2e_test
    else
        print_warning "E2E test script not found"
        print_info "Basic connectivity test passed"
    fi
}

# Compile Drools
cmd_compile() {
    local target=$1
    if [[ "$target" != "drools" ]]; then
        print_error "Unknown compile target: $target"
        print_info "Usage: ./cli.sh compile drools"
        exit 1
    fi

    print_header "Compiling Drools Rules"
    docker compose exec drools /app/compile-rules.sh 2>/dev/null || \
        docker compose restart drools
    print_success "Drools rules recompiled"
}

# Down - stop and remove
cmd_down() {
    print_header "Stopping and removing all containers"
    docker compose down
    print_success "All containers removed"
}

# Clean - remove everything
cmd_clean() {
    print_header "Cleaning all containers, images, and volumes"
    docker compose down -v --rmi local
    print_success "Cleaned up"
}

# Dev mode - optimized for development iteration
cmd_dev() {
    print_header "Development Mode"
    echo ""
    print_info "Starting with development optimizations..."
    echo ""

    # Check if infra is already running
    if infra_running; then
        print_success "Infrastructure already running"
    else
        print_info "Starting infrastructure first..."
        start_infra
        wait_for_infra
    fi

    # Start app services (with smart rebuild)
    print_info "Starting application services..."
    docker compose up -d --build $APP_SERVICES flink-job-submitter kafka-init

    echo ""
    print_success "Dev environment ready!"
    echo ""
    print_info "Quick commands for iteration:"
    echo "  ./cli.sh quick gateway   # Restart gateway (no rebuild)"
    echo "  ./cli.sh quick ui        # Restart UI (no rebuild)"
    echo "  ./cli.sh rebuild ui      # Rebuild and restart UI"
    echo "  ./cli.sh logs gateway    # Watch gateway logs"
    echo ""
}

# Watch mode placeholder
cmd_watch() {
    local service=$1
    if [[ -z "$service" ]]; then
        print_error "Please specify a service to watch"
        exit 1
    fi
    print_warning "Watch mode not yet implemented"
    print_info "For now, use: ./cli.sh logs $service"
}

# Memory diagnostics
cmd_memory() {
    local subcmd="${1:-overview}"
    shift 2>/dev/null || true
    source "$SCRIPT_DIR/scripts/memory-diagnostics.sh" "$subcmd" "$@"
}

# Replay - replay events with full tracing for debugging
cmd_replay() {
    local session_id="$1"
    local up_to_event="$2"

    if [[ -z "$session_id" ]]; then
        echo ""
        print_header "Event Replay"
        echo ""
        echo "Replay historical events through the FSM with full tracing."
        echo "Creates a new trace in Jaeger showing every state transition."
        echo ""
        echo "Usage:"
        echo "  ./cli.sh p replay <sessionId>              # Replay all events for session"
        echo "  ./cli.sh p replay <sessionId> <eventId>    # Replay up to specific event"
        echo ""
        echo "Examples:"
        echo "  ./cli.sh p replay default                  # Replay 'default' session"
        echo "  ./cli.sh p replay session-123 req-456      # Replay up to event req-456"
        echo ""
        echo "Other commands:"
        echo "  ./cli.sh p replay events <sessionId>       # List events without replaying"
        echo "  ./cli.sh p replay history <sessionId>      # Show state history"
        echo ""
        return 0
    fi

    # Handle subcommands
    if [[ "$session_id" == "events" ]]; then
        local target_session="$up_to_event"
        if [[ -z "$target_session" ]]; then
            print_error "Usage: ./cli.sh p replay events <sessionId>"
            return 1
        fi
        print_info "Fetching events for session: $target_session"
        curl -s "http://localhost:8080/api/replay/session/$target_session/events" | jq . 2>/dev/null || \
            curl -s "http://localhost:8080/api/replay/session/$target_session/events"
        return 0
    fi

    if [[ "$session_id" == "history" ]]; then
        local target_session="$up_to_event"
        if [[ -z "$target_session" ]]; then
            print_error "Usage: ./cli.sh p replay history <sessionId>"
            return 1
        fi
        print_info "Fetching state history for session: $target_session"
        curl -s "http://localhost:8080/api/replay/session/$target_session/history" | jq . 2>/dev/null || \
            curl -s "http://localhost:8080/api/replay/session/$target_session/history"
        return 0
    fi

    # Replay session
    print_header "Replaying Session: $session_id"
    echo ""

    local url="http://localhost:8080/api/replay/session/$session_id"
    if [[ -n "$up_to_event" ]]; then
        url="$url?upToEvent=$up_to_event"
        print_info "Replaying up to event: $up_to_event"
    fi

    local response
    response=$(curl -s -X POST "$url")

    if echo "$response" | grep -q '"success":true'; then
        local trace_id=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('replayTraceId',''))" 2>/dev/null)
        local events=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('eventsReplayed',0))" 2>/dev/null)
        local duration=$(echo "$response" | python3 -c "import sys,json; print(json.load(sys.stdin).get('durationMs',0))" 2>/dev/null)

        echo ""
        print_success "Replay completed!"
        echo ""
        echo "  Events replayed: $events"
        echo "  Duration: ${duration}ms"
        echo "  Trace ID: $trace_id"
        echo ""

        # Show state summary
        echo "Initial state:"
        echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"  value={d.get('initialState',{}).get('value',0)}, alert={d.get('initialState',{}).get('alert','NONE')}\")" 2>/dev/null

        echo "Final state:"
        echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"  value={d.get('finalState',{}).get('value',0)}, alert={d.get('finalState',{}).get('alert','NONE')}\")" 2>/dev/null

        echo ""
        print_info "View trace in Jaeger:"
        echo "  http://localhost:16686/trace/$trace_id"
        echo ""
        print_info "Or run: ./cli.sh p traces $trace_id"
    else
        print_error "Replay failed"
        echo "$response" | jq . 2>/dev/null || echo "$response"
    fi
}

# Quick diagnostic - run a single request and analyze the trace
cmd_diagnose() {
    print_header "Quick Diagnostic"
    echo ""

    # Check if application is running (gateway is on port 8080)
    if ! curl -s "http://localhost:8080/actuator/health" > /dev/null 2>&1; then
        print_error "Application not running. Start with: ./cli.sh start"
        exit 1
    fi

    print_info "Running diagnostic request..."

    # Call the diagnostic endpoint (via gateway on port 8080)
    # This endpoint waits ~8s for trace propagation, so needs longer timeout
    local response
    response=$(curl -s -X POST "http://localhost:8080/api/diagnostic/run" \
        -H "Content-Type: application/json" \
        --connect-timeout 10 \
        --max-time 60)

    if [[ $? -ne 0 ]]; then
        print_error "Failed to reach diagnostic endpoint"
        exit 1
    fi

    local success=$(echo "$response" | jq -r '.success // false')
    local trace_id=$(echo "$response" | jq -r '.actualTraceId // "unknown"')
    local request_id=$(echo "$response" | jq -r '.actualRequestId // "unknown"')

    echo ""
    print_info "Request ID: $request_id"
    print_info "Trace ID: $trace_id"
    echo ""

    if [[ "$success" == "true" ]]; then
        print_success "E2E trace propagation verified!"
        echo ""

        # Show validation summary
        local span_count=$(echo "$response" | jq -r '.validation.spanCount // 0')
        local services=$(echo "$response" | jq -r '.validation.presentServices | join(", ")')
        local log_count=$(echo "$response" | jq -r '.logCount // 0')

        echo "╔══════════════════════════════════════════════════════════════╗"
        echo "║                    DIAGNOSTIC SUMMARY                         ║"
        echo "╠══════════════════════════════════════════════════════════════╣"
        printf "║ Spans: %-5d   Logs: %-5d                                   ║\n" "$span_count" "$log_count"
        echo "╠══════════════════════════════════════════════════════════════╣"
        echo "║ SERVICES TRACED                                               ║"
        echo "╠══════════════════════════════════════════════════════════════╣"

        # Show per-service span counts
        echo "$response" | jq -r '.validation.spanCountByService | to_entries | .[] | "║   \(.key): \(.value) spans"' 2>/dev/null | while read line; do
            printf "%-65s║\n" "$line"
        done

        echo "╠══════════════════════════════════════════════════════════════╣"
        echo "║ OPERATIONS                                                     ║"
        echo "╠══════════════════════════════════════════════════════════════╣"

        # Show operations
        echo "$response" | jq -r '.validation.operations[]' 2>/dev/null | head -10 | while read op; do
            printf "║   %-60s║\n" "$op"
        done

        echo "╚══════════════════════════════════════════════════════════════╝"
        echo ""

        # Show logs if available
        local log_count=$(echo "$response" | jq -r '.logCount // 0')
        if [[ "$log_count" -gt 0 ]]; then
            print_info "Sample logs:"
            echo "$response" | jq -r '.logs[:5][] | "  [\(.service)] \(.line | split("\n")[0][:80])"' 2>/dev/null
        fi

        echo ""
        print_info "View full trace: http://localhost:16686/trace/$trace_id"
        print_info "For detailed benchmark: ./cli.sh benchmark full --duration 10"

    else
        print_error "Diagnostic failed"
        local error=$(echo "$response" | jq -r '.error // "Unknown error"')
        local missing=$(echo "$response" | jq -r '.validation.missingServices // []')

        echo ""
        echo "Error: $error"
        if [[ "$missing" != "[]" ]]; then
            echo "Missing services: $missing"
        fi

        echo ""
        print_info "Troubleshooting:"
        echo "  1. Check if all services are running: ./cli.sh status"
        echo "  2. Check logs: ./cli.sh logs application"
        echo "  3. Check Jaeger: http://localhost:16686"
    fi
}

# Benchmark control - Java benchmark runner
cmd_benchmark() {
    local subcmd="${1:-help}"
    shift 2>/dev/null || true

    # Collect remaining args for the Java script
    local args=""
    while [[ $# -gt 0 ]]; do
        args="$args $1"
        shift
    done

    case "$subcmd" in
        run|all)
            # Run all benchmarks via Java script
            "$SCRIPT_DIR/scripts/run-benchmarks-java.sh" all $args
            ;;
        http|kafka|flink|drools|gateway|full)
            # Run specific component benchmark
            "$SCRIPT_DIR/scripts/run-benchmarks-java.sh" "$subcmd" $args
            ;;
        report)
            # Open reports
            open_benchmark_reports
            ;;
        doctor)
            # Run benchmark observability diagnostics
            "$SCRIPT_DIR/scripts/benchmark-doctor.sh"
            ;;
        diagnose)
            # Run quick diagnostic with detailed trace analysis
            cmd_diagnose "$@"
            ;;
        history)
            # Benchmark history management
            local history_cmd="${1:-list}"
            shift 2>/dev/null || true
            "$SCRIPT_DIR/scripts/benchmark-history.sh" "$history_cmd" "$@"
            ;;
        compare)
            # Compare current results with baseline
            "$SCRIPT_DIR/scripts/benchmark-history.sh" compare "$1"
            ;;
        save)
            # Save current results to history
            "$SCRIPT_DIR/scripts/benchmark-history.sh" save
            ;;
        help|*)
            echo ""
            print_header "Benchmark System"
            echo ""
            echo "Run benchmarks via Java benchmark runner:"
            echo ""
            echo "  Component benchmarks:"
            echo "    ./cli.sh benchmark http      # HTTP endpoint latency"
            echo "    ./cli.sh benchmark kafka     # Kafka produce/consume"
            echo "    ./cli.sh benchmark flink     # Flink stream processing"
            echo "    ./cli.sh benchmark drools    # Drools rule evaluation"
            echo "    ./cli.sh benchmark gateway   # Gateway (HTTP + Kafka)"
            echo "    ./cli.sh benchmark full      # Full E2E pipeline"
            echo ""
            echo "  Run all:"
            echo "    ./cli.sh benchmark all       # Run all component benchmarks"
            echo ""
            echo "  Reports:"
            echo "    ./cli.sh benchmark report    # Open HTML reports"
            echo ""
            echo "  History & Regression:"
            echo "    ./cli.sh benchmark history   # List benchmark history"
            echo "    ./cli.sh benchmark save      # Save current results"
            echo "    ./cli.sh benchmark compare   # Compare with baseline"
            echo "    ./cli.sh benchmark compare <sha>  # Compare with commit"
            echo ""
            echo "  Diagnostics:"
            echo "    ./cli.sh benchmark diagnose  # Quick single-request trace analysis"
            echo "    ./cli.sh benchmark doctor    # Check observability health"
            echo ""
            echo "Options:"
            echo "  --duration, -d <sec>    Benchmark duration (default: 60)"
            echo "  --concurrency, -c <n>   Concurrent workers (default: 8)"
            echo "  --quick, -q             Quick mode: 5s, skip enrichment"
            echo "  --skip-enrichment       Skip trace/log fetching"
            echo ""
            echo "Examples:"
            echo "  ./cli.sh benchmark full --quick           # Fast feedback (~8s)"
            echo "  ./cli.sh benchmark all -d 60              # Run all benchmarks"
            echo "  ./cli.sh benchmark drools -d 30           # 30-second drools"
            echo "  ./cli.sh benchmark history                # View history"
            echo "  ./cli.sh benchmark compare                # Compare with baseline"
            echo "  ./cli.sh benchmark doctor                 # Validate observability"
            echo "  ./cli.sh benchmark report                 # View reports"
            echo ""
            ;;
    esac
}

# Get Maven command (use docker if mvn not available)
get_maven_cmd() {
    if command -v mvn &>/dev/null; then
        echo "mvn"
    else
        # Use Maven Docker image
        echo "docker run --rm -v $SCRIPT_DIR:/work -v $HOME/.m2:/root/.m2 -w /work maven:3.9-eclipse-temurin-21"
    fi
}

# Run platform benchmark (JMH)
run_platform_benchmark() {
    local benchmark_class="$1"
    local quick="$2"
    local verbose="$3"

    start_timer

    print_header "Platform Benchmark: $benchmark_class"
    echo ""

    # Check if platform module exists
    if [[ ! -f "$SCRIPT_DIR/platform/pom.xml" ]]; then
        print_error "Platform module not found"
        print_info "Expected: $SCRIPT_DIR/platform/pom.xml"
        end_timer "benchmark" "$benchmark_class" "error"
        return 1
    fi

    local maven_cmd=$(get_maven_cmd)

    # Build maven args (use benchmark profile to include benchmark tests)
    local maven_args="-f platform/pom.xml test -Pbenchmark -Dtest=$benchmark_class"

    if [[ "$quick" == "true" ]]; then
        maven_args="$maven_args -Djmh.warmup.iterations=1 -Djmh.iterations=3"
        print_info "Running in quick mode (fewer iterations)"
    fi

    if [[ "$verbose" != "true" ]]; then
        maven_args="$maven_args -q"
    fi

    echo ""
    print_info "Running: $maven_cmd $maven_args"
    echo ""

    # Run the benchmark
    if $maven_cmd $maven_args; then
        print_success "Benchmark completed"

        # Check for generated report
        local report_file="$SCRIPT_DIR/platform/target/benchmark-report.html"
        if [[ -f "$report_file" ]]; then
            echo ""
            print_info "Report generated: $report_file"

            # Open in browser
            if command -v open &>/dev/null; then
                open "$report_file"
            elif command -v xdg-open &>/dev/null; then
                xdg-open "$report_file"
            fi
        fi

        end_timer "benchmark" "$benchmark_class" "success"
    else
        print_error "Benchmark failed"
        end_timer "benchmark" "$benchmark_class" "error"
        return 1
    fi
}

# Run E2E benchmark (JUnit - requires system running)
run_e2e_benchmark() {
    local verbose="$1"

    start_timer

    print_header "End-to-End Benchmark"
    echo ""

    # Check if system is running
    print_info "Checking if system is running..."
    if ! curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
        print_error "System is not running"
        print_info "Start the system first: ./cli.sh start"
        end_timer "benchmark" "e2e" "error"
        return 1
    fi
    print_success "System is healthy"
    echo ""

    # Check if application module exists
    if [[ ! -f "$SCRIPT_DIR/application/pom.xml" ]]; then
        print_error "Application module not found"
        print_info "Expected: $SCRIPT_DIR/application/pom.xml"
        end_timer "benchmark" "e2e" "error"
        return 1
    fi

    local maven_cmd=$(get_maven_cmd)

    # For Docker, we need to use host.docker.internal to reach localhost services
    local gateway_url="http://localhost:8080"
    if [[ "$maven_cmd" == docker* ]]; then
        gateway_url="http://host.docker.internal:8080"
        # Add network host for Docker
        maven_cmd="docker run --rm --add-host=host.docker.internal:host-gateway -v $SCRIPT_DIR:/work -v $HOME/.m2:/root/.m2 -w /work maven:3.9-eclipse-temurin-21"
    fi

    # Build maven args
    local maven_args="-f application/pom.xml test -Pe2e-benchmark -Dtest=EndToEndBenchmark"
    maven_args="$maven_args -DGATEWAY_URL=$gateway_url"

    if [[ "$verbose" != "true" ]]; then
        maven_args="$maven_args -q"
    fi

    echo ""
    print_info "Running: $maven_cmd $maven_args"
    echo ""

    # Run the benchmark
    if $maven_cmd $maven_args; then
        print_success "E2E Benchmark completed"

        # Check for generated report
        local report_file="$SCRIPT_DIR/application/target/e2e-benchmark-report.html"
        if [[ -f "$report_file" ]]; then
            echo ""
            print_info "Report generated: $report_file"

            # Open in browser
            if command -v open &>/dev/null; then
                open "$report_file"
            elif command -v xdg-open &>/dev/null; then
                xdg-open "$report_file"
            fi
        fi

        end_timer "benchmark" "e2e" "success"
    else
        print_error "E2E Benchmark failed"
        end_timer "benchmark" "e2e" "error"
        return 1
    fi
}

# Run all Maven benchmarks
run_all_maven_benchmarks() {
    local quick="$1"
    local verbose="$2"

    start_timer

    print_header "Running All Benchmarks"
    echo ""
    print_info "This will run platform and application benchmarks."
    echo ""

    local all_passed=true

    # Platform: Serialization benchmark
    print_info "1/3: Serialization Benchmark"
    if run_platform_benchmark "SerializationBenchmark" "$quick" "$verbose"; then
        print_success "Serialization benchmark passed"
    else
        print_warning "Serialization benchmark failed"
        all_passed=false
    fi
    echo ""

    # Platform: Kafka benchmark
    print_info "2/3: Kafka Benchmark"
    if run_platform_benchmark "KafkaBenchmark" "$quick" "$verbose"; then
        print_success "Kafka benchmark passed"
    else
        print_warning "Kafka benchmark failed"
        all_passed=false
    fi
    echo ""

    # Application: E2E benchmark (only if system is running)
    print_info "3/3: End-to-End Benchmark"
    if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
        if run_e2e_benchmark "$verbose"; then
            print_success "E2E benchmark passed"
        else
            print_warning "E2E benchmark failed"
            all_passed=false
        fi
    else
        print_warning "Skipping E2E benchmark (system not running)"
        print_info "Start system with: ./cli.sh start"
    fi
    echo ""

    # Summary
    print_header "Benchmark Summary"
    if [[ "$all_passed" == "true" ]]; then
        print_success "All benchmarks completed successfully"
    else
        print_warning "Some benchmarks failed or were skipped"
    fi

    echo ""
    print_info "Reports location:"
    echo "  Platform:    $SCRIPT_DIR/platform/target/"
    echo "  Application: $SCRIPT_DIR/application/target/"

    end_timer "benchmark" "all" "success"
}

# Open benchmark reports
open_benchmark_reports() {
    print_header "Benchmark Reports"
    echo ""

    local found=false

    # Check for platform benchmark reports
    if [[ -f "$SCRIPT_DIR/platform/target/benchmark-report.html" ]]; then
        print_info "Platform benchmark report: platform/target/benchmark-report.html"
        found=true
        if command -v open &>/dev/null; then
            open "$SCRIPT_DIR/platform/target/benchmark-report.html"
        fi
    fi

    # Check for E2E benchmark reports
    if [[ -f "$SCRIPT_DIR/application/target/e2e-benchmark-report.html" ]]; then
        print_info "E2E benchmark report: application/target/e2e-benchmark-report.html"
        found=true
        if command -v open &>/dev/null; then
            open "$SCRIPT_DIR/application/target/e2e-benchmark-report.html"
        fi
    fi

    # Check for legacy report dashboard
    if [[ -f "$SCRIPT_DIR/reports/index.html" ]]; then
        print_info "Legacy report dashboard: reports/index.html"
        found=true
        if command -v open &>/dev/null; then
            open "$SCRIPT_DIR/reports/index.html"
        fi
    fi

    if [[ "$found" == "false" ]]; then
        print_warning "No benchmark reports found"
        echo ""
        print_info "Run a benchmark first:"
        echo "  ./cli.sh benchmark serialization    # Platform serialization"
        echo "  ./cli.sh benchmark kafka            # Platform Kafka"
        echo "  ./cli.sh benchmark e2e              # End-to-end (requires system)"
    fi
}

# Main command router
case "${1:-help}" in
    # Namespaces
    platform|p)
        cmd_platform "$2" "${@:3}"
        ;;
    app|a)
        cmd_app "$2" "${@:3}"
        ;;

    # Lifecycle commands
    start|up)
        cmd_start "$2"
        ;;
    stop)
        cmd_stop "$2"
        ;;
    restart)
        cmd_restart "$2"
        ;;
    quick)
        cmd_quick "$2"
        ;;
    rebuild)
        cmd_rebuild "$2"
        ;;
    full-rebuild)
        cmd_full_rebuild "$2"
        ;;
    build)
        cmd_build "$2"
        ;;
    logs)
        cmd_logs "$2"
        ;;
    status)
        cmd_status
        ;;
    down)
        cmd_down
        ;;
    clean)
        cmd_clean
        ;;

    # Development commands
    shell)
        cmd_shell "$2"
        ;;
    dev)
        cmd_dev
        ;;
    watch)
        cmd_watch "$2"
        ;;
    compile)
        cmd_compile "$2"
        ;;

    # Legacy commands (still work for backwards compatibility)
    doctor)
        cmd_doctor
        ;;
    traces)
        cmd_traces "$2"
        ;;
    send)
        cmd_send "$2" "$3" "$4"
        ;;
    search)
        cmd_search "$2"
        ;;
    e2e)
        cmd_e2e
        ;;
    memory|mem)
        cmd_memory "$2" "$3" "$4"
        ;;
    benchmark|bench)
        cmd_benchmark "$2" "${@:3}"
        ;;

    # Help
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac
