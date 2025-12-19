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
    echo "Usage: ./cli.sh <command> [service] [options]"
    echo ""
    echo "Quick Commands:"
    echo "  start [service]     Start all services or a specific service"
    echo "  stop [service]      Stop all services or a specific service"
    echo "  restart [service]   Restart services (no rebuild)"
    echo "  quick <service>     Quick restart without rebuild (fastest)"
    echo "  rebuild <service>   Smart rebuild (cached deps, fast for code changes)"
    echo "  full-rebuild <svc>  Full rebuild with no cache (for dep changes)"
    echo ""
    echo "Development:"
    echo "  dev                 Start in dev mode (faster rebuilds)"
    echo "  watch <service>     Watch and auto-restart on changes"
    echo ""
    echo "Operations:"
    echo "  build [service]     Build Docker images"
    echo "  logs [service]      View logs (follows)"
    echo "  status              Show running services"
    echo "  shell <service>     Enter container shell"
    echo "  doctor              Health check all services"
    echo "  traces [id]         Show trace timeline (or inspect specific trace)"
    echo "  e2e                 Run end-to-end test"
    echo "  compile drools      Recompile Drools rules"
    echo "  memory [cmd]        Memory diagnostics (overview/jvm/watch/heap/jfr)"
    echo "  benchmark [comp]    Component benchmarking (http/kafka/flink/drools/gateway/full/all)"
    echo ""
    echo "Lifecycle:"
    echo "  up                  Alias for 'start'"
    echo "  down                Stop and remove all containers"
    echo "  clean               Remove all containers, images, and volumes"
    echo "  help                Show this help message"
    echo ""
    echo "Services: ui, gateway, flink, drools, kafka, zookeeper, jaeger, grafana, otel-collector"
    echo ""
    echo "URLs:"
    echo "  http://localhost:3000    UI Portal"
    echo "  http://localhost:16686   Jaeger (Traces)"
    echo "  http://localhost:3001    Grafana (Dashboards)"
    echo "  http://localhost:8081    Flink Dashboard"
    echo ""
    echo "Examples:"
    echo "  ./cli.sh start              # Start all services"
    echo "  ./cli.sh quick gateway      # Quick restart gateway (no rebuild)"
    echo "  ./cli.sh rebuild ui         # Rebuild and restart UI"
    echo "  ./cli.sh logs gateway       # View gateway logs"
    echo "  ./cli.sh doctor             # Check health of all services"
    echo ""
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
        response=$(curl -sf "http://localhost:16686/api/traces/$trace_id" 2>/dev/null)

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

# Benchmark control - Component benchmarking system
cmd_benchmark() {
    local subcmd="${1:-help}"
    shift 2>/dev/null || true
    local api_key="${ADMIN_API_KEY:-reactive-admin-key}"

    # Parse common options
    local duration=30
    local events=0
    local report=false
    local output_dir="$SCRIPT_DIR/reports"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --duration)
                duration="$2"
                shift 2
                ;;
            --events)
                events="$2"
                shift 2
                ;;
            --report)
                report=true
                shift
                ;;
            --output)
                output_dir="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    # Valid components
    local valid_components="http kafka flink drools gateway full"

    case "$subcmd" in
        http|kafka|flink|drools|gateway|full)
            # Component benchmark
            run_component_benchmark "$subcmd" "$duration" "$events" "$report" "$output_dir"
            ;;
        all)
            # Run all component benchmarks
            run_all_benchmarks "$duration" "$report" "$output_dir"
            ;;
        report)
            # Open report dashboard
            open_benchmark_reports
            ;;
        stop)
            print_header "Stopping Benchmark"
            # Try to stop any running benchmark
            for component in $valid_components; do
                curl -sf -X POST "http://localhost:8080/api/admin/benchmark/${component}/stop" \
                    -H "x-api-key: $api_key" 2>/dev/null
            done
            print_success "Benchmark stopped"
            ;;
        status)
            print_header "Benchmark Status"
            local response
            response=$(curl -sf "http://localhost:8080/api/admin/benchmark/status" \
                -H "x-api-key: $api_key" 2>/dev/null)

            if [[ $? -eq 0 ]]; then
                echo "$response" | jq . 2>/dev/null || echo "$response"
            else
                print_error "Cannot get benchmark status"
                print_info "Make sure gateway is running"
            fi
            ;;
        results)
            print_header "All Benchmark Results"
            local response
            response=$(curl -sf "http://localhost:8080/api/admin/benchmark/results" \
                -H "x-api-key: $api_key" 2>/dev/null)

            if [[ $? -eq 0 ]]; then
                echo "$response" | jq . 2>/dev/null || echo "$response"
            else
                print_error "Cannot get benchmark results"
                print_info "Make sure gateway is running"
            fi
            ;;
        help|*)
            echo ""
            print_header "Component Benchmarking System"
            echo ""
            echo "Benchmark each component independently:"
            echo ""
            echo "  Component benchmarks:"
            echo "    ./cli.sh benchmark http       # HTTP endpoint latency"
            echo "    ./cli.sh benchmark kafka      # Kafka round-trip"
            echo "    ./cli.sh benchmark flink      # Kafka + Flink processing"
            echo "    ./cli.sh benchmark drools     # Direct Drools API"
            echo "    ./cli.sh benchmark gateway    # HTTP + Kafka publish"
            echo "    ./cli.sh benchmark full       # Full end-to-end pipeline"
            echo ""
            echo "  Run all:"
            echo "    ./cli.sh benchmark all        # Run all benchmarks in sequence"
            echo ""
            echo "Options:"
            echo "  --duration <secs>   Run for N seconds (default: 30)"
            echo "  --events <count>    Run until N events processed (0 = use duration)"
            echo "  --report            Generate HTML report after completion"
            echo "  --output <dir>      Report output directory (default: ./reports)"
            echo ""
            echo "Other commands:"
            echo "  ./cli.sh benchmark report       Open report dashboard"
            echo "  ./cli.sh benchmark status       Show current benchmark status"
            echo "  ./cli.sh benchmark results      Show all benchmark results"
            echo "  ./cli.sh benchmark stop         Stop running benchmark"
            echo ""
            echo "Examples:"
            echo "  ./cli.sh benchmark http --duration 60"
            echo "  ./cli.sh benchmark full --events 10000 --report"
            echo "  ./cli.sh benchmark all --duration 30 --report"
            echo ""
            ;;
    esac
}

# Run a layered benchmark
run_layered_benchmark() {
    local layer="$1"
    local duration="$2"
    local events="$3"
    local report="$4"
    local output_dir="$5"
    local api_key="${ADMIN_API_KEY:-reactive-admin-key}"

    start_timer

    print_header "Layered Benchmark: $layer"
    echo ""

    # Determine if we need to restart services with SKIP_DROOLS
    local needs_restart=false
    local skip_drools=false

    case "$layer" in
        kafka)
            print_info "Layer 1: Kafka produce/consume only"
            print_info "  - Tests Kafka throughput in isolation"
            print_info "  - Bypasses Flink processing"
            skip_drools=true
            ;;
        kafka-flink)
            print_info "Layer 2: Kafka + Flink processing"
            print_info "  - Tests stream processing without Drools"
            print_info "  - Isolates Flink performance"
            skip_drools=true
            needs_restart=true
            ;;
        kafka-flink-drools)
            print_info "Layer 3: Kafka + Flink + Drools"
            print_info "  - Full stream processing pipeline"
            print_info "  - Tests rule evaluation performance"
            skip_drools=false
            needs_restart=true
            ;;
        full)
            print_info "Layer 4: Full end-to-end (HTTP -> Kafka -> Flink -> Drools)"
            print_info "  - Tests complete system including HTTP overhead"
            print_info "  - Production-like load test"
            skip_drools=false
            ;;
    esac
    echo ""

    # Restart Flink with appropriate SKIP_DROOLS setting if needed
    if [[ "$needs_restart" == "true" ]]; then
        print_info "Configuring Flink with SKIP_DROOLS=$skip_drools..."
        export SKIP_DROOLS="$skip_drools"

        # Restart Flink services
        docker compose stop flink-taskmanager flink-job-submitter 2>/dev/null
        docker compose up -d flink-taskmanager flink-job-submitter

        # Wait for Flink to be ready
        print_info "Waiting for Flink to be ready..."
        sleep 10

        # Check Flink health
        local flink_ready=false
        for i in {1..30}; do
            if curl -sf http://localhost:8081/overview >/dev/null 2>&1; then
                flink_ready=true
                break
            fi
            sleep 1
        done

        if [[ "$flink_ready" != "true" ]]; then
            print_error "Flink did not start in time"
            end_timer "benchmark" "$layer" "error"
            return 1
        fi
        print_success "Flink is ready"
        echo ""
    fi

    # Prepare benchmark request body
    local duration_ms=$((duration * 1000))
    local body="{\"layer\": \"$layer\", \"durationMs\": $duration_ms, \"targetEventCount\": $events}"

    print_info "Starting benchmark..."
    print_info "  Duration: ${duration}s"
    if [[ "$events" -gt 0 ]]; then
        print_info "  Target events: $events"
    fi
    echo ""

    # Start the benchmark via Gateway API
    local response
    response=$(curl -sf -X POST "http://localhost:8080/api/admin/benchmark/layered" \
        -H "x-api-key: $api_key" \
        -H "Content-Type: application/json" \
        -d "$body" 2>/dev/null)

    if [[ $? -ne 0 ]]; then
        print_error "Failed to start benchmark"
        print_info "Make sure gateway is running and healthy"
        print_info "The layered benchmark API may not be implemented yet"
        echo ""
        print_info "Falling back to basic benchmark..."

        # Fall back to basic benchmark
        response=$(curl -sf -X POST "http://localhost:8080/api/admin/benchmark/start" \
            -H "x-api-key: $api_key" \
            -H "Content-Type: application/json" \
            -d "{\"maxDurationMs\": $duration_ms}" 2>/dev/null)

        if [[ $? -eq 0 ]]; then
            print_success "Basic benchmark started"
            echo "$response" | jq . 2>/dev/null || echo "$response"
        else
            print_error "Failed to start any benchmark"
            end_timer "benchmark" "$layer" "error"
            return 1
        fi
    else
        print_success "Layered benchmark started"
        echo "$response" | jq . 2>/dev/null || echo "$response"
    fi

    echo ""

    # Monitor progress
    print_info "Monitoring progress (Ctrl+C to stop early)..."
    echo ""

    local benchmark_running=true
    local last_throughput=0

    while [[ "$benchmark_running" == "true" ]]; do
        sleep 2

        local status
        status=$(curl -sf "http://localhost:8080/api/admin/benchmark/status" \
            -H "x-api-key: $api_key" 2>/dev/null)

        if [[ $? -eq 0 ]]; then
            local running
            running=$(echo "$status" | jq -r '.running // false' 2>/dev/null)

            if [[ "$running" == "false" ]]; then
                benchmark_running=false
            else
                # Show live throughput
                local throughput
                throughput=$(echo "$status" | jq -r '.lastResult.peakThroughput // 0' 2>/dev/null)
                if [[ "$throughput" != "0" ]] && [[ "$throughput" != "$last_throughput" ]]; then
                    printf "\r  Current throughput: %s events/sec    " "$throughput"
                    last_throughput="$throughput"
                fi
            fi
        else
            benchmark_running=false
        fi
    done

    echo ""
    echo ""

    # Get final results
    print_header "Benchmark Results"
    local final_status
    final_status=$(curl -sf "http://localhost:8080/api/admin/benchmark/status" \
        -H "x-api-key: $api_key" 2>/dev/null)

    if [[ $? -eq 0 ]]; then
        echo "$final_status" | jq '.lastResult // .' 2>/dev/null || echo "$final_status"
    fi

    # Generate report if requested
    if [[ "$report" == "true" ]]; then
        echo ""
        generate_benchmark_report "$layer" "$output_dir" "$final_status"
    fi

    # Restore normal operation if we modified SKIP_DROOLS
    if [[ "$needs_restart" == "true" ]] && [[ "$skip_drools" == "true" ]]; then
        echo ""
        print_info "Restoring normal Flink operation (SKIP_DROOLS=false)..."
        export SKIP_DROOLS="false"
        docker compose stop flink-taskmanager flink-job-submitter 2>/dev/null
        docker compose up -d flink-taskmanager flink-job-submitter
    fi

    end_timer "benchmark" "$layer" "success"
}

# Generate benchmark report
generate_benchmark_report() {
    local layer="$1"
    local output_dir="$2"
    local results="$3"

    local timestamp=$(date +%Y%m%d_%H%M%S)
    local commit=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    local report_dir="$output_dir/benchmark_${layer}_${commit}_${timestamp}"

    print_info "Generating benchmark report..."

    # Create report directory
    mkdir -p "$report_dir"

    # Save raw results
    echo "$results" > "$report_dir/results.json"

    # Call the report generation script if it exists
    if [[ -f "$SCRIPT_DIR/scripts/benchmark-report.sh" ]]; then
        source "$SCRIPT_DIR/scripts/benchmark-report.sh"
        generate_html_report "$layer" "$report_dir" "$results"
    else
        # Generate basic report
        generate_basic_report "$layer" "$report_dir" "$results"
    fi

    print_success "Report saved to: $report_dir"

    # Open report in browser if available
    if [[ -f "$report_dir/index.html" ]]; then
        if command -v open &>/dev/null; then
            print_info "Opening report in browser..."
            open "$report_dir/index.html"
        elif command -v xdg-open &>/dev/null; then
            xdg-open "$report_dir/index.html"
        fi
    fi
}

# Generate basic text report (fallback)
generate_basic_report() {
    local layer="$1"
    local report_dir="$2"
    local results="$3"

    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    local commit=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")

    cat > "$report_dir/summary.md" << EOF
# Benchmark Report: $layer

**Generated:** $timestamp
**Commit:** $commit
**Layer:** $layer

## Results

\`\`\`json
$(echo "$results" | jq '.lastResult // .' 2>/dev/null || echo "$results")
\`\`\`

## Layer Description

$(case "$layer" in
    kafka) echo "Layer 1: Kafka produce/consume only - Tests Kafka throughput in isolation" ;;
    kafka-flink) echo "Layer 2: Kafka + Flink processing - Tests stream processing without Drools" ;;
    kafka-flink-drools) echo "Layer 3: Kafka + Flink + Drools - Full stream processing pipeline" ;;
    full) echo "Layer 4: Full end-to-end - Tests complete system including HTTP overhead" ;;
esac)

---
Generated by Reactive System CLI
EOF

    print_success "Basic report generated: $report_dir/summary.md"
}

# Run a component benchmark (new API)
run_component_benchmark() {
    local component="$1"
    local duration="$2"
    local events="$3"
    local report="$4"
    local output_dir="$5"
    local api_key="${ADMIN_API_KEY:-reactive-admin-key}"

    start_timer

    # Component descriptions
    local desc=""
    case "$component" in
        http) desc="HTTP endpoint latency" ;;
        kafka) desc="Kafka produce/consume round-trip" ;;
        flink) desc="Kafka + Flink processing" ;;
        drools) desc="Direct Drools API evaluation" ;;
        gateway) desc="HTTP + Kafka publish (no wait)" ;;
        full) desc="Full end-to-end pipeline (HTTP → Kafka → Flink → Drools)" ;;
    esac

    print_header "Benchmark: $component"
    echo ""
    print_info "$desc"
    print_info "Duration: ${duration}s"
    if [[ "$events" -gt 0 ]]; then
        print_info "Target events: $events"
    fi
    echo ""

    # Prepare request body
    local duration_ms=$((duration * 1000))
    local body="{\"durationMs\": $duration_ms, \"targetEventCount\": $events, \"concurrency\": 8, \"batchSize\": 100}"

    # Start the benchmark
    print_info "Starting benchmark..."
    local response
    response=$(curl -sf -X POST "http://localhost:8080/api/admin/benchmark/${component}" \
        -H "x-api-key: $api_key" \
        -H "Content-Type: application/json" \
        -d "$body" 2>/dev/null)

    if [[ $? -ne 0 ]]; then
        print_error "Failed to start benchmark"
        print_info "Make sure gateway is running and healthy"
        end_timer "benchmark" "$component" "error"
        return 1
    fi

    print_success "Benchmark started"
    echo "$response" | jq -r '.message // .' 2>/dev/null
    echo ""

    # Monitor progress
    print_info "Monitoring progress (Ctrl+C to stop early)..."
    echo ""

    local benchmark_running=true
    local last_throughput=0

    while [[ "$benchmark_running" == "true" ]]; do
        sleep 2

        local result
        result=$(curl -sf "http://localhost:8080/api/admin/benchmark/${component}/result" \
            -H "x-api-key: $api_key" 2>/dev/null)

        if [[ $? -eq 0 ]]; then
            local status
            status=$(echo "$result" | jq -r '.status // "unknown"' 2>/dev/null)

            if [[ "$status" == "completed" ]] || [[ "$status" == "stopped" ]]; then
                benchmark_running=false
            else
                # Show live throughput
                local throughput
                throughput=$(echo "$result" | jq -r '.peakThroughput // 0' 2>/dev/null)
                if [[ "$throughput" != "0" ]] && [[ "$throughput" != "$last_throughput" ]]; then
                    printf "\r  Current throughput: %s events/sec    " "$throughput"
                    last_throughput="$throughput"
                fi
            fi
        else
            # Result not available yet - benchmark might still be running
            printf "\r  Waiting for results...    "
        fi
    done

    echo ""
    echo ""

    # Get final results
    print_header "Benchmark Results"
    local final_result
    final_result=$(curl -sf "http://localhost:8080/api/admin/benchmark/${component}/result" \
        -H "x-api-key: $api_key" 2>/dev/null)

    if [[ $? -eq 0 ]]; then
        # Show summary
        local peak=$(echo "$final_result" | jq -r '.peakThroughput // 0' 2>/dev/null)
        local avg=$(echo "$final_result" | jq -r '.avgThroughput // 0' 2>/dev/null)
        local p50=$(echo "$final_result" | jq -r '.latency.p50 // 0' 2>/dev/null)
        local p95=$(echo "$final_result" | jq -r '.latency.p95 // 0' 2>/dev/null)
        local p99=$(echo "$final_result" | jq -r '.latency.p99 // 0' 2>/dev/null)
        local success=$(echo "$final_result" | jq -r '.successfulOperations // 0' 2>/dev/null)
        local failed=$(echo "$final_result" | jq -r '.failedOperations // 0' 2>/dev/null)

        echo ""
        echo "  Throughput:  ${peak} peak / ${avg} avg events/sec"
        echo "  Latency:     P50=${p50}ms  P95=${p95}ms  P99=${p99}ms"
        echo "  Operations:  ${success} successful / ${failed} failed"
        echo ""

        # Generate report if requested
        if [[ "$report" == "true" ]]; then
            generate_component_report "$component" "$output_dir" "$final_result"
        fi
    else
        print_error "Could not get final results"
    fi

    end_timer "benchmark" "$component" "success"
}

# Run all benchmarks in sequence
run_all_benchmarks() {
    local duration="$1"
    local report="$2"
    local output_dir="$3"
    local api_key="${ADMIN_API_KEY:-reactive-admin-key}"

    start_timer

    print_header "Running All Benchmarks"
    echo ""
    print_info "This will run all 6 component benchmarks in sequence."
    print_info "Duration per benchmark: ${duration}s"
    print_info "Total estimated time: $((duration * 6 + 30))s (including delays)"
    echo ""

    # Start all benchmarks via API
    local duration_ms=$((duration * 1000))
    local response
    response=$(curl -sf -X POST "http://localhost:8080/api/admin/benchmark/all" \
        -H "x-api-key: $api_key" \
        -H "Content-Type: application/json" \
        -d "{\"durationMs\": $duration_ms}" 2>/dev/null)

    if [[ $? -ne 0 ]]; then
        print_error "Failed to start benchmarks"
        print_info "Make sure gateway is running"
        return 1
    fi

    print_success "All benchmarks started"
    echo ""

    # Wait for completion
    local components="http kafka flink drools gateway full"
    for component in $components; do
        print_info "Waiting for $component benchmark..."

        local completed=false
        local max_wait=$((duration + 60))
        local waited=0

        while [[ "$completed" != "true" ]] && [[ $waited -lt $max_wait ]]; do
            sleep 5
            waited=$((waited + 5))

            local result
            result=$(curl -sf "http://localhost:8080/api/admin/benchmark/${component}/result" \
                -H "x-api-key: $api_key" 2>/dev/null)

            if [[ $? -eq 0 ]]; then
                local status
                status=$(echo "$result" | jq -r '.status // ""' 2>/dev/null)
                if [[ "$status" == "completed" ]]; then
                    completed=true
                    local peak
                    peak=$(echo "$result" | jq -r '.peakThroughput // 0' 2>/dev/null)
                    print_success "$component: ${peak} events/sec"
                fi
            fi
        done

        if [[ "$completed" != "true" ]]; then
            print_warning "$component: Timeout or error"
        fi
    done

    echo ""

    # Generate reports if requested
    if [[ "$report" == "true" ]]; then
        print_info "Generating reports..."

        for component in $components; do
            local result
            result=$(curl -sf "http://localhost:8080/api/admin/benchmark/${component}/result" \
                -H "x-api-key: $api_key" 2>/dev/null)

            if [[ $? -eq 0 ]]; then
                generate_component_report "$component" "$output_dir" "$result"
            fi
        done

        print_success "All reports generated in: $output_dir"

        # Open report dashboard
        open_benchmark_reports
    fi

    end_timer "benchmark" "all" "success"
}

# Generate component benchmark report
generate_component_report() {
    local component="$1"
    local output_dir="$2"
    local results="$3"

    local timestamp=$(date +%Y%m%d_%H%M%S)
    local report_dir="$output_dir/${component}"

    # Create report directory
    mkdir -p "$report_dir"

    # Call the report generation script
    if [[ -f "$SCRIPT_DIR/scripts/benchmark-report.sh" ]]; then
        source "$SCRIPT_DIR/scripts/benchmark-report.sh"
        generate_report "$component" "$report_dir" "$results"
    fi

    # Also save to timestamped directory for history
    local history_dir="$output_dir/benchmark_${component}_${timestamp}"
    mkdir -p "$history_dir"
    cp -r "$report_dir"/* "$history_dir"/ 2>/dev/null

    # Update "latest" symlink
    local latest_dir="$output_dir/benchmark_${component}_latest"
    rm -rf "$latest_dir" 2>/dev/null
    cp -r "$report_dir" "$latest_dir"

    print_success "Report saved: $component"
}

# Open benchmark reports dashboard
open_benchmark_reports() {
    local report_index="$SCRIPT_DIR/reports/index.html"

    if [[ -f "$report_index" ]]; then
        print_info "Opening benchmark dashboard..."
        if command -v open &>/dev/null; then
            open "$report_index"
        elif command -v xdg-open &>/dev/null; then
            xdg-open "$report_index"
        else
            print_info "Open in browser: file://$report_index"
        fi
    else
        print_error "Report dashboard not found"
        print_info "Run a benchmark with --report first"
    fi
}

# Main command router
case "${1:-help}" in
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
    shell)
        cmd_shell "$2"
        ;;
    doctor)
        cmd_doctor
        ;;
    traces)
        cmd_traces "$2"
        ;;
    e2e)
        cmd_e2e
        ;;
    compile)
        cmd_compile "$2"
        ;;
    down)
        cmd_down
        ;;
    clean)
        cmd_clean
        ;;
    dev)
        cmd_dev
        ;;
    watch)
        cmd_watch "$2"
        ;;
    memory|mem)
        cmd_memory "$2" "$3" "$4"
        ;;
    benchmark|bench)
        cmd_benchmark "$2" "${@:3}"
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac
