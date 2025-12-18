#!/bin/bash
# Reactive System CLI
# Usage: ./cli.sh <command> [service]

set -e

# Get the directory where the script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Source utilities
source "$SCRIPT_DIR/scripts/utils.sh"

# Load environment variables
if [[ -f "$SCRIPT_DIR/.env" ]]; then
    export $(cat "$SCRIPT_DIR/.env" | grep -v '^#' | xargs)
fi

# Help message
show_help() {
    print_header "Reactive System CLI"
    echo ""
    echo "Usage: ./cli.sh <command> [service]"
    echo ""
    echo "Commands:"
    echo "  start [service]     Start all services or a specific service"
    echo "  stop [service]      Stop all services or a specific service"
    echo "  restart [service]   Restart all services or a specific service"
    echo "  build [service]     Build Docker images"
    echo "  logs [service]      View logs (follows)"
    echo "  status              Show running services"
    echo "  shell <service>     Enter container shell"
    echo "  doctor              Health check all services"
    echo "  e2e                 Run end-to-end test"
    echo "  compile drools      Recompile Drools rules"
    echo "  up                  Alias for 'start'"
    echo "  down                Stop and remove all containers"
    echo "  help                Show this help message"
    echo ""
    echo "Services: ui, gateway, flink, drools, kafka, zookeeper"
    echo ""
    echo "Examples:"
    echo "  ./cli.sh start              # Start all services"
    echo "  ./cli.sh start ui           # Start only UI"
    echo "  ./cli.sh logs gateway       # View gateway logs"
    echo "  ./cli.sh restart flink      # Restart Flink services"
    echo "  ./cli.sh doctor             # Check health of all services"
    echo ""
}

# Start services
cmd_start() {
    local service=$1
    if [[ -z "$service" ]]; then
        print_header "Starting all services"
        docker compose up -d
        print_success "All services started"
        echo ""
        print_info "UI available at: http://localhost:3000"
        print_info "Flink Dashboard: http://localhost:8081"
        print_info "Run './cli.sh doctor' to check health"
    else
        if ! service_exists "$service"; then
            print_error "Unknown service: $service"
            exit 1
        fi
        print_header "Starting $service"
        local services=$(map_service_name "$service")
        docker compose up -d $services
        print_success "$service started"
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

# Restart services
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
    print_header "Service Status"
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
    print_header "Health Check"
    echo ""

    local all_healthy=true

    # Check Zookeeper
    if ! check_tcp_health "Zookeeper" "localhost" "2181"; then
        all_healthy=false
    fi

    # Check Kafka
    if ! check_tcp_health "Kafka" "localhost" "9092"; then
        all_healthy=false
    fi

    # Check Drools
    if ! check_http_health "Drools" "http://localhost:8180/health"; then
        all_healthy=false
    fi

    # Check Flink
    if ! check_http_health "Flink" "http://localhost:8081/overview"; then
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
    if $all_healthy; then
        print_success "All services are healthy!"
        return 0
    else
        print_warning "Some services are unhealthy"
        return 1
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
    e2e)
        cmd_e2e
        ;;
    compile)
        cmd_compile "$2"
        ;;
    down)
        cmd_down
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
