#!/bin/bash
# Utility functions for CLI

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Print functions
print_header() {
    echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  ${CYAN}$1${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}!${NC} $1"
}

print_info() {
    echo -e "${CYAN}→${NC} $1"
}

# Service list
SERVICES=("ui" "gateway" "flink-jobmanager" "flink-taskmanager" "drools" "kafka" "zookeeper")

# Check if a service exists
service_exists() {
    local service=$1
    for s in "${SERVICES[@]}"; do
        if [[ "$s" == "$service" ]] || [[ "$s" == "$service-jobmanager" ]]; then
            return 0
        fi
    done
    # Also check for simplified names
    case $service in
        flink|ui|gateway|drools|kafka|zookeeper)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Map simplified service names to docker-compose service names
map_service_name() {
    local service=$1
    case $service in
        flink)
            echo "flink-jobmanager flink-taskmanager flink-job-submitter"
            ;;
        *)
            echo "$service"
            ;;
    esac
}

# Health check functions
check_http_health() {
    local name=$1
    local url=$2
    local response
    response=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "$url" 2>/dev/null)
    if [[ "$response" == "200" ]] || [[ "$response" == "204" ]]; then
        print_success "$name is healthy (HTTP $response)"
        return 0
    else
        print_error "$name is unhealthy (HTTP $response)"
        return 1
    fi
}

check_tcp_health() {
    local name=$1
    local host=$2
    local port=$3
    if nc -z "$host" "$port" 2>/dev/null; then
        print_success "$name is healthy (TCP $port)"
        return 0
    else
        print_error "$name is unreachable (TCP $port)"
        return 1
    fi
}

# Wait for service with timeout
wait_for_service() {
    local name=$1
    local check_cmd=$2
    local timeout=${3:-60}
    local elapsed=0

    print_info "Waiting for $name..."
    while ! eval "$check_cmd" &>/dev/null; do
        sleep 2
        elapsed=$((elapsed + 2))
        if [[ $elapsed -ge $timeout ]]; then
            print_error "$name did not start within ${timeout}s"
            return 1
        fi
    done
    print_success "$name is ready"
    return 0
}
