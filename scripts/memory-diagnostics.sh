#!/bin/bash
# Memory Diagnostics Tool for Reactive System
# Provides multi-level granularity analysis of memory pressure, crash tracking, and stability KPIs

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Data directory for history tracking
DATA_DIR="${PROJECT_ROOT}/diagnostics/history"
mkdir -p "$DATA_DIR"

# Pressure thresholds
PRESSURE_LOW=50
PRESSURE_MEDIUM=75
PRESSURE_HIGH=90

print_header() {
    echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}  ${CYAN}$1${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
    echo
}

print_section() {
    echo -e "${CYAN}── $1 ──${NC}"
}

# Pressure visualization bar
pressure_bar() {
    local pct=$1
    local width=10
    local filled=$((pct * width / 100))
    local empty=$((width - filled))
    local bar=""
    for ((i=0; i<filled; i++)); do bar+="▓"; done
    for ((i=0; i<empty; i++)); do bar+="░"; done
    echo "$bar"
}

# Pressure level with color
pressure_level() {
    local pct=$1
    if [ "$pct" -lt "$PRESSURE_LOW" ]; then
        echo -e "${GREEN}LOW${NC}"
    elif [ "$pct" -lt "$PRESSURE_MEDIUM" ]; then
        echo -e "${YELLOW}MEDIUM${NC}"
    elif [ "$pct" -lt "$PRESSURE_HIGH" ]; then
        echo -e "${YELLOW}HIGH${NC}"
    else
        echo -e "${RED}CRITICAL${NC}"
    fi
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

# ============================================
# CRASH TRACKING AND HISTORY
# ============================================

show_crash_history() {
    print_header "Crash History & Stability"

    local crash_log="$DATA_DIR/crash_history.jsonl"
    touch "$crash_log"

    # Detect current OOM-killed containers
    local oom_detected=0
    while IFS= read -r container; do
        local oom=$(docker inspect "$container" 2>/dev/null | jq -r '.[0].State.OOMKilled' 2>/dev/null)
        if [ "$oom" = "true" ]; then
            oom_detected=$((oom_detected + 1))
            local name=$(docker inspect "$container" 2>/dev/null | jq -r '.[0].Name' 2>/dev/null | sed 's|^/||')
            local ts=$(date +%s)
            echo "{\"timestamp\":$ts,\"container\":\"$name\",\"type\":\"OOM\"}" >> "$crash_log"
            echo -e "${RED}⚠ OOM Detected: $name${NC}"
        fi
    done < <(docker ps -aq 2>/dev/null)

    # Check for exited containers
    local exit_detected=0
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        exit_detected=$((exit_detected + 1))
    done < <(docker ps -a --filter "status=exited" --format "{{.Names}}" 2>/dev/null | grep -E "reactive|flink")

    # Calculate stats from history
    local total_crashes=$(wc -l < "$crash_log" 2>/dev/null | tr -d ' ')
    local now=$(date +%s)
    local day_ago=$((now - 86400))
    local week_ago=$((now - 604800))

    local crashes_24h=$(awk -v cutoff="$day_ago" -F'"timestamp":' '{split($2,a,","); if(a[1]+0 > cutoff) print}' "$crash_log" 2>/dev/null | wc -l | tr -d ' ')
    local crashes_7d=$(awk -v cutoff="$week_ago" -F'"timestamp":' '{split($2,a,","); if(a[1]+0 > cutoff) print}' "$crash_log" 2>/dev/null | wc -l | tr -d ' ')

    echo ""
    print_section "Crash Summary"
    echo -e "  Last 24 hours: ${BOLD}$crashes_24h${NC} crashes"
    echo -e "  Last 7 days:   ${BOLD}$crashes_7d${NC} crashes"
    echo -e "  All time:      ${BOLD}$total_crashes${NC} recorded"
    echo ""

    # Show recent crashes
    if [ "$total_crashes" -gt 0 ]; then
        print_section "Recent Crashes (last 5)"
        tail -5 "$crash_log" 2>/dev/null | while IFS= read -r line; do
            local ts=$(echo "$line" | jq -r '.timestamp' 2>/dev/null)
            local container=$(echo "$line" | jq -r '.container' 2>/dev/null)
            local type=$(echo "$line" | jq -r '.type' 2>/dev/null)
            # macOS date vs GNU date
            local date_str=$(date -r "$ts" "+%Y-%m-%d %H:%M" 2>/dev/null || date -d "@$ts" "+%Y-%m-%d %H:%M" 2>/dev/null || echo "unknown")
            echo -e "    ${RED}[$type]${NC} $container at $date_str"
        done
        echo ""
    fi

    # Current health
    print_section "Current Container Health"
    local healthy=0
    local total=0
    while IFS= read -r name; do
        [ -z "$name" ] && continue
        total=$((total + 1))
        local health=$(docker inspect "$name" 2>/dev/null | jq -r '.[0].State.Health.Status // "running"' 2>/dev/null)
        if [ "$health" = "healthy" ] || [ "$health" = "running" ]; then
            healthy=$((healthy + 1))
        else
            local short_name=$(echo "$name" | sed 's/reactive-//;s/-system-01//')
            echo -e "  ${RED}✗${NC} $short_name: $health"
        fi
    done < <(docker ps --format "{{.Names}}" 2>/dev/null | grep -E "reactive|flink")

    if [ "$healthy" -eq "$total" ]; then
        echo -e "  ${GREEN}✓ All $total containers healthy${NC}"
    else
        echo -e "  ${YELLOW}$healthy/$total containers healthy${NC}"
    fi
    echo ""
}

# ============================================
# BENCHMARK STABILITY KPIs
# ============================================

show_stability_kpis() {
    print_header "Benchmark Stability KPIs"

    local benchmark_log="$DATA_DIR/benchmark_history.jsonl"

    if [ ! -f "$benchmark_log" ] || [ ! -s "$benchmark_log" ]; then
        echo -e "  ${YELLOW}No benchmark history yet.${NC}"
        echo ""
        echo "  Record benchmarks with:"
        echo "    $0 record-benchmark <throughput> <status> [crash_reason]"
        echo ""
        echo "  Examples:"
        echo "    $0 record-benchmark 5107 success"
        echo "    $0 record-benchmark 0 crash \"flink OOM\""
        echo ""
        return
    fi

    # Calculate stats
    local total_runs=$(wc -l < "$benchmark_log" | tr -d ' ')
    local success_runs=$(grep -c '"status":"success"' "$benchmark_log" 2>/dev/null || echo 0)
    local crash_runs=$((total_runs - success_runs))
    local success_rate=0
    [ "$total_runs" -gt 0 ] && success_rate=$((success_runs * 100 / total_runs))

    print_section "Run History (last 10)"
    local run_num=$total_runs
    tail -10 "$benchmark_log" | while IFS= read -r line; do
        local throughput=$(echo "$line" | jq -r '.throughput' 2>/dev/null)
        local status=$(echo "$line" | jq -r '.status' 2>/dev/null)
        local type=$(echo "$line" | jq -r '.type // "full"' 2>/dev/null)
        local duration=$(echo "$line" | jq -r '.duration // 30' 2>/dev/null)
        local reason=$(echo "$line" | jq -r '.crash_reason // ""' 2>/dev/null)

        if [ "$status" = "success" ]; then
            echo -e "  #$run_num: ${GREEN}✓${NC} ${throughput} ops/s ($type, ${duration}s)"
        else
            echo -e "  #$run_num: ${RED}✗${NC} CRASHED - $reason"
        fi
        run_num=$((run_num + 1))
    done
    echo ""

    # Success rate with color coding
    print_section "Stability Metrics"
    local rate_color="$GREEN"
    [ "$success_rate" -lt 90 ] && rate_color="$YELLOW"
    [ "$success_rate" -lt 70 ] && rate_color="$RED"

    echo -e "  Success Rate: ${rate_color}${BOLD}${success_rate}%${NC} ($success_runs/$total_runs runs)"

    # Crash causes breakdown
    if [ "$crash_runs" -gt 0 ]; then
        echo ""
        echo "  Crash Causes:"
        grep '"status":"crash"' "$benchmark_log" 2>/dev/null | jq -r '.crash_reason // "unknown"' | sort | uniq -c | sort -rn | while read count reason; do
            echo -e "    - $reason: ${RED}$count${NC}"
        done
    fi

    # Throughput trend
    echo ""
    echo "  Throughput Trend:"
    local recent_avg=$(grep '"status":"success"' "$benchmark_log" 2>/dev/null | tail -5 | jq -r '.throughput' | awk '{sum+=$1; count++} END {if(count>0) printf "%.0f", sum/count; else print 0}')
    local older_avg=$(grep '"status":"success"' "$benchmark_log" 2>/dev/null | head -5 | jq -r '.throughput' | awk '{sum+=$1; count++} END {if(count>0) printf "%.0f", sum/count; else print 0}')

    if [ "$recent_avg" -gt 0 ] && [ "$older_avg" -gt 0 ]; then
        local diff=$((recent_avg - older_avg))
        if [ "$diff" -gt 100 ]; then
            echo -e "    ${GREEN}↑ Improving${NC} (recent: ${recent_avg} ops/s, earlier: ${older_avg} ops/s)"
        elif [ "$diff" -lt -100 ]; then
            echo -e "    ${RED}↓ Declining${NC} (recent: ${recent_avg} ops/s, earlier: ${older_avg} ops/s)"
        else
            echo -e "    → Stable (avg: ${recent_avg} ops/s)"
        fi
    else
        echo "    Insufficient data for trend analysis"
    fi
    echo ""
}

# Record a benchmark result
record_benchmark() {
    local throughput=$1
    local status=$2
    local crash_reason=${3:-""}
    local type=${4:-"full"}
    local duration=${5:-30}

    local benchmark_log="$DATA_DIR/benchmark_history.jsonl"

    local entry="{\"timestamp\":$(date +%s),\"throughput\":$throughput,\"status\":\"$status\",\"type\":\"$type\",\"duration\":$duration"
    [ -n "$crash_reason" ] && entry+=",\"crash_reason\":\"$crash_reason\""
    entry+="}"

    echo "$entry" >> "$benchmark_log"
    echo -e "${GREEN}✓${NC} Recorded: $throughput ops/s ($status)"
}

# ============================================
# RISK ASSESSMENT
# ============================================

show_risk_assessment() {
    print_header "Crash Risk Assessment"

    local risk_score=0
    local risk_factors=()

    # Check memory pressure for each container
    while IFS=$'\t' read -r name pct_raw; do
        [[ ! "$name" =~ reactive|flink ]] && continue

        # Parse percentage - remove % and decimal
        local pct=$(echo "$pct_raw" | tr -d '%' | cut -d'.' -f1)
        pct=${pct:-0}

        local short_name=$(echo "$name" | sed 's/reactive-//;s/-system-01//')

        if [ "$pct" -gt 90 ]; then
            risk_score=$((risk_score + 30))
            risk_factors+=("${short_name} at ${pct}% memory (CRITICAL)")
        elif [ "$pct" -gt 75 ]; then
            risk_score=$((risk_score + 15))
            risk_factors+=("${short_name} at ${pct}% memory (HIGH)")
        elif [ "$pct" -gt 60 ]; then
            risk_score=$((risk_score + 5))
            risk_factors+=("${short_name} at ${pct}% memory (MEDIUM)")
        fi
    done < <(docker stats --no-stream --format "{{.Name}}\t{{.MemPerc}}" 2>/dev/null)

    # Determine risk level
    local risk_level="LOW"
    local risk_color="$GREEN"
    if [ "$risk_score" -gt 50 ]; then
        risk_level="CRITICAL"
        risk_color="$RED"
    elif [ "$risk_score" -gt 30 ]; then
        risk_level="HIGH"
        risk_color="$RED"
    elif [ "$risk_score" -gt 15 ]; then
        risk_level="MEDIUM"
        risk_color="$YELLOW"
    fi

    echo -e "  Overall Risk: ${risk_color}${BOLD}$risk_level${NC} (score: $risk_score/100)"
    echo ""

    if [ ${#risk_factors[@]} -gt 0 ]; then
        print_section "Risk Factors"
        for factor in "${risk_factors[@]}"; do
            echo -e "  ${YELLOW}⚠${NC} $factor"
        done
        echo ""
    fi

    print_section "Recommendations"
    if [ "$risk_score" -gt 30 ]; then
        echo -e "  ${RED}→ Reduce load or increase memory limits immediately${NC}"
        echo -e "  ${RED}→ Check for memory leaks in high-pressure components${NC}"
    elif [ "$risk_score" -gt 15 ]; then
        echo -e "  ${YELLOW}→ Monitor closely during benchmarks${NC}"
        echo -e "  ${YELLOW}→ Consider increasing memory limits${NC}"
    else
        echo -e "  ${GREEN}→ System is stable, safe to run benchmarks${NC}"
    fi
    echo ""
}

# ============================================
# QUICK PRESSURE SUMMARY
# ============================================

show_pressure_summary() {
    print_header "Memory Pressure Summary"

    printf "  ${BOLD}%-25s %18s %12s %12s${NC}\n" "COMPONENT" "USAGE" "PRESSURE" "STATUS"
    echo "  ─────────────────────────────────────────────────────────────────"

    docker stats --no-stream --format "{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" 2>/dev/null | while IFS=$'\t' read -r name usage pct_raw; do
        [[ ! "$name" =~ reactive|flink ]] && continue

        # Parse percentage - remove % and decimal
        local pct=$(echo "$pct_raw" | tr -d '%' | cut -d'.' -f1)
        pct=${pct:-0}

        local short_name=$(echo "$name" | sed 's/reactive-//;s/-system-01//')

        printf "  %-25s %18s " "$short_name" "$usage"
        echo -ne "$(pressure_bar $pct) "
        pressure_level $pct
    done
    echo ""
}

# ============================================
# ACTIONABLE VERDICT
# ============================================

show_verdict() {
    print_header "VERDICT: Action Required"

    local dominated_by=""
    local max_pressure=0
    local action=""

    # Find the component with highest memory pressure
    while IFS=$'\t' read -r name pct_raw; do
        [[ ! "$name" =~ reactive|flink ]] && continue
        local pct=$(echo "$pct_raw" | tr -d '%' | cut -d'.' -f1)
        pct=${pct:-0}

        if [ "$pct" -gt "$max_pressure" ]; then
            max_pressure=$pct
            dominated_by=$(echo "$name" | sed 's/reactive-//;s/-system-01//')
        fi
    done < <(docker stats --no-stream --format "{{.Name}}\t{{.MemPerc}}" 2>/dev/null)

    # Determine verdict
    if [ "$max_pressure" -gt 90 ]; then
        echo -e "  ${RED}${BOLD}CRITICAL: $dominated_by at ${max_pressure}% memory${NC}"
        echo ""
        echo -e "  ${BOLD}Immediate Actions:${NC}"
        case "$dominated_by" in
            *flink*|*taskmanager*)
                echo "    1. Reduce ASYNC_CAPACITY in docker-compose.yml (current: 200 → try 100)"
                echo "    2. Increase taskmanager.memory.process.size (current: 3g → try 4g)"
                echo "    3. Check for state accumulation: ./cli.sh p memory heap flink-taskmanager"
                ;;
            *drools*)
                echo "    1. Increase Drools memory limit (current: 768M → try 1G)"
                echo "    2. Check rule session leaks: ./cli.sh p memory jfr drools 30"
                ;;
            *jaeger*)
                echo "    1. Reduce MEMORY_MAX_TRACES (current: 20000 → try 10000)"
                echo "    2. Increase Jaeger memory limit (current: 4G → try 6G)"
                ;;
            *prometheus*)
                echo "    1. Reduce scrape frequency or retention period"
                echo "    2. Increase Prometheus memory limit"
                ;;
            *)
                echo "    1. Increase memory limit for $dominated_by"
                echo "    2. Check for memory leaks with heap dump"
                ;;
        esac
    elif [ "$max_pressure" -gt 75 ]; then
        echo -e "  ${YELLOW}${BOLD}WARNING: $dominated_by at ${max_pressure}% memory${NC}"
        echo ""
        echo -e "  ${BOLD}Recommended Actions:${NC}"
        echo "    1. Monitor $dominated_by during benchmark"
        echo "    2. Consider increasing memory limit before heavy load"
    elif [ "$max_pressure" -gt 60 ]; then
        echo -e "  ${YELLOW}CAUTION: $dominated_by at ${max_pressure}% memory${NC}"
        echo ""
        echo -e "  ${BOLD}Optional Actions:${NC}"
        echo "    1. Watch $dominated_by: docker stats $dominated_by"
    else
        echo -e "  ${GREEN}${BOLD}ALL CLEAR: System healthy${NC}"
        echo ""
        echo -e "  Memory pressure is low across all components."
        echo -e "  Highest: $dominated_by at ${max_pressure}%"
        echo ""
        echo -e "  ${BOLD}Next Step:${NC} Run benchmark to measure throughput"
        echo "    ./cli.sh p benchmark full --duration 30"
    fi
    echo ""
}

# ============================================
# COMPREHENSIVE DIAGNOSTIC REPORT
# ============================================

show_full_diagnostic() {
    show_pressure_summary
    show_risk_assessment
    show_crash_history
    show_stability_kpis
    show_recommendations
    show_verdict
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
    pressure|summary)
        show_pressure_summary
        ;;
    risk)
        show_risk_assessment
        ;;
    crashes|history)
        show_crash_history
        ;;
    kpis|stability)
        show_stability_kpis
        ;;
    verdict|action)
        show_verdict
        ;;
    record-benchmark)
        if [ -z "$2" ] || [ -z "$3" ]; then
            echo "Usage: $0 record-benchmark <throughput> <status> [crash_reason] [type] [duration]"
            echo "  status: success or crash"
            exit 1
        fi
        record_benchmark "$2" "$3" "${4:-}" "${5:-full}" "${6:-30}"
        ;;
    diagnose|full)
        show_full_diagnostic
        ;;
    help|--help|-h)
        echo "Memory Diagnostics Tool"
        echo
        echo "Usage: $0 <command> [args]"
        echo
        echo "Quick Commands:"
        echo "  pressure      Quick memory pressure summary with visual bars"
        echo "  risk          Crash risk assessment with recommendations"
        echo "  crashes       Crash history and current health"
        echo "  kpis          Benchmark stability KPIs and trends"
        echo "  diagnose      Full diagnostic report (pressure + risk + crashes + kpis)"
        echo
        echo "Detailed Analysis:"
        echo "  overview      Memory usage overview (default)"
        echo "  jvm           Detailed JVM memory for Java services"
        echo "  observability Observability stack memory"
        echo "  all           All memory information"
        echo "  watch         Live memory monitoring"
        echo "  recommend     Tuning recommendations"
        echo
        echo "Profiling:"
        echo "  heap <svc>    Take heap dump (drools, flink-taskmanager)"
        echo "  jfr <svc> [s] Start Java Flight Recorder for N seconds"
        echo
        echo "Recording:"
        echo "  record-benchmark <throughput> <status> [crash_reason] [type] [duration]"
        echo "                Record a benchmark result for KPI tracking"
        echo
        echo "Examples:"
        echo "  $0 pressure                    # Quick pressure check"
        echo "  $0 diagnose                    # Full diagnostic report"
        echo "  $0 record-benchmark 5107 success"
        echo "  $0 record-benchmark 0 crash \"flink OOM\""
        echo "  $0 heap drools                 # Heap dump of Drools"
        ;;
    *)
        echo "Unknown command: $1"
        echo "Run '$0 help' for usage"
        exit 1
        ;;
esac
