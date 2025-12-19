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
    help|--help|-h)
        show_help
        ;;
    *)
        print_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac
