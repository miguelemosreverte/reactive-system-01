#!/bin/bash
# Doctor script - standalone version
# This can be run directly: ./scripts/doctor.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/utils.sh"

run_doctor() {
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

# Run if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    run_doctor
fi
