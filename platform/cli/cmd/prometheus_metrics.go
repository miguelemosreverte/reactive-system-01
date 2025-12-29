package cmd

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// PrometheusMetrics contains detailed metrics from all services
type PrometheusMetrics struct {
	CollectedAt string                    `json:"collectedAt"`
	Services    map[string]*ServiceMetrics `json:"services"`
}

// ServiceMetrics contains metrics for a single service
type ServiceMetrics struct {
	JVM          *JVMMetrics          `json:"jvm,omitempty"`
	Kafka        *KafkaMetrics        `json:"kafka,omitempty"`
	HTTP         *HTTPMetrics         `json:"http,omitempty"`
	GC           *GCMetrics           `json:"gc,omitempty"`
	Threads      *ThreadMetrics       `json:"threads,omitempty"`
	Flink        *FlinkMetrics        `json:"flink,omitempty"`
	Network      *NetworkMetrics      `json:"network,omitempty"`
	CustomMetrics map[string]float64  `json:"customMetrics,omitempty"`
}

// JVMMetrics contains JVM memory breakdown
type JVMMetrics struct {
	HeapUsedBytes       float64            `json:"heapUsedBytes"`
	HeapMaxBytes        float64            `json:"heapMaxBytes"`
	HeapCommittedBytes  float64            `json:"heapCommittedBytes"`
	HeapUsagePercent    float64            `json:"heapUsagePercent"`
	NonHeapUsedBytes    float64            `json:"nonHeapUsedBytes"`
	NonHeapMaxBytes     float64            `json:"nonHeapMaxBytes"`
	MetaspaceUsedBytes  float64            `json:"metaspaceUsedBytes"`
	MetaspaceMaxBytes   float64            `json:"metaspaceMaxBytes"`
	DirectBufferBytes   float64            `json:"directBufferBytes"`
	MemoryPoolBreakdown map[string]float64 `json:"memoryPoolBreakdown"`
}

// KafkaMetrics contains Kafka consumer/producer metrics
type KafkaMetrics struct {
	ConsumerLag         map[string]float64 `json:"consumerLag"`
	TotalLag            float64            `json:"totalLag"`
	BytesConsumedRate   float64            `json:"bytesConsumedRate"`
	BytesProducedRate   float64            `json:"bytesProducedRate"`
	RecordsConsumedRate float64            `json:"recordsConsumedRate"`
	RecordsProducedRate float64            `json:"recordsProducedRate"`
	FetchLatencyAvg     float64            `json:"fetchLatencyAvg"`
	ProduceLatencyAvg   float64            `json:"produceLatencyAvg"`
	Partitions          []PartitionMetrics `json:"partitions,omitempty"`
}

// PartitionMetrics contains per-partition metrics
type PartitionMetrics struct {
	Topic     string  `json:"topic"`
	Partition int     `json:"partition"`
	Lag       float64 `json:"lag"`
	Offset    float64 `json:"offset"`
}

// HTTPMetrics contains HTTP server metrics
type HTTPMetrics struct {
	RequestsTotal    float64                   `json:"requestsTotal"`
	RequestsRate     float64                   `json:"requestsRate"`
	ErrorRate        float64                   `json:"errorRate"`
	AvgResponseTime  float64                   `json:"avgResponseTime"`
	P99ResponseTime  float64                   `json:"p99ResponseTime"`
	EndpointBreakdown []EndpointMetrics        `json:"endpointBreakdown,omitempty"`
}

// EndpointMetrics contains per-endpoint metrics
type EndpointMetrics struct {
	URI           string  `json:"uri"`
	Method        string  `json:"method"`
	RequestCount  float64 `json:"requestCount"`
	TotalTimeMs   float64 `json:"totalTimeMs"`
	AvgTimeMs     float64 `json:"avgTimeMs"`
	ErrorCount    float64 `json:"errorCount"`
	SuccessRate   float64 `json:"successRate"`
}

// GCMetrics contains garbage collection metrics
type GCMetrics struct {
	TotalPauseSeconds  float64            `json:"totalPauseSeconds"`
	PauseCount         float64            `json:"pauseCount"`
	AvgPauseMs         float64            `json:"avgPauseMs"`
	MaxPauseMs         float64            `json:"maxPauseMs"`
	GCOverheadPercent  float64            `json:"gcOverheadPercent"`
	CollectorBreakdown map[string]GCStats `json:"collectorBreakdown"`
}

// GCStats contains stats for a single GC collector
type GCStats struct {
	Count         float64 `json:"count"`
	TotalTimeMs   float64 `json:"totalTimeMs"`
	AvgPauseMs    float64 `json:"avgPauseMs"`
	MaxPauseMs    float64 `json:"maxPauseMs"`
}

// ThreadMetrics contains thread pool metrics
type ThreadMetrics struct {
	LiveThreads     float64            `json:"liveThreads"`
	DaemonThreads   float64            `json:"daemonThreads"`
	PeakThreads     float64            `json:"peakThreads"`
	ThreadsStarted  float64            `json:"threadsStarted"`
	StateBreakdown  map[string]float64 `json:"stateBreakdown"`
}

// FlinkMetrics contains Flink-specific metrics
type FlinkMetrics struct {
	CPULoad                float64 `json:"cpuLoad"`
	HeapUsed               float64 `json:"heapUsed"`
	HeapMax                float64 `json:"heapMax"`
	ManagedMemoryUsed      float64 `json:"managedMemoryUsed"`
	ManagedMemoryTotal     float64 `json:"managedMemoryTotal"`
	NetworkBuffersUsed     float64 `json:"networkBuffersUsed"`
	NetworkBuffersTotal    float64 `json:"networkBuffersTotal"`
	NumRecordsIn           float64 `json:"numRecordsIn"`
	NumRecordsOut          float64 `json:"numRecordsOut"`
	NumBytesIn             float64 `json:"numBytesIn"`
	NumBytesOut            float64 `json:"numBytesOut"`
	BackPressuredTimeMsPerSecond float64 `json:"backPressuredTimeMsPerSecond"`
	BusyTimeMsPerSecond    float64 `json:"busyTimeMsPerSecond"`
	IdleTimeMsPerSecond    float64 `json:"idleTimeMsPerSecond"`
	CheckpointsCompleted   float64 `json:"checkpointsCompleted"`
	CheckpointsFailed      float64 `json:"checkpointsFailed"`
	LastCheckpointDurationMs float64 `json:"lastCheckpointDurationMs"`
}

// NetworkMetrics contains network I/O metrics
type NetworkMetrics struct {
	BytesReceivedTotal float64 `json:"bytesReceivedTotal"`
	BytesSentTotal     float64 `json:"bytesSentTotal"`
	ConnectionsActive  float64 `json:"connectionsActive"`
}

// ServiceEndpoint maps service names to their Prometheus endpoints
var ServiceEndpoints = map[string]string{
	"gateway": "http://localhost:8080/actuator/prometheus",
	"drools":  "http://localhost:8180/actuator/prometheus",
}

// CollectPrometheusMetrics collects metrics from all available Prometheus endpoints
func CollectPrometheusMetrics() *PrometheusMetrics {
	metrics := &PrometheusMetrics{
		CollectedAt: time.Now().Format(time.RFC3339),
		Services:    make(map[string]*ServiceMetrics),
	}

	client := &http.Client{Timeout: 5 * time.Second}

	// Collect from Spring Boot actuator endpoints
	for service, endpoint := range ServiceEndpoints {
		svcMetrics := collectServiceMetrics(client, endpoint)
		if svcMetrics != nil {
			metrics.Services[service] = svcMetrics
		}
	}

	// Collect from Prometheus server for Flink metrics
	flinkMetrics := collectFlinkMetricsFromPrometheus(client)
	if flinkMetrics != nil {
		if metrics.Services["flink-taskmanager"] == nil {
			metrics.Services["flink-taskmanager"] = &ServiceMetrics{}
		}
		metrics.Services["flink-taskmanager"].Flink = flinkMetrics
	}

	return metrics
}

func collectServiceMetrics(client *http.Client, endpoint string) *ServiceMetrics {
	resp, err := client.Get(endpoint)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return nil
	}

	// Read entire body
	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil
	}
	body := string(bodyBytes)

	metrics := &ServiceMetrics{}

	// Parse JVM metrics
	metrics.JVM = parseJVMMetrics(body)

	// Parse Kafka metrics
	metrics.Kafka = parseKafkaMetrics(body)

	// Parse HTTP metrics
	metrics.HTTP = parseHTTPMetrics(body)

	// Parse GC metrics
	metrics.GC = parseGCMetrics(body)

	// Parse Thread metrics
	metrics.Threads = parseThreadMetrics(body)

	return metrics
}

func parseJVMMetrics(body string) *JVMMetrics {
	jvm := &JVMMetrics{
		MemoryPoolBreakdown: make(map[string]float64),
	}

	// Parse heap used from individual memory pools
	heapPools := []string{"G1 Eden Space", "G1 Old Gen", "G1 Survivor Space"}
	for _, pool := range heapPools {
		pattern := fmt.Sprintf(`jvm_memory_used_bytes\{[^}]*id="%s"[^}]*\}\s+([\d.eE+-]+)`, pool)
		re := regexp.MustCompile(pattern)
		if match := re.FindStringSubmatch(body); len(match) == 2 {
			if val, err := strconv.ParseFloat(match[1], 64); err == nil && val > 0 {
				jvm.HeapUsedBytes += val
			}
		}
	}

	// Parse heap max - use G1 Old Gen max as it's typically the largest
	jvm.HeapMaxBytes = parseFirstPositiveMetric(body, []string{
		`jvm_memory_max_bytes\{[^}]*id="G1 Old Gen"[^}]*\}`,
	})

	// If no max, try heap committed as approximation
	if jvm.HeapMaxBytes == 0 {
		jvm.HeapMaxBytes = parseFirstPositiveMetric(body, []string{
			`jvm_memory_committed_bytes\{[^}]*area="heap"[^}]*\}`,
		})
		// Sum all heap committed
		for _, pool := range heapPools {
			pattern := fmt.Sprintf(`jvm_memory_committed_bytes\{[^}]*id="%s"[^}]*\}\s+([\d.eE+-]+)`, pool)
			re := regexp.MustCompile(pattern)
			if match := re.FindStringSubmatch(body); len(match) == 2 {
				if val, err := strconv.ParseFloat(match[1], 64); err == nil && val > 0 {
					jvm.HeapCommittedBytes += val
				}
			}
		}
		if jvm.HeapMaxBytes == 0 {
			jvm.HeapMaxBytes = jvm.HeapCommittedBytes
		}
	}

	// Calculate heap usage percent
	if jvm.HeapMaxBytes > 0 {
		jvm.HeapUsagePercent = (jvm.HeapUsedBytes / jvm.HeapMaxBytes) * 100
	}

	// Parse non-heap used from individual pools
	nonHeapPools := []string{"Metaspace", "Compressed Class Space", "CodeHeap 'profiled nmethods'", "CodeHeap 'non-profiled nmethods'", "CodeHeap 'non-nmethods'"}
	for _, pool := range nonHeapPools {
		pattern := fmt.Sprintf(`jvm_memory_used_bytes\{[^}]*id="%s"[^}]*\}\s+([\d.eE+-]+)`, pool)
		re := regexp.MustCompile(pattern)
		if match := re.FindStringSubmatch(body); len(match) == 2 {
			if val, err := strconv.ParseFloat(match[1], 64); err == nil && val > 0 {
				jvm.NonHeapUsedBytes += val
				jvm.MemoryPoolBreakdown[pool] = val
			}
		}
	}

	// Parse metaspace specifically
	jvm.MetaspaceUsedBytes = parseFirstPositiveMetric(body, []string{
		`jvm_memory_used_bytes\{[^}]*id="Metaspace"[^}]*\}`,
	})

	// Parse direct buffers
	jvm.DirectBufferBytes = parseFirstPositiveMetric(body, []string{
		`jvm_buffer_memory_used_bytes\{[^}]*id="direct"[^}]*\}`,
	})

	return jvm
}

func parseFirstPositiveMetric(body string, patterns []string) float64 {
	for _, pattern := range patterns {
		re := regexp.MustCompile(pattern + `\s+([\d.eE+-]+)`)
		matches := re.FindAllStringSubmatch(body, -1)
		for _, match := range matches {
			if len(match) == 2 {
				if val, err := strconv.ParseFloat(match[1], 64); err == nil && val > 0 {
					return val
				}
			}
		}
	}
	return 0
}

func parseKafkaMetrics(body string) *KafkaMetrics {
	kafka := &KafkaMetrics{
		ConsumerLag: make(map[string]float64),
	}

	// Parse consumer lag per partition
	lagPattern := regexp.MustCompile(`kafka_consumer_fetch_manager_records_lag\{[^}]*topic="([^"]+)"[^}]*partition="(\d+)"[^}]*\}\s+([\d.eE+-]+)`)
	matches := lagPattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 4 {
			key := fmt.Sprintf("%s-%s", m[1], m[2])
			if val, err := strconv.ParseFloat(m[3], 64); err == nil && val >= 0 {
				kafka.ConsumerLag[key] = val
				kafka.TotalLag += val

				partition, _ := strconv.Atoi(m[2])
				kafka.Partitions = append(kafka.Partitions, PartitionMetrics{
					Topic:     m[1],
					Partition: partition,
					Lag:       val,
				})
			}
		}
	}

	// Parse byte rates
	kafka.BytesConsumedRate = parseMetricValue(body, `kafka_consumer_incoming_byte_rate\{[^}]*\}`)
	kafka.RecordsConsumedRate = parseMetricValue(body, `kafka_consumer_fetch_manager_records_consumed_rate\{[^}]*\}`)
	kafka.FetchLatencyAvg = parseMetricValue(body, `kafka_consumer_fetch_manager_fetch_latency_avg\{[^}]*\}`)

	// Sort partitions
	sort.Slice(kafka.Partitions, func(i, j int) bool {
		if kafka.Partitions[i].Topic == kafka.Partitions[j].Topic {
			return kafka.Partitions[i].Partition < kafka.Partitions[j].Partition
		}
		return kafka.Partitions[i].Topic < kafka.Partitions[j].Topic
	})

	return kafka
}

func parseHTTPMetrics(body string) *HTTPMetrics {
	http := &HTTPMetrics{}

	// Parse HTTP request counts and times
	endpointPattern := regexp.MustCompile(`http_server_requests_seconds_count\{[^}]*method="([^"]+)"[^}]*uri="([^"]+)"[^}]*status="(\d+)"[^}]*\}\s+([\d.eE+-]+)`)
	sumPattern := regexp.MustCompile(`http_server_requests_seconds_sum\{[^}]*method="([^"]+)"[^}]*uri="([^"]+)"[^}]*status="(\d+)"[^}]*\}\s+([\d.eE+-]+)`)

	endpointMap := make(map[string]*EndpointMetrics)

	// Parse counts
	matches := endpointPattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 5 {
			key := m[1] + " " + m[2]
			if endpointMap[key] == nil {
				endpointMap[key] = &EndpointMetrics{
					Method: m[1],
					URI:    m[2],
				}
			}
			if count, err := strconv.ParseFloat(m[4], 64); err == nil {
				endpointMap[key].RequestCount += count
				http.RequestsTotal += count

				status, _ := strconv.Atoi(m[3])
				if status >= 400 {
					endpointMap[key].ErrorCount += count
				}
			}
		}
	}

	// Parse sums
	matches = sumPattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 5 {
			key := m[1] + " " + m[2]
			if endpointMap[key] != nil {
				if sum, err := strconv.ParseFloat(m[4], 64); err == nil {
					endpointMap[key].TotalTimeMs += sum * 1000 // convert to ms
				}
			}
		}
	}

	// Calculate averages and build list
	for _, ep := range endpointMap {
		if ep.RequestCount > 0 {
			ep.AvgTimeMs = ep.TotalTimeMs / ep.RequestCount
			ep.SuccessRate = ((ep.RequestCount - ep.ErrorCount) / ep.RequestCount) * 100
		}
		http.EndpointBreakdown = append(http.EndpointBreakdown, *ep)
	}

	// Sort by request count
	sort.Slice(http.EndpointBreakdown, func(i, j int) bool {
		return http.EndpointBreakdown[i].RequestCount > http.EndpointBreakdown[j].RequestCount
	})

	// Calculate overall error rate
	if http.RequestsTotal > 0 {
		var totalErrors float64
		for _, ep := range http.EndpointBreakdown {
			totalErrors += ep.ErrorCount
		}
		http.ErrorRate = (totalErrors / http.RequestsTotal) * 100
	}

	return http
}

func parseGCMetrics(body string) *GCMetrics {
	gc := &GCMetrics{
		CollectorBreakdown: make(map[string]GCStats),
	}

	// Parse GC pause seconds
	gcPattern := regexp.MustCompile(`jvm_gc_pause_seconds_count\{[^}]*gc="([^"]+)"[^}]*\}\s+([\d.eE+-]+)`)
	sumPattern := regexp.MustCompile(`jvm_gc_pause_seconds_sum\{[^}]*gc="([^"]+)"[^}]*\}\s+([\d.eE+-]+)`)
	maxPattern := regexp.MustCompile(`jvm_gc_pause_seconds_max\{[^}]*gc="([^"]+)"[^}]*\}\s+([\d.eE+-]+)`)

	// Parse counts
	matches := gcPattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 3 {
			if count, err := strconv.ParseFloat(m[2], 64); err == nil {
				stats := gc.CollectorBreakdown[m[1]]
				stats.Count += count
				gc.CollectorBreakdown[m[1]] = stats
				gc.PauseCount += count
			}
		}
	}

	// Parse sums
	matches = sumPattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 3 {
			if sum, err := strconv.ParseFloat(m[2], 64); err == nil {
				stats := gc.CollectorBreakdown[m[1]]
				stats.TotalTimeMs += sum * 1000
				gc.CollectorBreakdown[m[1]] = stats
				gc.TotalPauseSeconds += sum
			}
		}
	}

	// Parse max
	matches = maxPattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 3 {
			if max, err := strconv.ParseFloat(m[2], 64); err == nil {
				stats := gc.CollectorBreakdown[m[1]]
				maxMs := max * 1000
				if maxMs > stats.MaxPauseMs {
					stats.MaxPauseMs = maxMs
				}
				gc.CollectorBreakdown[m[1]] = stats
				if maxMs > gc.MaxPauseMs {
					gc.MaxPauseMs = maxMs
				}
			}
		}
	}

	// Calculate averages
	for name, stats := range gc.CollectorBreakdown {
		if stats.Count > 0 {
			stats.AvgPauseMs = stats.TotalTimeMs / stats.Count
		}
		gc.CollectorBreakdown[name] = stats
	}

	if gc.PauseCount > 0 {
		gc.AvgPauseMs = (gc.TotalPauseSeconds * 1000) / gc.PauseCount
	}

	// Parse GC overhead
	gc.GCOverheadPercent = parseMetricValue(body, `jvm_gc_overhead_percent`)

	return gc
}

func parseThreadMetrics(body string) *ThreadMetrics {
	threads := &ThreadMetrics{
		StateBreakdown: make(map[string]float64),
	}

	threads.LiveThreads = parseMetricValue(body, `jvm_threads_live_threads`)
	threads.DaemonThreads = parseMetricValue(body, `jvm_threads_daemon_threads`)
	threads.PeakThreads = parseMetricValue(body, `jvm_threads_peak_threads`)
	threads.ThreadsStarted = parseMetricValue(body, `jvm_threads_started_threads_total`)

	// Parse thread states
	statePattern := regexp.MustCompile(`jvm_threads_states_threads\{[^}]*state="([^"]+)"[^}]*\}\s+([\d.eE+-]+)`)
	matches := statePattern.FindAllStringSubmatch(body, -1)
	for _, m := range matches {
		if len(m) == 3 {
			if val, err := strconv.ParseFloat(m[2], 64); err == nil {
				threads.StateBreakdown[m[1]] = val
			}
		}
	}

	return threads
}

func collectFlinkMetricsFromPrometheus(client *http.Client) *FlinkMetrics {
	resp, err := client.Get("http://localhost:9090/api/v1/query?query={__name__=~\"flink_.*\"}")
	if err != nil {
		return nil
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return nil
	}

	var result struct {
		Status string `json:"status"`
		Data   struct {
			Result []struct {
				Metric map[string]string `json:"metric"`
				Value  []interface{}     `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil
	}

	flink := &FlinkMetrics{}

	for _, r := range result.Data.Result {
		name := r.Metric["__name__"]
		if len(r.Value) < 2 {
			continue
		}
		valStr, ok := r.Value[1].(string)
		if !ok {
			continue
		}
		val, err := strconv.ParseFloat(valStr, 64)
		if err != nil {
			continue
		}

		switch {
		case strings.Contains(name, "JVM_CPU_Load"):
			flink.CPULoad = val * 100
		case strings.Contains(name, "JVM_Memory_Heap_Used"):
			flink.HeapUsed = val
		case strings.Contains(name, "JVM_Memory_Heap_Max"):
			flink.HeapMax = val
		case strings.Contains(name, "Managed_Used"):
			flink.ManagedMemoryUsed = val
		case strings.Contains(name, "Managed_Total"):
			flink.ManagedMemoryTotal = val
		case strings.Contains(name, "Network_AvailableMemorySegments"):
			flink.NetworkBuffersTotal = val
		case strings.Contains(name, "Network_TotalMemorySegments"):
			flink.NetworkBuffersUsed = val
		case strings.Contains(name, "numRecordsIn") && !strings.Contains(name, "Rate"):
			flink.NumRecordsIn += val
		case strings.Contains(name, "numRecordsOut") && !strings.Contains(name, "Rate"):
			flink.NumRecordsOut += val
		case strings.Contains(name, "numBytesIn") && !strings.Contains(name, "Rate"):
			flink.NumBytesIn += val
		case strings.Contains(name, "numBytesOut") && !strings.Contains(name, "Rate"):
			flink.NumBytesOut += val
		case strings.Contains(name, "backPressuredTimeMsPerSecond"):
			flink.BackPressuredTimeMsPerSecond = val
		case strings.Contains(name, "busyTimeMsPerSecond"):
			flink.BusyTimeMsPerSecond = val
		case strings.Contains(name, "idleTimeMsPerSecond"):
			flink.IdleTimeMsPerSecond = val
		}
	}

	return flink
}

// Helper functions
func parseMetricValue(body, pattern string) float64 {
	re := regexp.MustCompile(pattern + `\s+([\d.eE+-]+)`)
	match := re.FindStringSubmatch(body)
	if len(match) == 2 {
		val, err := strconv.ParseFloat(match[1], 64)
		if err == nil {
			return val
		}
	}
	return 0
}

func parseMetricSum(body, pattern string, sum bool) float64 {
	re := regexp.MustCompile(pattern + `\s+([\d.eE+-]+)`)
	matches := re.FindAllStringSubmatch(body, -1)
	var total float64
	for _, m := range matches {
		if len(m) == 2 {
			val, err := strconv.ParseFloat(m[1], 64)
			if err == nil && val > 0 {
				if sum {
					total += val
				} else if val > total {
					total = val
				}
			}
		}
	}
	return total
}

// formatBytesFloat formats bytes (float64) to human-readable string
func formatBytesFloat(bytes float64) string {
	const unit = 1024
	if bytes < unit {
		return fmt.Sprintf("%.0f B", bytes)
	}
	div, exp := float64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", bytes/div, "KMGTPE"[exp])
}

// PrintPrometheusMetrics prints metrics in a readable format
func PrintPrometheusMetrics(metrics *PrometheusMetrics) {
	fmt.Println()
	printHeader("DETAILED PROMETHEUS METRICS")
	fmt.Printf("  Collected at: %s\n", metrics.CollectedAt)
	fmt.Println()

	for service, svc := range metrics.Services {
		fmt.Printf("  %s%s%s\n", "\033[36m", strings.ToUpper(service), "\033[0m")
		fmt.Println("  " + strings.Repeat("─", 50))

		// JVM Metrics
		if svc.JVM != nil {
			fmt.Println("  JVM Memory:")
			fmt.Printf("    Heap: %s / %s (%.1f%%)\n",
				formatBytesFloat(svc.JVM.HeapUsedBytes),
				formatBytesFloat(svc.JVM.HeapMaxBytes),
				svc.JVM.HeapUsagePercent)
			fmt.Printf("    Non-Heap: %s / %s\n",
				formatBytesFloat(svc.JVM.NonHeapUsedBytes),
				formatBytesFloat(svc.JVM.NonHeapMaxBytes))
			fmt.Printf("    Metaspace: %s\n", formatBytesFloat(svc.JVM.MetaspaceUsedBytes))
			fmt.Printf("    Direct Buffers: %s\n", formatBytesFloat(svc.JVM.DirectBufferBytes))

			if len(svc.JVM.MemoryPoolBreakdown) > 0 {
				fmt.Println("    Memory Pools:")
				for pool, bytes := range svc.JVM.MemoryPoolBreakdown {
					fmt.Printf("      %s: %s\n", pool, formatBytesFloat(bytes))
				}
			}
		}

		// GC Metrics
		if svc.GC != nil && svc.GC.PauseCount > 0 {
			fmt.Println("  Garbage Collection:")
			fmt.Printf("    Total Pauses: %.0f (%.2fs total)\n", svc.GC.PauseCount, svc.GC.TotalPauseSeconds)
			fmt.Printf("    Avg Pause: %.2fms, Max: %.2fms\n", svc.GC.AvgPauseMs, svc.GC.MaxPauseMs)
			fmt.Printf("    GC Overhead: %.2f%%\n", svc.GC.GCOverheadPercent)

			for name, stats := range svc.GC.CollectorBreakdown {
				fmt.Printf("      %s: %.0f collections, avg %.2fms\n", name, stats.Count, stats.AvgPauseMs)
			}
		}

		// Thread Metrics
		if svc.Threads != nil {
			fmt.Println("  Threads:")
			fmt.Printf("    Live: %.0f (%.0f daemon), Peak: %.0f\n",
				svc.Threads.LiveThreads, svc.Threads.DaemonThreads, svc.Threads.PeakThreads)
			if len(svc.Threads.StateBreakdown) > 0 {
				states := []string{}
				for state, count := range svc.Threads.StateBreakdown {
					if count > 0 {
						states = append(states, fmt.Sprintf("%s:%.0f", state, count))
					}
				}
				fmt.Printf("    States: %s\n", strings.Join(states, ", "))
			}
		}

		// Kafka Metrics
		if svc.Kafka != nil && svc.Kafka.TotalLag >= 0 {
			fmt.Println("  Kafka:")
			fmt.Printf("    Total Consumer Lag: %.0f\n", svc.Kafka.TotalLag)
			fmt.Printf("    Bytes Consumed Rate: %.1f/s\n", svc.Kafka.BytesConsumedRate)
			fmt.Printf("    Records Consumed Rate: %.1f/s\n", svc.Kafka.RecordsConsumedRate)
			if len(svc.Kafka.Partitions) > 0 && svc.Kafka.TotalLag > 0 {
				fmt.Println("    Partition Lag:")
				for _, p := range svc.Kafka.Partitions {
					if p.Lag > 0 {
						fmt.Printf("      %s[%d]: %.0f\n", p.Topic, p.Partition, p.Lag)
					}
				}
			}
		}

		// HTTP Metrics
		if svc.HTTP != nil && svc.HTTP.RequestsTotal > 0 {
			fmt.Println("  HTTP:")
			fmt.Printf("    Total Requests: %.0f\n", svc.HTTP.RequestsTotal)
			fmt.Printf("    Error Rate: %.2f%%\n", svc.HTTP.ErrorRate)
			if len(svc.HTTP.EndpointBreakdown) > 0 {
				fmt.Println("    Top Endpoints:")
				for i, ep := range svc.HTTP.EndpointBreakdown {
					if i >= 5 {
						break
					}
					fmt.Printf("      %s %s: %.0f reqs, %.2fms avg\n",
						ep.Method, ep.URI, ep.RequestCount, ep.AvgTimeMs)
				}
			}
		}

		// Flink Metrics
		if svc.Flink != nil {
			fmt.Println("  Flink:")
			fmt.Printf("    CPU Load: %.1f%%\n", svc.Flink.CPULoad)
			fmt.Printf("    Heap: %s / %s\n",
				formatBytesFloat(svc.Flink.HeapUsed), formatBytesFloat(svc.Flink.HeapMax))
			fmt.Printf("    Managed Memory: %s / %s\n",
				formatBytesFloat(svc.Flink.ManagedMemoryUsed), formatBytesFloat(svc.Flink.ManagedMemoryTotal))
			fmt.Printf("    Records In/Out: %.0f / %.0f\n", svc.Flink.NumRecordsIn, svc.Flink.NumRecordsOut)
			if svc.Flink.BackPressuredTimeMsPerSecond > 0 {
				fmt.Printf("    Back Pressure: %.1fms/s\n", svc.Flink.BackPressuredTimeMsPerSecond)
			}
		}

		fmt.Println()
	}
}
