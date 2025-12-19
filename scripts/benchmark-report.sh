#!/bin/bash
# Benchmark Report Generation
# Generates honest, comprehensive HTML and Markdown reports with clear methodology
#
# ARCHITECTURE: This script follows a shared data structure pattern:
# 1. extract_report_data() - Populates all report variables from JSON
# 2. serialize_html_report() - Serializes the data structure to HTML
# 3. serialize_markdown_report() - Serializes the same data structure to Markdown
#
# Supports both legacy layered benchmark format and new component benchmark format.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ============================================================================
# HELPER FUNCTIONS
# ============================================================================

round_int() {
    local val="$1"
    if [[ -z "$val" || "$val" == "null" ]]; then
        echo "0"
        return
    fi
    printf "%.0f" "$val" 2>/dev/null || echo "0"
}

# ============================================================================
# DATA EXTRACTION - Single source of truth for all report data
# ============================================================================

# Global variables populated by extract_report_data()
REPORT_COMPONENT=""
REPORT_COMPONENT_NAME=""
REPORT_COMPONENT_DESC=""
REPORT_TIMESTAMP=""
REPORT_COMMIT=""

# Throughput metrics
REPORT_PEAK_THROUGHPUT=0
REPORT_AVG_THROUGHPUT=0

# Event counts
REPORT_TOTAL_OPS=0
REPORT_SUCCESSFUL_OPS=0
REPORT_FAILED_OPS=0
REPORT_SUCCESS_RATE=0
REPORT_DURATION_SEC=0

# Latency metrics
REPORT_LATENCY_MIN=0
REPORT_LATENCY_MAX=0
REPORT_LATENCY_AVG=0
REPORT_P50=0
REPORT_P95=0
REPORT_P99=0

# Component timing
REPORT_GATEWAY_MS=0
REPORT_KAFKA_MS=0
REPORT_FLINK_MS=0
REPORT_DROOLS_MS=0

# Resource metrics
REPORT_PEAK_CPU=0
REPORT_PEAK_MEMORY=0

# Chart data (JSON arrays)
REPORT_THROUGHPUT_TIMELINE="[]"
REPORT_CPU_TIMELINE="[]"
REPORT_MEMORY_TIMELINE="[]"
REPORT_SAMPLE_EVENTS="[]"

# Component metadata (bash 3 compatible)
get_component_name() {
    case "$1" in
        http) echo "HTTP" ;;
        kafka) echo "Kafka" ;;
        flink) echo "Flink" ;;
        drools) echo "Drools" ;;
        gateway) echo "Gateway" ;;
        full) echo "Full End-to-End" ;;
        *) echo "$1" ;;
    esac
}

get_component_description() {
    case "$1" in
        http) echo "Gateway HTTP endpoint latency" ;;
        kafka) echo "Kafka produce/consume round-trip" ;;
        flink) echo "Kafka + Flink processing" ;;
        drools) echo "Rule evaluation via direct HTTP" ;;
        gateway) echo "HTTP + Kafka publish (no wait)" ;;
        full) echo "HTTP → Kafka → Flink → Drools" ;;
        *) echo "Unknown component" ;;
    esac
}

get_component_color() {
    case "$1" in
        http) echo "#3b82f6" ;;
        kafka) echo "#8b5cf6" ;;
        flink) echo "#06b6d4" ;;
        drools) echo "#10b981" ;;
        gateway) echo "#f59e0b" ;;
        full) echo "#22c55e" ;;
        *) echo "#6b7280" ;;
    esac
}

extract_report_data() {
    local component="$1"
    local results="$2"

    REPORT_COMPONENT="$component"
    REPORT_COMPONENT_NAME="$(get_component_name "$component")"
    REPORT_COMPONENT_DESC="$(get_component_description "$component")"
    REPORT_TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    REPORT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")

    # Try new format first, fall back to legacy format
    local data_path=""
    if echo "$results" | jq -e '.component' >/dev/null 2>&1; then
        # New component benchmark format (direct result)
        data_path=""
    elif echo "$results" | jq -e '.lastResult' >/dev/null 2>&1; then
        # Legacy layered benchmark format
        data_path=".lastResult"
    else
        echo "Warning: Unknown result format" >&2
        return 1
    fi

    # Throughput metrics
    if [[ -n "$data_path" ]]; then
        REPORT_PEAK_THROUGHPUT=$(round_int "$(echo "$results" | jq -r "${data_path}.outputPeakThroughput // ${data_path}.peakThroughput // 0" 2>/dev/null)")
        REPORT_AVG_THROUGHPUT=$(round_int "$(echo "$results" | jq -r "${data_path}.outputAvgThroughput // ${data_path}.avgThroughput // 0" 2>/dev/null)")
    else
        REPORT_PEAK_THROUGHPUT=$(round_int "$(echo "$results" | jq -r '.peakThroughput // 0' 2>/dev/null)")
        REPORT_AVG_THROUGHPUT=$(round_int "$(echo "$results" | jq -r '.avgThroughput // 0' 2>/dev/null)")
    fi

    # Event counts
    if [[ -n "$data_path" ]]; then
        REPORT_TOTAL_OPS=$(round_int "$(echo "$results" | jq -r "${data_path}.totalEventsSent // ${data_path}.totalOperations // 0" 2>/dev/null)")
        REPORT_SUCCESSFUL_OPS=$(round_int "$(echo "$results" | jq -r "${data_path}.successfulEvents // ${data_path}.successfulOperations // 0" 2>/dev/null)")
        REPORT_FAILED_OPS=$(round_int "$(echo "$results" | jq -r "${data_path}.timedOutEvents // ${data_path}.failedOperations // 0" 2>/dev/null)")
        local duration_ms=$(round_int "$(echo "$results" | jq -r "${data_path}.durationMs // 0" 2>/dev/null)")
    else
        REPORT_TOTAL_OPS=$(round_int "$(echo "$results" | jq -r '.totalOperations // 0' 2>/dev/null)")
        REPORT_SUCCESSFUL_OPS=$(round_int "$(echo "$results" | jq -r '.successfulOperations // 0' 2>/dev/null)")
        REPORT_FAILED_OPS=$(round_int "$(echo "$results" | jq -r '.failedOperations // 0' 2>/dev/null)")
        local duration_ms=$(round_int "$(echo "$results" | jq -r '.durationMs // 0' 2>/dev/null)")
    fi

    REPORT_DURATION_SEC=$((duration_ms / 1000))

    # Calculate success rate
    if [[ "$REPORT_TOTAL_OPS" -gt 0 ]]; then
        REPORT_SUCCESS_RATE=$((REPORT_SUCCESSFUL_OPS * 100 / REPORT_TOTAL_OPS))
    else
        REPORT_SUCCESS_RATE=0
    fi

    # Latency metrics
    if [[ -n "$data_path" ]]; then
        REPORT_LATENCY_MIN=$(round_int "$(echo "$results" | jq -r "${data_path}.latencyMin // ${data_path}.latency.min // 0" 2>/dev/null)")
        REPORT_LATENCY_MAX=$(round_int "$(echo "$results" | jq -r "${data_path}.latencyMax // ${data_path}.latency.max // 0" 2>/dev/null)")
        REPORT_LATENCY_AVG=$(round_int "$(echo "$results" | jq -r "${data_path}.latencyAvg // ${data_path}.latency.avg // 0" 2>/dev/null)")
        REPORT_P50=$(round_int "$(echo "$results" | jq -r "${data_path}.latencyP50 // ${data_path}.latency.p50 // 0" 2>/dev/null)")
        REPORT_P95=$(round_int "$(echo "$results" | jq -r "${data_path}.latencyP95 // ${data_path}.latency.p95 // 0" 2>/dev/null)")
        REPORT_P99=$(round_int "$(echo "$results" | jq -r "${data_path}.latencyP99 // ${data_path}.latency.p99 // 0" 2>/dev/null)")
    else
        REPORT_LATENCY_MIN=$(round_int "$(echo "$results" | jq -r '.latency.min // 0' 2>/dev/null)")
        REPORT_LATENCY_MAX=$(round_int "$(echo "$results" | jq -r '.latency.max // 0' 2>/dev/null)")
        REPORT_LATENCY_AVG=$(round_int "$(echo "$results" | jq -r '.latency.avg // 0' 2>/dev/null)")
        REPORT_P50=$(round_int "$(echo "$results" | jq -r '.latency.p50 // 0' 2>/dev/null)")
        REPORT_P95=$(round_int "$(echo "$results" | jq -r '.latency.p95 // 0' 2>/dev/null)")
        REPORT_P99=$(round_int "$(echo "$results" | jq -r '.latency.p99 // 0' 2>/dev/null)")
    fi

    # Component timing
    if [[ -n "$data_path" ]]; then
        REPORT_GATEWAY_MS=$(round_int "$(echo "$results" | jq -r "${data_path}.componentTimingAvg.gatewayMs // ${data_path}.componentTiming.gatewayMs // 0" 2>/dev/null)")
        REPORT_KAFKA_MS=$(round_int "$(echo "$results" | jq -r "${data_path}.componentTimingAvg.kafkaMs // ${data_path}.componentTiming.kafkaMs // 0" 2>/dev/null)")
        REPORT_FLINK_MS=$(round_int "$(echo "$results" | jq -r "${data_path}.componentTimingAvg.flinkMs // ${data_path}.componentTiming.flinkMs // 0" 2>/dev/null)")
        REPORT_DROOLS_MS=$(round_int "$(echo "$results" | jq -r "${data_path}.componentTimingAvg.droolsMs // ${data_path}.componentTiming.droolsMs // 0" 2>/dev/null)")
    else
        REPORT_GATEWAY_MS=$(round_int "$(echo "$results" | jq -r '.componentTiming.gatewayMs // 0' 2>/dev/null)")
        REPORT_KAFKA_MS=$(round_int "$(echo "$results" | jq -r '.componentTiming.kafkaMs // 0' 2>/dev/null)")
        REPORT_FLINK_MS=$(round_int "$(echo "$results" | jq -r '.componentTiming.flinkMs // 0' 2>/dev/null)")
        REPORT_DROOLS_MS=$(round_int "$(echo "$results" | jq -r '.componentTiming.droolsMs // 0' 2>/dev/null)")
    fi

    # Resource metrics
    if [[ -n "$data_path" ]]; then
        REPORT_PEAK_CPU=$(round_int "$(echo "$results" | jq -r "${data_path}.peakCpuPercent // ${data_path}.peakCpu // 0" 2>/dev/null)")
        REPORT_PEAK_MEMORY=$(round_int "$(echo "$results" | jq -r "${data_path}.peakMemoryPercent // ${data_path}.peakMemory // 0" 2>/dev/null)")
    else
        REPORT_PEAK_CPU=$(round_int "$(echo "$results" | jq -r '.peakCpu // 0' 2>/dev/null)")
        REPORT_PEAK_MEMORY=$(round_int "$(echo "$results" | jq -r '.peakMemory // 0' 2>/dev/null)")
    fi

    # Chart data
    if [[ -n "$data_path" ]]; then
        REPORT_THROUGHPUT_TIMELINE=$(echo "$results" | jq -c "${data_path}.throughputTimeline // ${data_path}.outputThroughputTimeline // []" 2>/dev/null || echo "[]")
        REPORT_CPU_TIMELINE=$(echo "$results" | jq -c "${data_path}.cpuTimeline // []" 2>/dev/null || echo "[]")
        REPORT_MEMORY_TIMELINE=$(echo "$results" | jq -c "${data_path}.memoryTimeline // []" 2>/dev/null || echo "[]")
        REPORT_SAMPLE_EVENTS=$(echo "$results" | jq -c "${data_path}.sampleEvents // []" 2>/dev/null || echo "[]")
    else
        REPORT_THROUGHPUT_TIMELINE=$(echo "$results" | jq -c '.throughputTimeline // []' 2>/dev/null || echo "[]")
        REPORT_CPU_TIMELINE=$(echo "$results" | jq -c '.cpuTimeline // []' 2>/dev/null || echo "[]")
        REPORT_MEMORY_TIMELINE=$(echo "$results" | jq -c '.memoryTimeline // []' 2>/dev/null || echo "[]")
        REPORT_SAMPLE_EVENTS=$(echo "$results" | jq -c '.sampleEvents // []' 2>/dev/null || echo "[]")
    fi
}

# ============================================================================
# MARKDOWN SERIALIZATION
# ============================================================================

serialize_markdown_report() {
    local report_dir="$1"

    cat > "$report_dir/README.md" << MDEOF
# Benchmark Report: ${REPORT_COMPONENT_NAME}

**${REPORT_COMPONENT_DESC}**

| Metric | Value |
|--------|-------|
| Peak Throughput | **${REPORT_PEAK_THROUGHPUT} events/sec** |
| Average Throughput | ${REPORT_AVG_THROUGHPUT} events/sec |
| Total Operations | ${REPORT_TOTAL_OPS} |
| Successful | ${REPORT_SUCCESSFUL_OPS} |
| Failed | ${REPORT_FAILED_OPS} |
| Success Rate | ${REPORT_SUCCESS_RATE}% |
| Duration | ${REPORT_DURATION_SEC}s |

## Latency

| Percentile | Value |
|------------|-------|
| Min | ${REPORT_LATENCY_MIN}ms |
| P50 (Median) | ${REPORT_P50}ms |
| P95 | ${REPORT_P95}ms |
| P99 | ${REPORT_P99}ms |
| Max | ${REPORT_LATENCY_MAX}ms |

## Component Timing

| Component | Time |
|-----------|------|
| Gateway | ${REPORT_GATEWAY_MS}ms |
| Kafka | ${REPORT_KAFKA_MS}ms |
| Flink | ${REPORT_FLINK_MS}ms |
| Drools | ${REPORT_DROOLS_MS}ms |

## Resources

| Metric | Value |
|--------|-------|
| Peak CPU | ${REPORT_PEAK_CPU}% |
| Peak Memory | ${REPORT_PEAK_MEMORY}% |

---
*Generated: ${REPORT_TIMESTAMP} | Commit: ${REPORT_COMMIT}*
MDEOF

    echo "Markdown report generated: $report_dir/README.md"
}

# ============================================================================
# HTML SERIALIZATION
# ============================================================================

serialize_html_report() {
    local report_dir="$1"
    local results="$2"

    # Save raw results JSON for reference
    echo "$results" > "$report_dir/results.json"

    local component_color="$(get_component_color "$REPORT_COMPONENT")"

    # Calculate component bar percentages
    local total_component_ms=$((REPORT_GATEWAY_MS + REPORT_KAFKA_MS + REPORT_FLINK_MS + REPORT_DROOLS_MS))
    local total_for_pct=$((total_component_ms > 0 ? total_component_ms : 1))
    local gw_pct=$((REPORT_GATEWAY_MS * 100 / total_for_pct))
    local kf_pct=$((REPORT_KAFKA_MS * 100 / total_for_pct))
    local fl_pct=$((REPORT_FLINK_MS * 100 / total_for_pct))
    local dr_pct=$((REPORT_DROOLS_MS * 100 / total_for_pct))

    # Parse sample events
    local successful_samples=$(echo "$REPORT_SAMPLE_EVENTS" | jq -c '[.[] | select(.status == "success")] | .[0:2]' 2>/dev/null || echo "[]")
    local error_samples=$(echo "$REPORT_SAMPLE_EVENTS" | jq -c '[.[] | select(.status != "success")] | .[0:10]' 2>/dev/null || echo "[]")
    local successful_count=$(echo "$successful_samples" | jq 'length' 2>/dev/null || echo "0")
    local error_count=$(echo "$error_samples" | jq 'length' 2>/dev/null || echo "0")

    # Extract embedded trace data for trace viewer
    local trace_data=$(echo "$REPORT_SAMPLE_EVENTS" | jq -c '[.[] | select(.jaegerTrace != null) | {traceId: .traceId, trace: .jaegerTrace}] | INDEX(.traceId)' 2>/dev/null || echo "{}")
    local logs_data=$(echo "$REPORT_SAMPLE_EVENTS" | jq -c '[.[] | select(.lokiLogs != null and (.lokiLogs | length) > 0) | {traceId: .traceId, logs: .lokiLogs}] | INDEX(.traceId)' 2>/dev/null || echo "{}")

    # Generate HTML
    cat > "$report_dir/index.html" << 'HTMLEOF'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
HTMLEOF

    echo "    <title>Benchmark: ${REPORT_COMPONENT_NAME} - ${REPORT_PEAK_THROUGHPUT} events/sec</title>" >> "$report_dir/index.html"

    cat >> "$report_dir/index.html" << 'HTMLEOF'
    <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; background: #0f172a; color: #e2e8f0; padding: 2rem; max-width: 1400px; margin: 0 auto; line-height: 1.5; }
        h1 { font-size: 1rem; color: #64748b; font-weight: 400; margin-bottom: 0.5rem; }
        h2 { font-size: 0.75rem; color: #64748b; text-transform: uppercase; letter-spacing: 0.1em; margin: 2rem 0 1rem; border-bottom: 1px solid #334155; padding-bottom: 0.5rem; }
        .hero { margin-bottom: 2rem; }
        .hero .value { font-size: 4rem; font-weight: 700; line-height: 1; }
        .hero .unit { font-size: 1.25rem; color: #64748b; }
        .hero .subtitle { color: #94a3b8; font-size: 0.875rem; margin-top: 0.5rem; }
        .meta { color: #64748b; font-size: 0.75rem; margin-bottom: 2rem; }
        .meta strong { color: #94a3b8; }
        .card { background: #1e293b; border-radius: 8px; padding: 1.25rem; }
        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-bottom: 1.5rem; }
        .grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; margin-bottom: 1.5rem; }
        .grid-4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1rem; margin-bottom: 1.5rem; }
        .stat-label { font-size: 0.7rem; color: #64748b; text-transform: uppercase; letter-spacing: 0.05em; }
        .stat-value { font-size: 1.5rem; font-weight: 700; color: #f1f5f9; }
        .stat-value.green { color: #22c55e; }
        .stat-value.yellow { color: #eab308; }
        .stat-value.red { color: #ef4444; }
        .stat-unit { font-size: 0.7rem; color: #64748b; }
        .chart-container { background: #1e293b; border-radius: 8px; padding: 1.25rem; margin-bottom: 1.5rem; }
        .chart-wrapper { height: 180px; }
        .chart-title { font-size: 0.75rem; color: #94a3b8; margin-bottom: 0.75rem; }
        .info-box { background: #1e3a5f; border-left: 3px solid #3b82f6; padding: 1rem; border-radius: 0 8px 8px 0; margin-bottom: 1.5rem; font-size: 0.875rem; }
        .info-box strong { color: #60a5fa; }
        .component-bar { display: flex; height: 32px; border-radius: 6px; overflow: hidden; margin: 1rem 0; }
        .component-bar > div { display: flex; align-items: center; justify-content: center; font-size: 0.7rem; font-weight: 600; color: white; min-width: 40px; }
        .legend { display: flex; gap: 1rem; flex-wrap: wrap; font-size: 0.75rem; }
        .legend-item { display: flex; align-items: center; gap: 0.375rem; }
        .legend-dot { width: 10px; height: 10px; border-radius: 2px; }

        /* Sample Events Table */
        .events-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; margin-top: 1rem; }
        .events-table th { background: #0f172a; color: #64748b; font-weight: 500; text-align: left; padding: 0.75rem 0.5rem; border-bottom: 1px solid #334155; }
        .events-table td { padding: 0.75rem 0.5rem; border-bottom: 1px solid #1e293b; vertical-align: middle; }
        .events-table tr:hover { background: #1e3a5f; }
        .events-table .id { font-family: monospace; color: #60a5fa; font-size: 0.7rem; }
        .events-table .trace-id { font-family: monospace; color: #94a3b8; font-size: 0.65rem; max-width: 80px; overflow: hidden; text-overflow: ellipsis; }
        .events-table .timing { color: #22c55e; font-family: monospace; }
        .events-table .status-success { color: #22c55e; }
        .events-table .status-error { color: #ef4444; }
        .events-table .btn { background: #334155; border: none; color: #e2e8f0; padding: 0.25rem 0.5rem; border-radius: 4px; cursor: pointer; font-size: 0.7rem; margin-right: 0.25rem; }
        .events-table .btn:hover { background: #475569; }
        .events-table .btn:disabled { opacity: 0.5; cursor: not-allowed; }

        /* Modal */
        .modal { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); z-index: 1000; }
        .modal.active { display: flex; align-items: center; justify-content: center; }
        .modal-content { background: #1e293b; border-radius: 8px; width: 90%; max-width: 900px; max-height: 80vh; overflow: hidden; display: flex; flex-direction: column; }
        .modal-header { display: flex; justify-content: space-between; align-items: center; padding: 1rem; border-bottom: 1px solid #334155; }
        .modal-header h3 { font-size: 1rem; color: #f1f5f9; }
        .modal-close { background: none; border: none; color: #64748b; font-size: 1.5rem; cursor: pointer; }
        .modal-close:hover { color: #f1f5f9; }
        .modal-body { overflow-y: auto; padding: 1rem; flex: 1; }

        /* Trace Viewer */
        .trace-timeline { font-family: monospace; font-size: 0.75rem; }
        .trace-span { display: flex; align-items: center; margin-bottom: 0.5rem; }
        .trace-span-bar { height: 20px; border-radius: 3px; display: flex; align-items: center; padding: 0 0.5rem; color: white; font-size: 0.65rem; white-space: nowrap; }
        .trace-span-label { min-width: 150px; color: #94a3b8; }
        .trace-span-duration { min-width: 80px; color: #64748b; text-align: right; margin-left: 0.5rem; }

        /* Log Viewer */
        .log-entry { font-family: monospace; font-size: 0.7rem; padding: 0.5rem; border-bottom: 1px solid #334155; }
        .log-entry:hover { background: #0f172a; }
        .log-timestamp { color: #64748b; margin-right: 1rem; }
        .log-level-info { color: #3b82f6; }
        .log-level-warn { color: #eab308; }
        .log-level-error { color: #ef4444; }
        .log-message { color: #e2e8f0; }

        .footer { margin-top: 2rem; color: #475569; font-size: 0.7rem; text-align: center; }
        @media (max-width: 768px) { .grid-2, .grid-3, .grid-4 { grid-template-columns: 1fr; } .hero .value { font-size: 3rem; } }
    </style>
</head>
<body>
HTMLEOF

    # Hero section with component-specific color
    cat >> "$report_dir/index.html" << HTMLEOF
    <div class="hero">
        <h1>${REPORT_COMPONENT_DESC}</h1>
        <div><span class="value" style="color: ${component_color};">${REPORT_PEAK_THROUGHPUT}</span> <span class="unit">events/sec</span></div>
        <div class="subtitle">Peak throughput for ${REPORT_COMPONENT_NAME} benchmark</div>
    </div>

    <div class="meta">
        <strong>${REPORT_TOTAL_OPS}</strong> operations |
        <strong>${REPORT_SUCCESSFUL_OPS}</strong> successful |
        <strong>${REPORT_FAILED_OPS}</strong> failed |
        <strong>${REPORT_SUCCESS_RATE}%</strong> success rate |
        <strong>${REPORT_DURATION_SEC}s</strong> duration |
        Commit: <strong>${REPORT_COMMIT}</strong> |
        ${REPORT_TIMESTAMP}
    </div>
HTMLEOF

    # Throughput section with chart
    cat >> "$report_dir/index.html" << HTMLEOF
    <h2>Throughput</h2>

    <div class="chart-container">
        <div class="chart-title">Throughput Over Time (events/sec)</div>
        <div class="chart-wrapper">
            <canvas id="throughputChart"></canvas>
        </div>
    </div>

    <div class="grid-2">
        <div class="card">
            <div class="stat-label">Peak Throughput</div>
            <div class="stat-value green">${REPORT_PEAK_THROUGHPUT}</div>
            <div class="stat-unit">events/sec</div>
        </div>
        <div class="card">
            <div class="stat-label">Average Throughput</div>
            <div class="stat-value">${REPORT_AVG_THROUGHPUT}</div>
            <div class="stat-unit">events/sec</div>
        </div>
    </div>

    <h2>Latency Distribution</h2>

    <div class="grid-4">
        <div class="card">
            <div class="stat-label">P50 (Median)</div>
            <div class="stat-value">${REPORT_P50}</div>
            <div class="stat-unit">ms</div>
        </div>
        <div class="card">
            <div class="stat-label">P95</div>
            <div class="stat-value">${REPORT_P95}</div>
            <div class="stat-unit">ms</div>
        </div>
        <div class="card">
            <div class="stat-label">P99</div>
            <div class="stat-value">${REPORT_P99}</div>
            <div class="stat-unit">ms</div>
        </div>
        <div class="card">
            <div class="stat-label">Max</div>
            <div class="stat-value">${REPORT_LATENCY_MAX}</div>
            <div class="stat-unit">ms</div>
        </div>
    </div>
HTMLEOF

    # Component timing section (only for relevant benchmarks)
    if [[ "$REPORT_COMPONENT" == "full" || "$REPORT_COMPONENT" == "gateway" ]]; then
        cat >> "$report_dir/index.html" << HTMLEOF
    <h2>Component Timing Breakdown</h2>

    <div class="card">
        <div class="component-bar">
            <div style="width: ${gw_pct}%; background: #3b82f6;">${REPORT_GATEWAY_MS}ms</div>
            <div style="width: ${kf_pct}%; background: #8b5cf6;">${REPORT_KAFKA_MS}ms</div>
            <div style="width: ${fl_pct}%; background: #06b6d4;">${REPORT_FLINK_MS}ms</div>
            <div style="width: ${dr_pct}%; background: #10b981;">${REPORT_DROOLS_MS}ms</div>
        </div>
        <div class="legend">
            <div class="legend-item"><span class="legend-dot" style="background:#3b82f6;"></span> Gateway ${REPORT_GATEWAY_MS}ms</div>
            <div class="legend-item"><span class="legend-dot" style="background:#8b5cf6;"></span> Kafka ${REPORT_KAFKA_MS}ms</div>
            <div class="legend-item"><span class="legend-dot" style="background:#06b6d4;"></span> Flink ${REPORT_FLINK_MS}ms</div>
            <div class="legend-item"><span class="legend-dot" style="background:#10b981;"></span> Drools ${REPORT_DROOLS_MS}ms</div>
        </div>
    </div>
HTMLEOF
    fi

    # Resource usage section with charts
    cat >> "$report_dir/index.html" << HTMLEOF
    <h2>Resource Usage</h2>

    <div class="grid-2">
        <div class="chart-container">
            <div class="chart-title">CPU Usage (%)</div>
            <div class="chart-wrapper">
                <canvas id="cpuChart"></canvas>
            </div>
        </div>
        <div class="chart-container">
            <div class="chart-title">Memory Usage (%)</div>
            <div class="chart-wrapper">
                <canvas id="memoryChart"></canvas>
            </div>
        </div>
    </div>

    <div class="grid-2">
        <div class="card">
            <div class="stat-label">Peak CPU</div>
            <div class="stat-value">${REPORT_PEAK_CPU}%</div>
        </div>
        <div class="card">
            <div class="stat-label">Peak Memory</div>
            <div class="stat-value">${REPORT_PEAK_MEMORY}%</div>
        </div>
    </div>
HTMLEOF

    # Sample Events Table
    cat >> "$report_dir/index.html" << HTMLEOF
    <h2>Sample Events</h2>

    <div class="info-box">
        <strong>Event Samples:</strong> Up to 2 successful events and up to 10 error/timeout events are captured for analysis.
        Click "Trace" to view the distributed trace timeline, or "Logs" to see correlated log entries.
    </div>

    <div class="card">
        <table class="events-table">
            <thead>
                <tr>
                    <th>Status</th>
                    <th>Event ID</th>
                    <th>Trace ID</th>
                    <th>Latency</th>
HTMLEOF

    if [[ "$REPORT_COMPONENT" == "full" || "$REPORT_COMPONENT" == "gateway" ]]; then
        echo "                    <th>Gateway</th><th>Kafka</th><th>Flink</th><th>Drools</th>" >> "$report_dir/index.html"
    fi

    cat >> "$report_dir/index.html" << HTMLEOF
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
HTMLEOF

    # Generate table rows for successful events
    echo "$successful_samples" | jq -c '.[]' 2>/dev/null | while read -r event; do
        local eid=$(echo "$event" | jq -r '.id // "unknown"')
        local trace_id=$(echo "$event" | jq -r '.traceId // ""')
        local latency_ms=$(echo "$event" | jq -r '.latencyMs // 0')
        local gw=$(echo "$event" | jq -r '.componentTiming.gatewayMs // "-"')
        local kf=$(echo "$event" | jq -r '.componentTiming.kafkaMs // "-"')
        local fl=$(echo "$event" | jq -r '.componentTiming.flinkMs // "-"')
        local dr=$(echo "$event" | jq -r '.componentTiming.droolsMs // "-"')
        local has_trace=$(echo "$event" | jq -r 'if .jaegerTrace != null then "true" else "false" end')
        local has_logs=$(echo "$event" | jq -r 'if .lokiLogs != null and (.lokiLogs | length) > 0 then "true" else "false" end')

        cat >> "$report_dir/index.html" << ROWEOF
                <tr>
                    <td><span class="status-success">✓ Success</span></td>
                    <td class="id">${eid}</td>
                    <td class="trace-id" title="${trace_id}">${trace_id:0:16}...</td>
                    <td class="timing">${latency_ms}ms</td>
ROWEOF

        if [[ "$REPORT_COMPONENT" == "full" || "$REPORT_COMPONENT" == "gateway" ]]; then
            echo "                    <td class=\"timing\">${gw}ms</td><td class=\"timing\">${kf}ms</td><td class=\"timing\">${fl}ms</td><td class=\"timing\">${dr}ms</td>" >> "$report_dir/index.html"
        fi

        local trace_btn_disabled=""
        local logs_btn_disabled=""
        [[ "$has_trace" == "false" ]] && trace_btn_disabled="disabled"
        [[ "$has_logs" == "false" ]] && logs_btn_disabled="disabled"

        cat >> "$report_dir/index.html" << ROWEOF
                    <td>
                        <button class="btn" onclick="showTrace('${trace_id}')" ${trace_btn_disabled}>Trace</button>
                        <button class="btn" onclick="showLogs('${trace_id}')" ${logs_btn_disabled}>Logs</button>
                    </td>
                </tr>
ROWEOF
    done

    # Generate table rows for error events
    echo "$error_samples" | jq -c '.[]' 2>/dev/null | while read -r event; do
        local eid=$(echo "$event" | jq -r '.id // "unknown"')
        local trace_id=$(echo "$event" | jq -r '.traceId // ""')
        local latency_ms=$(echo "$event" | jq -r '.latencyMs // 0')
        local error=$(echo "$event" | jq -r '.error // "Unknown error"')
        local event_status=$(echo "$event" | jq -r '.status // "error"')
        local has_trace=$(echo "$event" | jq -r 'if .jaegerTrace != null then "true" else "false" end')
        local has_logs=$(echo "$event" | jq -r 'if .lokiLogs != null and (.lokiLogs | length) > 0 then "true" else "false" end')

        cat >> "$report_dir/index.html" << ROWEOF
                <tr>
                    <td><span class="status-error">✗ ${event_status}</span></td>
                    <td class="id">${eid}</td>
                    <td class="trace-id" title="${trace_id}">${trace_id:0:16}...</td>
                    <td class="timing">${latency_ms}ms</td>
ROWEOF

        if [[ "$REPORT_COMPONENT" == "full" || "$REPORT_COMPONENT" == "gateway" ]]; then
            echo "                    <td colspan=\"4\" style=\"color:#ef4444;font-size:0.7rem;\">${error:0:50}</td>" >> "$report_dir/index.html"
        fi

        local trace_btn_disabled=""
        local logs_btn_disabled=""
        [[ "$has_trace" == "false" ]] && trace_btn_disabled="disabled"
        [[ "$has_logs" == "false" ]] && logs_btn_disabled="disabled"

        cat >> "$report_dir/index.html" << ROWEOF
                    <td>
                        <button class="btn" onclick="showTrace('${trace_id}')" ${trace_btn_disabled}>Trace</button>
                        <button class="btn" onclick="showLogs('${trace_id}')" ${logs_btn_disabled}>Logs</button>
                    </td>
                </tr>
ROWEOF
    done

    cat >> "$report_dir/index.html" << HTMLEOF
            </tbody>
        </table>
    </div>
HTMLEOF

    # Modals for trace and log viewers
    cat >> "$report_dir/index.html" << HTMLEOF
    <!-- Trace Viewer Modal -->
    <div id="traceModal" class="modal">
        <div class="modal-content">
            <div class="modal-header">
                <h3>Trace Timeline</h3>
                <button class="modal-close" onclick="closeModal('traceModal')">&times;</button>
            </div>
            <div class="modal-body">
                <div id="traceViewer" class="trace-timeline"></div>
            </div>
        </div>
    </div>

    <!-- Logs Viewer Modal -->
    <div id="logsModal" class="modal">
        <div class="modal-content">
            <div class="modal-header">
                <h3>Correlated Logs</h3>
                <button class="modal-close" onclick="closeModal('logsModal')">&times;</button>
            </div>
            <div class="modal-body">
                <div id="logsViewer"></div>
            </div>
        </div>
    </div>

    <div class="footer">
        Generated ${REPORT_TIMESTAMP} | ${REPORT_COMPONENT_NAME} Benchmark | Reactive System
    </div>

    <!-- Embedded Data -->
    <script id="trace-data" type="application/json">${trace_data}</script>
    <script id="logs-data" type="application/json">${logs_data}</script>

    <script>
        Chart.defaults.animation = false;
        Chart.defaults.responsive = true;
        Chart.defaults.maintainAspectRatio = false;

        // Chart data
        const throughputTimeline = ${REPORT_THROUGHPUT_TIMELINE};
        const cpuTimeline = ${REPORT_CPU_TIMELINE};
        const memoryTimeline = ${REPORT_MEMORY_TIMELINE};

        // Throughput Chart
        if (throughputTimeline && throughputTimeline.length > 0) {
            new Chart(document.getElementById('throughputChart'), {
                type: 'line',
                data: {
                    labels: throughputTimeline.map((_, i) => i + 's'),
                    datasets: [{
                        label: 'Throughput',
                        data: throughputTimeline,
                        borderColor: '${component_color}',
                        backgroundColor: '${component_color}22',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0,
                        borderWidth: 2
                    }]
                },
                options: {
                    plugins: { legend: { display: false } },
                    scales: {
                        y: { beginAtZero: true, grid: { color: '#334155' }, ticks: { color: '#64748b' } },
                        x: { grid: { display: false }, ticks: { color: '#64748b', maxTicksLimit: 10 } }
                    }
                }
            });
        } else {
            document.getElementById('throughputChart').parentElement.innerHTML = '<div style="color:#64748b;text-align:center;padding:2rem;">No timeline data available</div>';
        }

        // CPU Chart
        if (cpuTimeline && cpuTimeline.length > 0) {
            new Chart(document.getElementById('cpuChart'), {
                type: 'line',
                data: {
                    labels: cpuTimeline.map((_, i) => i + 's'),
                    datasets: [{
                        label: 'CPU %',
                        data: cpuTimeline,
                        borderColor: '#f59e0b',
                        backgroundColor: '#f59e0b22',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0,
                        borderWidth: 2
                    }]
                },
                options: {
                    plugins: { legend: { display: false } },
                    scales: {
                        y: { beginAtZero: true, max: 100, grid: { color: '#334155' }, ticks: { color: '#64748b' } },
                        x: { grid: { display: false }, ticks: { color: '#64748b', maxTicksLimit: 10 } }
                    }
                }
            });
        } else {
            document.getElementById('cpuChart').parentElement.innerHTML = '<div style="color:#64748b;text-align:center;padding:2rem;">No CPU data available</div>';
        }

        // Memory Chart
        if (memoryTimeline && memoryTimeline.length > 0) {
            new Chart(document.getElementById('memoryChart'), {
                type: 'line',
                data: {
                    labels: memoryTimeline.map((_, i) => i + 's'),
                    datasets: [{
                        label: 'Memory %',
                        data: memoryTimeline,
                        borderColor: '#8b5cf6',
                        backgroundColor: '#8b5cf622',
                        fill: true,
                        tension: 0.3,
                        pointRadius: 0,
                        borderWidth: 2
                    }]
                },
                options: {
                    plugins: { legend: { display: false } },
                    scales: {
                        y: { beginAtZero: true, max: 100, grid: { color: '#334155' }, ticks: { color: '#64748b' } },
                        x: { grid: { display: false }, ticks: { color: '#64748b', maxTicksLimit: 10 } }
                    }
                }
            });
        } else {
            document.getElementById('memoryChart').parentElement.innerHTML = '<div style="color:#64748b;text-align:center;padding:2rem;">No memory data available</div>';
        }

        // Trace and Log Viewer Functions
        const traceData = JSON.parse(document.getElementById('trace-data').textContent || '{}');
        const logsData = JSON.parse(document.getElementById('logs-data').textContent || '{}');

        function showTrace(traceId) {
            const data = traceData[traceId];
            if (!data || !data.trace) {
                alert('No trace data available for this event');
                return;
            }

            const trace = data.trace;
            const spans = trace.spans.sort((a, b) => a.startTime - b.startTime);
            const baseTime = spans[0]?.startTime || 0;
            const maxDuration = Math.max(...spans.map(s => (s.startTime - baseTime) + s.duration));

            const colors = {
                'gateway-service': '#3b82f6',
                'flink-processor': '#06b6d4',
                'drools-service': '#10b981',
                'kafka': '#8b5cf6'
            };

            const html = spans.map(span => {
                const offset = ((span.startTime - baseTime) / maxDuration) * 80;
                const width = Math.max((span.duration / maxDuration) * 80, 2);
                const serviceName = trace.processes[span.processID]?.serviceName || 'unknown';
                const color = colors[serviceName] || '#64748b';
                const durationMs = (span.duration / 1000).toFixed(2);

                return \`
                    <div class="trace-span">
                        <span class="trace-span-label">\${serviceName}</span>
                        <div style="flex:1;position:relative;height:20px;">
                            <div class="trace-span-bar" style="position:absolute;left:\${offset}%;width:\${width}%;background:\${color};">
                                \${span.operationName}
                            </div>
                        </div>
                        <span class="trace-span-duration">\${durationMs}ms</span>
                    </div>
                \`;
            }).join('');

            document.getElementById('traceViewer').innerHTML = html || '<div style="color:#64748b;">No spans found</div>';
            document.getElementById('traceModal').classList.add('active');
        }

        function showLogs(traceId) {
            const data = logsData[traceId];
            if (!data || !data.logs || data.logs.length === 0) {
                alert('No logs available for this event');
                return;
            }

            const html = data.logs.map(log => {
                const ts = new Date(parseInt(log.timestamp) / 1000000).toISOString().substr(11, 12);
                let level = 'info';
                let message = log.line;

                if (log.fields) {
                    level = log.fields.level || 'info';
                    message = log.fields.msg || log.fields.message || log.line;
                }

                const levelClass = level === 'error' ? 'log-level-error' : level === 'warn' ? 'log-level-warn' : 'log-level-info';

                return \`
                    <div class="log-entry">
                        <span class="log-timestamp">\${ts}</span>
                        <span class="\${levelClass}">[\${level.toUpperCase()}]</span>
                        <span class="log-message">\${message}</span>
                    </div>
                \`;
            }).join('');

            document.getElementById('logsViewer').innerHTML = html;
            document.getElementById('logsModal').classList.add('active');
        }

        function closeModal(id) {
            document.getElementById(id).classList.remove('active');
        }

        // Close modal on escape key
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                document.querySelectorAll('.modal.active').forEach(m => m.classList.remove('active'));
            }
        });

        // Close modal on background click
        document.querySelectorAll('.modal').forEach(modal => {
            modal.addEventListener('click', (e) => {
                if (e.target === modal) modal.classList.remove('active');
            });
        });
    </script>
</body>
</html>
HTMLEOF

    echo "HTML report generated: $report_dir/index.html"
}

# ============================================================================
# PUBLIC API - Main entry points for report generation
# ============================================================================

# Generate both HTML and Markdown reports using the shared data structure
# Usage: generate_report <component> <report_dir> <results_json>
generate_report() {
    local component="$1"
    local report_dir="$2"
    local results="$3"

    # Step 1: Extract all data into shared variables
    extract_report_data "$component" "$results"

    # Step 2: Serialize to both formats using the same data
    serialize_html_report "$report_dir" "$results"
    serialize_markdown_report "$report_dir"
}

# Legacy API for backwards compatibility (layer = component)
generate_html_report() {
    local layer="$1"
    local report_dir="$2"
    local results="$3"

    generate_report "$layer" "$report_dir" "$results"
}

generate_markdown_report() {
    local layer="$1"
    local report_dir="$2"
    local results="$3"

    if [[ -z "$REPORT_COMPONENT" ]]; then
        extract_report_data "$layer" "$results"
    fi
    serialize_markdown_report "$report_dir"
}
