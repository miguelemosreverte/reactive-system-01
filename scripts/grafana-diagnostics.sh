#!/bin/bash
# Grafana Dashboard Diagnostics - Quick check of all panel queries

PROMETHEUS_URL="http://localhost:9090"
LOKI_URL="http://localhost:3100"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

passed=0
failed=0

check_metric() {
    local name="$1"
    local query="$2"

    local result=$(curl -s "${PROMETHEUS_URL}/api/v1/query" --data-urlencode "query=${query}" 2>/dev/null)
    local count=$(echo "$result" | jq -r '.data.result | length' 2>/dev/null || echo "0")

    if [ "$count" != "0" ] && [ -n "$count" ]; then
        local sample=$(echo "$result" | jq -r '.data.result[0].value[1] // "N/A"' 2>/dev/null)
        echo -e "${GREEN}[OK]${NC} $name ($count series)"
        ((passed++))
        return 0
    else
        echo -e "${RED}[NO DATA]${NC} $name"
        echo "         Query: $query"
        ((failed++))
        return 1
    fi
}

echo "============================================"
echo "  Grafana Dashboard Panel Diagnostics"
echo "============================================"
echo ""

echo "=== System Health ==="
check_metric "Drools Startup" "application_ready_time_seconds"
check_metric "Events Processed" "sum(flink_taskmanager_job_task_operator_numRecordsIn)"
check_metric "JVM Threads" "jvm_threads_live_threads"
echo ""

echo "=== Pipeline Overview ==="
check_metric "Latency by Service (spanmetrics)" "sum(rate(reactive_system_traces_duration_milliseconds_sum[5m])) by (service_name)"
check_metric "Flink Input Rate" "rate(flink_taskmanager_job_task_operator_numRecordsIn[1m])"
check_metric "Kafka Consumer Rate" "flink_taskmanager_job_task_operator_KafkaSourceReader_KafkaConsumer_records_consumed_rate"
echo ""

echo "=== RED Metrics ==="
check_metric "HTTP Request Rate" "rate(http_server_requests_seconds_count[1m])"
check_metric "HTTP Duration" "rate(http_server_requests_seconds_sum[1m])"
echo ""

echo "=== Kafka ==="
check_metric "Producer Latency" "flink_taskmanager_job_task_operator_KafkaProducer_request_latency_avg"
check_metric "Consumer Fetch Latency" "flink_taskmanager_job_task_operator_KafkaSourceReader_KafkaConsumer_fetch_latency_avg"
check_metric "Kafka Byte Rate" "flink_taskmanager_job_task_operator_KafkaProducer_outgoing_byte_rate"
echo ""

echo "=== Flink ==="
check_metric "Flink Records/sec" "flink_taskmanager_job_task_operator_numRecordsInPerSecond"
check_metric "Flink Buffer Pool" "flink_taskmanager_Status_Network_TotalMemorySegments"
check_metric "Flink Backpressure" "flink_taskmanager_job_task_backPressuredTimeMsPerSecond"
check_metric "Flink Busy Time" "flink_taskmanager_job_task_busyTimeMsPerSecond"
check_metric "Buffer Debloating" "flink_taskmanager_job_task_Shuffle_Netty_Input_0_debloatedBufferSize"
echo ""

echo "=== Memory ==="
check_metric "Drools JVM Heap" "jvm_memory_used_bytes{area=\"heap\"}"
check_metric "Flink JVM Heap" "flink_taskmanager_Status_JVM_Memory_Heap_Used"
check_metric "OTEL/cAdvisor Memory" "go_memstats_heap_alloc_bytes{job=\"cadvisor\"}"
check_metric "Container Memory (cAdvisor)" "container_memory_usage_bytes{name=~\"reactive-.*\"}"
echo ""

echo "=== GC & Threads ==="
check_metric "GC Pause Count" "jvm_gc_pause_seconds_count"
check_metric "Flink GC Young" "flink_taskmanager_Status_JVM_GarbageCollector_G1_Young_Generation_Count"
check_metric "Flink GC Old" "flink_taskmanager_Status_JVM_GarbageCollector_G1_Old_Generation_Count"
check_metric "Drools Threads" "jvm_threads_live_threads"
check_metric "Flink Threads" "flink_taskmanager_Status_JVM_Threads_Count"
check_metric "cAdvisor Goroutines" "go_goroutines{job=\"cadvisor\"}"
echo ""

echo "=== Loki/Logs ==="
if curl -sf "${LOKI_URL}/ready" > /dev/null 2>&1; then
    labels=$(curl -s "${LOKI_URL}/loki/api/v1/labels" 2>/dev/null)
    if echo "$labels" | jq -e '.data | length > 0' > /dev/null 2>&1; then
        echo -e "${GREEN}[OK]${NC} Loki Labels Available"
        ((passed++))
    else
        echo -e "${RED}[NO DATA]${NC} Loki has no labels"
        ((failed++))
    fi
else
    echo -e "${RED}[DOWN]${NC} Loki not reachable"
    ((failed++))
fi
echo ""

echo "============================================"
echo -e "  ${GREEN}Passed: $passed${NC}  ${RED}Failed: $failed${NC}"
echo "============================================"

if [ $failed -gt 0 ]; then
    exit 1
else
    exit 0
fi
