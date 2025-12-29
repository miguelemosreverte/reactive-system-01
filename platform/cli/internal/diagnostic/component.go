package diagnostic

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// ComponentDiagnostics provides per-component diagnostic collection
type ComponentDiagnostics struct {
	client *http.Client
}

// NewComponentDiagnostics creates a new component diagnostics collector
func NewComponentDiagnostics() *ComponentDiagnostics {
	return &ComponentDiagnostics{
		client: &http.Client{Timeout: 5 * time.Second},
	}
}

// ComponentType defines the type of component for specialized diagnostics
type ComponentType string

const (
	ComponentTypeJVM    ComponentType = "jvm"
	ComponentTypeGo     ComponentType = "go"
	ComponentTypeNative ComponentType = "native"
)

// ComponentInfo defines a component's diagnostic endpoints
type ComponentInfo struct {
	Name          string
	DisplayName   string
	Type          ComponentType
	Container     string
	ActuatorURL   string // Spring Boot actuator base URL
	PrometheusURL string // Prometheus metrics endpoint
	HealthURL     string
	MemoryLimitMB int64
}

// PlatformComponents returns all platform components with their diagnostic endpoints
func PlatformComponents() []ComponentInfo {
	return []ComponentInfo{
		{
			Name:          "application",
			DisplayName:   "Application",
			Type:          ComponentTypeJVM,
			Container:     "reactive-application",
			ActuatorURL:   "http://localhost:8080/actuator",
			PrometheusURL: "http://localhost:8080/actuator/prometheus",
			HealthURL:     "http://localhost:8080/actuator/health",
			MemoryLimitMB: 1536,
		},
		{
			Name:          "drools",
			DisplayName:   "Drools Engine",
			Type:          ComponentTypeJVM,
			Container:     "reactive-drools",
			ActuatorURL:   "http://localhost:8180/actuator",
			PrometheusURL: "http://localhost:8180/actuator/prometheus",
			HealthURL:     "http://localhost:8180/actuator/health",
			MemoryLimitMB: 512,
		},
		{
			Name:          "flink-jobmanager",
			DisplayName:   "Flink JobManager",
			Type:          ComponentTypeJVM,
			Container:     "reactive-flink-jobmanager",
			PrometheusURL: "",
			HealthURL:     "http://localhost:8081/overview",
			MemoryLimitMB: 768,
		},
		{
			Name:          "flink-taskmanager",
			DisplayName:   "Flink TaskManager",
			Type:          ComponentTypeJVM,
			Container:     "reactive-system-01-flink-taskmanager-1",
			PrometheusURL: "http://localhost:9249/metrics",
			HealthURL:     "",
			MemoryLimitMB: 1408,
		},
		{
			Name:          "kafka",
			DisplayName:   "Kafka Broker",
			Type:          ComponentTypeJVM,
			Container:     "reactive-kafka",
			PrometheusURL: "",
			HealthURL:     "",
			MemoryLimitMB: 1536,
		},
		{
			Name:          "otel-collector",
			DisplayName:   "OTEL Collector",
			Type:          ComponentTypeGo,
			Container:     "reactive-otel-collector",
			PrometheusURL: "http://localhost:8888/metrics",
			HealthURL:     "http://localhost:13133/",
			MemoryLimitMB: 1024,
		},
		{
			Name:          "jaeger",
			DisplayName:   "Jaeger",
			Type:          ComponentTypeGo,
			Container:     "reactive-jaeger",
			PrometheusURL: "http://localhost:14269/metrics",
			HealthURL:     "http://localhost:16686/api/services",
			MemoryLimitMB: 96,
		},
		{
			Name:          "loki",
			DisplayName:   "Loki",
			Type:          ComponentTypeGo,
			Container:     "reactive-loki",
			PrometheusURL: "http://localhost:3100/metrics",
			HealthURL:     "http://localhost:3100/ready",
			MemoryLimitMB: 1024,
		},
		{
			Name:          "prometheus",
			DisplayName:   "Prometheus",
			Type:          ComponentTypeGo,
			Container:     "reactive-prometheus",
			PrometheusURL: "http://localhost:9090/metrics",
			HealthURL:     "http://localhost:9090/-/healthy",
			MemoryLimitMB: 768,
		},
		{
			Name:          "grafana",
			DisplayName:   "Grafana",
			Type:          ComponentTypeGo,
			Container:     "reactive-grafana",
			PrometheusURL: "",
			HealthURL:     "http://localhost:3001/api/health",
			MemoryLimitMB: 256,
		},
	}
}

// CollectAll collects diagnostics for all components
func (d *ComponentDiagnostics) CollectAll(ctx context.Context) ([]DiagnosticSnapshot, error) {
	var snapshots []DiagnosticSnapshot

	for _, comp := range PlatformComponents() {
		snapshot, err := d.CollectComponent(ctx, comp)
		if err != nil {
			// Still include partial data
			snapshot = DiagnosticSnapshot{
				Component:   comp.Name,
				TimestampMs: time.Now().UnixMilli(),
			}
		}
		snapshots = append(snapshots, snapshot)
	}

	return snapshots, nil
}

// CollectComponent collects diagnostics for a specific component
func (d *ComponentDiagnostics) CollectComponent(ctx context.Context, comp ComponentInfo) (DiagnosticSnapshot, error) {
	snapshot := DiagnosticSnapshot{
		Component:   comp.Name,
		TimestampMs: time.Now().UnixMilli(),
	}

	// Get container status and uptime
	status, uptime, exitCode, oomKilled := d.getContainerInfo(comp.Container)
	snapshot.UptimeMs = uptime

	// If container is not running, return minimal snapshot with crash indicators
	if status != "running" {
		snapshot.Errors.CrashIndicators.OOMRisk = oomKilled || exitCode == 137
		if oomKilled || exitCode == 137 {
			snapshot.Errors.CrashIndicators.OOMRiskPercent = 100
		}
		return snapshot, nil
	}

	// Collect based on component type
	switch comp.Type {
	case ComponentTypeJVM:
		d.collectJVMDiagnostics(ctx, comp, &snapshot)
	case ComponentTypeGo:
		d.collectGoDiagnostics(ctx, comp, &snapshot)
	}

	// Collect container-level memory stats
	d.collectContainerMemory(comp, &snapshot)

	// Health check
	if comp.HealthURL != "" {
		healthy, latency := d.checkHealth(comp.HealthURL)
		if !healthy {
			snapshot.Errors.TotalErrorCount++
		}
		_ = latency // Could add to latency metrics
	}

	// Analyze crash risk
	d.analyzeCrashRisk(&snapshot)

	return snapshot, nil
}

// collectJVMDiagnostics collects JVM-specific diagnostics via actuator/prometheus
func (d *ComponentDiagnostics) collectJVMDiagnostics(ctx context.Context, comp ComponentInfo, snapshot *DiagnosticSnapshot) {
	// Try actuator metrics first
	if comp.ActuatorURL != "" {
		d.collectActuatorMetrics(comp.ActuatorURL, snapshot)
	}

	// Also try prometheus format
	if comp.PrometheusURL != "" {
		d.collectPrometheusMetrics(comp.PrometheusURL, snapshot)
	}
}

// collectActuatorMetrics collects metrics from Spring Boot Actuator
func (d *ComponentDiagnostics) collectActuatorMetrics(baseURL string, snapshot *DiagnosticSnapshot) {
	// JVM Memory - Heap
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.memory.used", "area:heap"); val > 0 {
		snapshot.Memory.HeapUsedBytes = int64(val)
		snapshot.Saturation.HeapUsedBytes = int64(val)
	}
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.memory.max", "area:heap"); val > 0 {
		snapshot.Memory.HeapMaxBytes = int64(val)
		snapshot.Saturation.HeapMaxBytes = int64(val)
	}
	if snapshot.Memory.HeapMaxBytes > 0 {
		snapshot.Saturation.HeapUsedPercent = float64(snapshot.Memory.HeapUsedBytes) / float64(snapshot.Memory.HeapMaxBytes) * 100
	}

	// Non-heap memory
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.memory.used", "area:nonheap"); val > 0 {
		snapshot.Memory.NonHeapUsedBytes = int64(val)
	}

	// Direct buffers
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.buffer.memory.used", "id:direct"); val > 0 {
		snapshot.Memory.DirectBufferUsedBytes = int64(val)
	}

	// GC metrics
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.gc.pause", ""); val > 0 {
		snapshot.GC.MaxGCPauseMs = val * 1000 // Convert to ms
	}

	// Threads
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.threads.live", ""); val > 0 {
		snapshot.Saturation.ThreadPoolActive = int(val)
	}
	if val := d.getActuatorMetricValue(baseURL+"/metrics/jvm.threads.peak", ""); val > 0 {
		snapshot.Saturation.ThreadPoolMax = int(val)
	}

	// CPU
	if val := d.getActuatorMetricValue(baseURL+"/metrics/process.cpu.usage", ""); val > 0 {
		snapshot.Saturation.CPUPercent = val * 100
	}

	// Throughput (HTTP requests for Spring Boot)
	if val := d.getActuatorMetricValue(baseURL+"/metrics/http.server.requests", ""); val > 0 {
		snapshot.Throughput.EventsPerSecond = val
	}
}

// getActuatorMetricValue fetches a single metric value from actuator
func (d *ComponentDiagnostics) getActuatorMetricValue(url string, tag string) float64 {
	if tag != "" {
		url = fmt.Sprintf("%s?tag=%s", url, tag)
	}

	resp, err := d.client.Get(url)
	if err != nil {
		return 0
	}
	defer resp.Body.Close()

	var result struct {
		Measurements []struct {
			Value float64 `json:"value"`
		} `json:"measurements"`
	}

	body, _ := io.ReadAll(resp.Body)
	if json.Unmarshal(body, &result) == nil && len(result.Measurements) > 0 {
		return result.Measurements[0].Value
	}
	return 0
}

// collectPrometheusMetrics collects metrics from Prometheus-format endpoint
func (d *ComponentDiagnostics) collectPrometheusMetrics(url string, snapshot *DiagnosticSnapshot) {
	resp, err := d.client.Get(url)
	if err != nil {
		return
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	metrics := parsePrometheusMetrics(string(body))

	// JVM metrics (Flink TaskManager format)
	if val, ok := metrics["flink_taskmanager_Status_JVM_Memory_Heap_Used"]; ok {
		snapshot.Memory.HeapUsedBytes = int64(val)
		snapshot.Saturation.HeapUsedBytes = int64(val)
	}
	if val, ok := metrics["flink_taskmanager_Status_JVM_Memory_Heap_Max"]; ok {
		snapshot.Memory.HeapMaxBytes = int64(val)
		snapshot.Saturation.HeapMaxBytes = int64(val)
	}
	if val, ok := metrics["flink_taskmanager_Status_JVM_Memory_NonHeap_Used"]; ok {
		snapshot.Memory.NonHeapUsedBytes = int64(val)
	}
	if val, ok := metrics["flink_taskmanager_Status_JVM_Memory_Direct_Used"]; ok {
		snapshot.Memory.DirectBufferUsedBytes = int64(val)
	}

	// GC metrics
	if val, ok := metrics["flink_taskmanager_Status_JVM_GarbageCollector_G1_Young_Generation_Count"]; ok {
		snapshot.GC.YoungGCCount = int64(val)
	}
	if val, ok := metrics["flink_taskmanager_Status_JVM_GarbageCollector_G1_Young_Generation_Time"]; ok {
		snapshot.GC.YoungGCTimeMs = int64(val)
	}
	if val, ok := metrics["flink_taskmanager_Status_JVM_GarbageCollector_G1_Old_Generation_Count"]; ok {
		snapshot.GC.OldGCCount = int64(val)
	}
	if val, ok := metrics["flink_taskmanager_Status_JVM_GarbageCollector_G1_Old_Generation_Time"]; ok {
		snapshot.GC.OldGCTimeMs = int64(val)
	}

	// Calculate heap percent
	if snapshot.Saturation.HeapMaxBytes > 0 {
		snapshot.Saturation.HeapUsedPercent = float64(snapshot.Saturation.HeapUsedBytes) / float64(snapshot.Saturation.HeapMaxBytes) * 100
	}

	// OTEL Collector specific metrics
	if val, ok := metrics["otelcol_process_memory_rss"]; ok {
		snapshot.Memory.HeapUsedBytes = int64(val)
	}
	if val, ok := metrics["otelcol_exporter_sent_spans"]; ok {
		snapshot.Throughput.EventsPerSecond = val // This is total, not rate
	}
	if val, ok := metrics["otelcol_receiver_accepted_spans"]; ok {
		snapshot.Throughput.EventsPerSecond = val
	}
	if val, ok := metrics["otelcol_processor_dropped_spans"]; ok {
		snapshot.Throughput.DroppedCount = int64(val)
	}

	// Loki specific metrics
	if val, ok := metrics["loki_ingester_memory_chunks"]; ok {
		snapshot.Throughput.QueueDepth = int64(val)
	}
	if val, ok := metrics["loki_distributor_bytes_received_total"]; ok {
		snapshot.Throughput.BytesPerSecond = int64(val)
	}

	// Jaeger specific metrics
	if val, ok := metrics["jaeger_agent_reporter_spans_submitted_total"]; ok {
		snapshot.Throughput.EventsPerSecond = val
	}
}

// parsePrometheusMetrics parses Prometheus text format into a map
func parsePrometheusMetrics(body string) map[string]float64 {
	metrics := make(map[string]float64)
	lines := strings.Split(body, "\n")

	// Regex to match metric lines (with or without labels)
	metricRe := regexp.MustCompile(`^([a-zA-Z_][a-zA-Z0-9_]*)(\{[^}]*\})?\s+([0-9.eE+-]+)`)

	for _, line := range lines {
		if strings.HasPrefix(line, "#") || line == "" {
			continue
		}

		matches := metricRe.FindStringSubmatch(line)
		if len(matches) >= 4 {
			name := matches[1]
			valueStr := matches[3]
			if val, err := strconv.ParseFloat(valueStr, 64); err == nil {
				// Use the first occurrence if multiple
				if _, exists := metrics[name]; !exists {
					metrics[name] = val
				}
			}
		}
	}
	return metrics
}

// collectGoDiagnostics collects Go runtime diagnostics
func (d *ComponentDiagnostics) collectGoDiagnostics(ctx context.Context, comp ComponentInfo, snapshot *DiagnosticSnapshot) {
	if comp.PrometheusURL != "" {
		d.collectPrometheusMetrics(comp.PrometheusURL, snapshot)
	}

	// Go runtime metrics from prometheus endpoint
	if comp.PrometheusURL != "" {
		resp, err := d.client.Get(comp.PrometheusURL)
		if err != nil {
			return
		}
		defer resp.Body.Close()

		body, _ := io.ReadAll(resp.Body)
		metrics := parsePrometheusMetrics(string(body))

		// Standard Go runtime metrics
		if val, ok := metrics["go_memstats_heap_alloc_bytes"]; ok {
			snapshot.Memory.HeapUsedBytes = int64(val)
			snapshot.Saturation.HeapUsedBytes = int64(val)
		}
		if val, ok := metrics["go_memstats_heap_sys_bytes"]; ok {
			snapshot.Memory.HeapMaxBytes = int64(val)
			snapshot.Saturation.HeapMaxBytes = int64(val)
		}
		if val, ok := metrics["go_memstats_sys_bytes"]; ok {
			// Total memory obtained from system
			snapshot.Memory.HeapCommittedBytes = int64(val)
		}
		if val, ok := metrics["go_goroutines"]; ok {
			snapshot.Saturation.ThreadPoolActive = int(val)
		}
		if val, ok := metrics["go_gc_duration_seconds_sum"]; ok {
			snapshot.GC.YoungGCTimeMs = int64(val * 1000)
		}

		// Calculate heap percent
		if snapshot.Saturation.HeapMaxBytes > 0 {
			snapshot.Saturation.HeapUsedPercent = float64(snapshot.Saturation.HeapUsedBytes) / float64(snapshot.Saturation.HeapMaxBytes) * 100
		}
	}
}

// collectContainerMemory collects container-level memory from docker
func (d *ComponentDiagnostics) collectContainerMemory(comp ComponentInfo, snapshot *DiagnosticSnapshot) {
	// Get current memory from docker stats
	cmd := exec.Command("docker", "stats", "--no-stream", "--format", "{{.MemUsage}}|{{.MemPerc}}|{{.CPUPerc}}", comp.Container)
	output, err := cmd.Output()
	if err != nil {
		return
	}

	parts := strings.Split(strings.TrimSpace(string(output)), "|")
	if len(parts) < 3 {
		return
	}

	// Parse memory usage
	memParts := strings.Split(parts[0], " / ")
	if len(memParts) >= 1 {
		usedBytes := parseMemoryString(memParts[0])
		snapshot.Memory.NativeMemoryUsedBytes = usedBytes
	}

	// Parse memory percent
	memPctStr := strings.TrimSuffix(parts[1], "%")
	if pct, err := strconv.ParseFloat(memPctStr, 64); err == nil {
		// If we don't have heap data, use container memory
		if snapshot.Saturation.HeapUsedPercent == 0 {
			snapshot.Saturation.HeapUsedPercent = pct
		}
	}

	// Parse CPU percent
	cpuStr := strings.TrimSuffix(parts[2], "%")
	if pct, err := strconv.ParseFloat(cpuStr, 64); err == nil {
		snapshot.Saturation.CPUPercent = pct
	}

	// Get peak memory (high watermark)
	peakBytes := d.getContainerPeakMemory(comp.Container)
	if peakBytes > 0 {
		// Store peak in a memory pool for tracking
		limitBytes := comp.MemoryLimitMB * 1024 * 1024
		peakPct := float64(peakBytes) / float64(limitBytes) * 100
		snapshot.Memory.MemoryPools = append(snapshot.Memory.MemoryPools, MemoryPool{
			Name:        "container_peak",
			UsedBytes:   peakBytes,
			MaxBytes:    limitBytes,
			UsedPercent: peakPct,
		})
	}
}

// getContainerPeakMemory returns the high watermark memory usage
func (d *ComponentDiagnostics) getContainerPeakMemory(containerName string) int64 {
	// Try cgroup v1
	cmd := exec.Command("docker", "exec", containerName, "cat", "/sys/fs/cgroup/memory/memory.max_usage_in_bytes")
	output, err := cmd.Output()
	if err == nil {
		if peak, err := strconv.ParseInt(strings.TrimSpace(string(output)), 10, 64); err == nil {
			return peak
		}
	}

	// Try cgroup v2
	cmd = exec.Command("docker", "exec", containerName, "cat", "/sys/fs/cgroup/memory.peak")
	output, err = cmd.Output()
	if err == nil {
		if peak, err := strconv.ParseInt(strings.TrimSpace(string(output)), 10, 64); err == nil {
			return peak
		}
	}

	return 0
}

// getContainerInfo returns container status, uptime, exit code, and OOM flag
func (d *ComponentDiagnostics) getContainerInfo(containerName string) (status string, uptimeMs int64, exitCode int, oomKilled bool) {
	cmd := exec.Command("docker", "inspect", containerName,
		"--format", "{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.StartedAt}}")
	output, err := cmd.Output()
	if err != nil {
		return "unknown", 0, 0, false
	}

	parts := strings.Split(strings.TrimSpace(string(output)), "|")
	if len(parts) < 4 {
		return "unknown", 0, 0, false
	}

	status = parts[0]
	exitCode, _ = strconv.Atoi(parts[1])
	oomKilled = parts[2] == "true"

	// Calculate uptime
	if startedAt, err := time.Parse(time.RFC3339Nano, parts[3]); err == nil {
		uptimeMs = time.Since(startedAt).Milliseconds()
	}

	return
}

// checkHealth checks a health endpoint
func (d *ComponentDiagnostics) checkHealth(url string) (healthy bool, latencyMs int64) {
	start := time.Now()
	resp, err := d.client.Get(url)
	latencyMs = time.Since(start).Milliseconds()

	if err != nil {
		return false, latencyMs
	}
	defer resp.Body.Close()

	return resp.StatusCode < 400, latencyMs
}

// analyzeCrashRisk analyzes the snapshot for crash indicators
func (d *ComponentDiagnostics) analyzeCrashRisk(snapshot *DiagnosticSnapshot) {
	// OOM Risk based on heap usage
	if snapshot.Saturation.HeapUsedPercent > 90 {
		snapshot.Errors.CrashIndicators.OOMRisk = true
		snapshot.Errors.CrashIndicators.OOMRiskPercent = snapshot.Saturation.HeapUsedPercent
	} else if snapshot.Saturation.HeapUsedPercent > 80 {
		snapshot.Errors.CrashIndicators.OOMRiskPercent = snapshot.Saturation.HeapUsedPercent
	}

	// Check peak memory from memory pools
	for _, pool := range snapshot.Memory.MemoryPools {
		if pool.Name == "container_peak" && pool.UsedPercent > 95 {
			snapshot.Errors.CrashIndicators.OOMRisk = true
			if pool.UsedPercent > snapshot.Errors.CrashIndicators.OOMRiskPercent {
				snapshot.Errors.CrashIndicators.OOMRiskPercent = pool.UsedPercent
			}
		}
	}

	// GC Thrashing detection
	if snapshot.GC.OldGCCount > 0 && snapshot.UptimeMs > 0 {
		gcFreqPerMin := float64(snapshot.GC.OldGCCount) / (float64(snapshot.UptimeMs) / 60000)
		snapshot.GC.GCFrequencyPerMin = gcFreqPerMin
		if gcFreqPerMin > 10 { // More than 10 full GCs per minute
			snapshot.Errors.CrashIndicators.GCThrashing = true
		}
	}

	// Memory leak suspected if heap is high and growing
	if snapshot.Saturation.HeapUsedPercent > 85 && snapshot.GC.OldGCCount > 5 {
		snapshot.Errors.CrashIndicators.MemoryLeakSuspected = true
	}

	// Thread exhaustion
	if snapshot.Saturation.ThreadPoolMax > 0 {
		threadUsage := float64(snapshot.Saturation.ThreadPoolActive) / float64(snapshot.Saturation.ThreadPoolMax) * 100
		if threadUsage > 90 {
			snapshot.Errors.CrashIndicators.ThreadExhaustionRisk = true
		}
	}
}
