#!/bin/bash
# Memory Diagnostics Tool for Reactive System
# Helps identify memory bottlenecks across all services

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

print_header() {
    echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  ${CYAN}$1${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
    echo
}

print_section() {
    echo -e "${CYAN}── $1 ──${NC}"
}

# Get container memory stats
get_container_memory() {
    local container=$1
    docker stats --no-stream --format "{{.MemUsage}}\t{{.MemPerc}}" "$container" 2>/dev/null || echo "N/A\tN/A"
}

# Get JVM memory via JMX (for Java services)
get_jvm_memory() {
    local service=$1
    local port=$2

    case $service in
        drools)
            # Drools exposes actuator metrics
            curl -s "http://localhost:8180/actuator/metrics/jvm.memory.used" 2>/dev/null | jq -r '.measurements[0].value // 0' | awk '{printf "%.0f MB", $1/1024/1024}'
            ;;
        flink)
            # Flink exposes via Prometheus
            curl -s "http://localhost:9249/metrics" 2>/dev/null | grep "flink_taskmanager_Status_JVM_Memory_Heap_Used" | head -1 | awk '{printf "%.0f MB", $2/1024/1024}'
            ;;
        *)
            echo "N/A"
            ;;
    esac
}

# Show overall memory usage
show_overview() {
    print_header "Memory Overview - All Services"

    echo -e "Container\t\t\tMemory Used\tLimit\t\tPercent"
    echo -e "─────────\t\t\t───────────\t─────\t\t───────"

    for container in reactive-gateway reactive-drools reactive-flink-jobmanager reactive-flink-taskmanager reactive-kafka reactive-jaeger reactive-otel-collector reactive-grafana reactive-prometheus reactive-loki; do
        if docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
            stats=$(docker stats --no-stream --format "{{.MemUsage}}\t{{.MemPerc}}" "$container" 2>/dev/null)
            mem_used=$(echo "$stats" | cut -f1)
            mem_pct=$(echo "$stats" | cut -f2)
            printf "%-30s\t%s\t%s\n" "$container" "$mem_used" "$mem_pct"
        else
            printf "%-30s\t${RED}Not Running${NC}\n" "$container"
        fi
    done
    echo
}

# Detailed JVM analysis for Java services
show_jvm_details() {
    print_header "JVM Memory Details"

    print_section "Drools (Spring Boot)"
    if curl -s "http://localhost:8180/actuator/health" > /dev/null 2>&1; then
        echo "Heap Used:    $(curl -s 'http://localhost:8180/actuator/metrics/jvm.memory.used?tag=area:heap' 2>/dev/null | jq -r '.measurements[0].value // 0' | awk '{printf "%.1f MB", $1/1024/1024}')"
        echo "Heap Max:     $(curl -s 'http://localhost:8180/actuator/metrics/jvm.memory.max?tag=area:heap' 2>/dev/null | jq -r '.measurements[0].value // 0' | awk '{printf "%.1f MB", $1/1024/1024}')"
        echo "Non-Heap:     $(curl -s 'http://localhost:8180/actuator/metrics/jvm.memory.used?tag=area:nonheap' 2>/dev/null | jq -r '.measurements[0].value // 0' | awk '{printf "%.1f MB", $1/1024/1024}')"
        echo "GC Pauses:    $(curl -s 'http://localhost:8180/actuator/metrics/jvm.gc.pause' 2>/dev/null | jq -r '.measurements[] | select(.statistic=="COUNT") | .value // 0')"
        echo "Threads:      $(curl -s 'http://localhost:8180/actuator/metrics/jvm.threads.live' 2>/dev/null | jq -r '.measurements[0].value // 0')"
    else
        echo -e "${RED}Drools not reachable${NC}"
    fi
    echo

    print_section "Flink TaskManager"
    if curl -s "http://localhost:9249/metrics" > /dev/null 2>&1; then
        metrics=$(curl -s "http://localhost:9249/metrics" 2>/dev/null)
        heap_used=$(echo "$metrics" | grep "flink_taskmanager_Status_JVM_Memory_Heap_Used " | awk '{printf "%.1f MB", $2/1024/1024}')
        heap_max=$(echo "$metrics" | grep "flink_taskmanager_Status_JVM_Memory_Heap_Max " | awk '{printf "%.1f MB", $2/1024/1024}')
        nonheap=$(echo "$metrics" | grep "flink_taskmanager_Status_JVM_Memory_NonHeap_Used " | awk '{printf "%.1f MB", $2/1024/1024}')
        direct=$(echo "$metrics" | grep "flink_taskmanager_Status_JVM_Memory_Direct_Used " | awk '{printf "%.1f MB", $2/1024/1024}')

        echo "Heap Used:    ${heap_used:-N/A}"
        echo "Heap Max:     ${heap_max:-N/A}"
        echo "Non-Heap:     ${nonheap:-N/A}"
        echo "Direct Mem:   ${direct:-N/A}"

        # Network buffers - critical for backpressure
        total_segments=$(echo "$metrics" | grep "flink_taskmanager_Status_Network_TotalMemorySegments " | awk '{print $2}')
        avail_segments=$(echo "$metrics" | grep "flink_taskmanager_Status_Network_AvailableMemorySegments " | awk '{print $2}')
        if [ -n "$total_segments" ] && [ -n "$avail_segments" ]; then
            used_pct=$(echo "scale=1; (($total_segments - $avail_segments) / $total_segments) * 100" | bc 2>/dev/null || echo "N/A")
            echo "Net Buffers:  ${avail_segments}/${total_segments} available (${used_pct}% used)"
        fi
    else
        echo -e "${RED}Flink TaskManager metrics not reachable${NC}"
    fi
    echo
}

# Show memory breakdown for observability stack
show_observability_memory() {
    print_header "Observability Stack Memory"

    print_section "OTEL Collector"
    if curl -s "http://localhost:8888/metrics" > /dev/null 2>&1; then
        metrics=$(curl -s "http://localhost:8888/metrics" 2>/dev/null)
        # Go runtime metrics
        heap_alloc=$(echo "$metrics" | grep "^go_memstats_heap_alloc_bytes " | awk '{printf "%.1f MB", $2/1024/1024}')
        heap_sys=$(echo "$metrics" | grep "^go_memstats_heap_sys_bytes " | awk '{printf "%.1f MB", $2/1024/1024}')
        goroutines=$(echo "$metrics" | grep "^go_goroutines " | awk '{print $2}')

        echo "Heap Alloc:   ${heap_alloc:-N/A}"
        echo "Heap Sys:     ${heap_sys:-N/A}"
        echo "Goroutines:   ${goroutines:-N/A}"

        # Collector-specific metrics
        spans_received=$(echo "$metrics" | grep "otelcol_receiver_accepted_spans" | awk '{sum+=$2} END {print sum}')
        spans_dropped=$(echo "$metrics" | grep "otelcol_processor_dropped_spans" | awk '{sum+=$2} END {print sum}')
        echo "Spans Recv'd: ${spans_received:-0}"
        echo "Spans Drop'd: ${spans_dropped:-0}"
    else
        echo -e "${RED}OTEL Collector metrics not reachable${NC}"
    fi
    echo

    print_section "Jaeger"
    docker stats --no-stream --format "Memory: {{.MemUsage}} ({{.MemPerc}})" reactive-jaeger 2>/dev/null || echo -e "${RED}Jaeger not running${NC}"
    echo

    print_section "Prometheus"
    docker stats --no-stream --format "Memory: {{.MemUsage}} ({{.MemPerc}})" reactive-prometheus 2>/dev/null || echo -e "${RED}Prometheus not running${NC}"
    echo

    print_section "Loki"
    docker stats --no-stream --format "Memory: {{.MemUsage}} ({{.MemPerc}})" reactive-loki 2>/dev/null || echo -e "${RED}Loki not running${NC}"
    echo
}

# Take heap dump from Java service
take_heap_dump() {
    local service=$1
    local container="reactive-${service}"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local dump_file="/tmp/heapdump_${service}_${timestamp}.hprof"

    print_header "Taking Heap Dump: ${service}"

    if ! docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
        echo -e "${RED}Container ${container} is not running${NC}"
        return 1
    fi

    echo "Finding Java PID in container..."
    local pid=$(docker exec "$container" pgrep -f "java" | head -1)

    if [ -z "$pid" ]; then
        echo -e "${RED}No Java process found in ${container}${NC}"
        return 1
    fi

    echo "Java PID: $pid"
    echo "Taking heap dump (this may take a moment)..."

    docker exec "$container" jmap -dump:format=b,file=/tmp/heapdump.hprof "$pid" 2>/dev/null

    echo "Copying heap dump to host..."
    docker cp "${container}:/tmp/heapdump.hprof" "$dump_file"
    docker exec "$container" rm -f /tmp/heapdump.hprof

    local size=$(ls -lh "$dump_file" | awk '{print $5}')
    echo -e "${GREEN}Heap dump saved: ${dump_file} (${size})${NC}"
    echo
    echo "Analyze with:"
    echo "  - Eclipse MAT: https://www.eclipse.org/mat/"
    echo "  - VisualVM: visualvm --openfile $dump_file"
    echo "  - jhat: jhat $dump_file"
}

# Start Java Flight Recorder
start_jfr() {
    local service=$1
    local duration=${2:-60}
    local container="reactive-${service}"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local jfr_file="/tmp/recording_${service}_${timestamp}.jfr"

    print_header "Starting Java Flight Recorder: ${service}"

    if ! docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
        echo -e "${RED}Container ${container} is not running${NC}"
        return 1
    fi

    echo "Finding Java PID in container..."
    local pid=$(docker exec "$container" pgrep -f "java" | head -1)

    if [ -z "$pid" ]; then
        echo -e "${RED}No Java process found in ${container}${NC}"
        return 1
    fi

    echo "Java PID: $pid"
    echo "Starting ${duration}s recording..."

    docker exec "$container" jcmd "$pid" JFR.start name=diag duration="${duration}s" filename=/tmp/recording.jfr settings=profile 2>/dev/null

    echo "Recording started. Waiting ${duration} seconds..."
    sleep "$duration"

    echo "Copying recording to host..."
    docker cp "${container}:/tmp/recording.jfr" "$jfr_file"
    docker exec "$container" rm -f /tmp/recording.jfr

    local size=$(ls -lh "$jfr_file" | awk '{print $5}')
    echo -e "${GREEN}JFR recording saved: ${jfr_file} (${size})${NC}"
    echo
    echo "Analyze with:"
    echo "  - JDK Mission Control: jmc"
    echo "  - IntelliJ IDEA: File > Open > select .jfr file"
    echo "  - Async-profiler converter: java -jar converter.jar $jfr_file"
}

# Live memory watch
watch_memory() {
    print_header "Live Memory Monitor (Ctrl+C to stop)"

    docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}" \
        reactive-gateway \
        reactive-drools \
        reactive-flink-jobmanager \
        reactive-flink-taskmanager \
        reactive-kafka \
        reactive-jaeger \
        reactive-otel-collector \
        reactive-prometheus \
        reactive-loki \
        reactive-grafana
}

# Memory recommendations
show_recommendations() {
    print_header "Memory Tuning Recommendations"

    echo "Current container limits vs actual usage:"
    echo

    # Check each service
    for container in reactive-drools reactive-flink-taskmanager reactive-jaeger reactive-otel-collector reactive-loki; do
        if docker ps --format '{{.Names}}' | grep -q "^${container}$"; then
            stats=$(docker stats --no-stream --format "{{.MemUsage}}" "$container" 2>/dev/null)
            used=$(echo "$stats" | cut -d'/' -f1 | tr -d ' ')
            limit=$(echo "$stats" | cut -d'/' -f2 | tr -d ' ')

            # Parse to MB for comparison
            used_mb=$(echo "$used" | sed 's/MiB//' | sed 's/GiB/*1024/' | bc 2>/dev/null || echo "0")
            limit_mb=$(echo "$limit" | sed 's/MiB//' | sed 's/GiB/*1024/' | bc 2>/dev/null || echo "0")

            if [ "$limit_mb" != "0" ]; then
                pct=$(echo "scale=0; $used_mb * 100 / $limit_mb" | bc 2>/dev/null || echo "0")

                if [ "$pct" -gt 80 ]; then
                    echo -e "${RED}$container: $stats (${pct}% - CRITICAL)${NC}"
                    echo "  -> Increase memory limit or reduce load"
                elif [ "$pct" -gt 60 ]; then
                    echo -e "${YELLOW}$container: $stats (${pct}% - WARNING)${NC}"
                    echo "  -> Monitor closely under load"
                else
                    echo -e "${GREEN}$container: $stats (${pct}% - OK)${NC}"
                fi
            else
                echo "$container: $stats"
            fi
        fi
    done

    echo
    echo "Tuning suggestions:"
    echo "1. Jaeger: Increase MEMORY_MAX_TRACES if OOM, or reduce trace retention"
    echo "2. OTEL Collector: Increase sampling rate under load (already adaptive)"
    echo "3. Flink: Tune taskmanager.memory.* settings for your workload"
    echo "4. Drools: Increase -Xmx if heap pressure, tune rule sessions"
    echo "5. Loki: Reduce retention period or increase memory"
}

# Main command router
case "${1:-overview}" in
    overview|status)
        show_overview
        ;;
    jvm)
        show_jvm_details
        ;;
    observability|obs)
        show_observability_memory
        ;;
    all)
        show_overview
        show_jvm_details
        show_observability_memory
        show_recommendations
        ;;
    heap)
        if [ -z "$2" ]; then
            echo "Usage: $0 heap <service>"
            echo "Services: drools, flink-jobmanager, flink-taskmanager"
            exit 1
        fi
        take_heap_dump "$2"
        ;;
    jfr)
        if [ -z "$2" ]; then
            echo "Usage: $0 jfr <service> [duration_seconds]"
            echo "Services: drools, flink-jobmanager, flink-taskmanager"
            exit 1
        fi
        start_jfr "$2" "${3:-60}"
        ;;
    watch)
        watch_memory
        ;;
    recommend|recommendations)
        show_recommendations
        ;;
    help|--help|-h)
        echo "Memory Diagnostics Tool"
        echo
        echo "Usage: $0 <command> [args]"
        echo
        echo "Commands:"
        echo "  overview      Show memory usage overview (default)"
        echo "  jvm           Show detailed JVM memory for Java services"
        echo "  observability Show observability stack memory"
        echo "  all           Show all memory information"
        echo "  watch         Live memory monitoring"
        echo "  recommend     Show tuning recommendations"
        echo "  heap <svc>    Take heap dump (drools, flink-taskmanager)"
        echo "  jfr <svc> [s] Start Java Flight Recorder for N seconds"
        echo
        echo "Examples:"
        echo "  $0 all                    # Full memory report"
        echo "  $0 watch                  # Live monitoring"
        echo "  $0 heap drools            # Heap dump of Drools"
        echo "  $0 jfr flink-taskmanager 30  # 30s JFR recording"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Run '$0 help' for usage"
        exit 1
        ;;
esac
