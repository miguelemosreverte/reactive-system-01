package cmd

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// ============================================================================
// COMPREHENSIVE DIAGNOSTIC COMMAND
// Provides all information needed for optimization decisions:
// - Memory per component (container + JVM breakdown)
// - Time breakdown per component (from Prometheus/traces)
// - GC analysis (parse GC logs)
// - Bottleneck identification with scores
// - OOM detection and history
// - Actionable recommendations
// ============================================================================

var diagnoseFullCmd = &cobra.Command{
	Use:   "diagnose",
	Short: "Comprehensive system diagnostics for optimization",
	Long: `Full diagnostic report including:
  - Component-level memory breakdown (container + JVM + buffers)
  - Time breakdown per component (latency, throughput)
  - GC analysis with pause times and allocation rates
  - Bottleneck identification with scores
  - OOM detection and crash history
  - Actionable recommendations

Examples:
  reactive diagnose              # Full diagnostic report
  reactive diagnose --json       # JSON output for automation
  reactive diagnose --component gateway  # Focus on one component`,
	Run: runDiagnoseFull,
}

var (
	diagnoseJSON      bool
	diagnoseComponent string
)

func init() {
	diagnoseFullCmd.Flags().BoolVar(&diagnoseJSON, "json", false, "Output as JSON")
	diagnoseFullCmd.Flags().StringVar(&diagnoseComponent, "component", "", "Focus on specific component")
	rootCmd.AddCommand(diagnoseFullCmd)
}

// ============================================================================
// DATA STRUCTURES
// ============================================================================

type DiagnosticReport struct {
	Timestamp        string                       `json:"timestamp"`
	OverallHealth    string                       `json:"overallHealth"`
	HealthScore      int                          `json:"healthScore"`
	Components       map[string]*ComponentReport  `json:"components"`
	Kafka            *KafkaReport                 `json:"kafka,omitempty"`
	Bottlenecks      []Bottleneck                 `json:"bottlenecks"`
	OOMEvents        []OOMEvent                   `json:"oomEvents"`
	Recommendations  []Recommendation             `json:"recommendations"`
	TraceAnalysis    *TraceAnalysis               `json:"traceAnalysis,omitempty"`
	LastBenchmark    *BenchmarkSummary            `json:"lastBenchmark,omitempty"`
	Optimizations    []Optimization               `json:"optimizations,omitempty"`
	Summary          string                       `json:"summary"`
}

// ============================================================================
// KAFKA DIAGNOSTICS
// ============================================================================

type KafkaReport struct {
	Brokers           []BrokerInfo           `json:"brokers"`
	Topics            []TopicInfo            `json:"topics"`
	ConsumerGroups    []ConsumerGroupInfo    `json:"consumerGroups"`
	PartitionBalance  *PartitionBalance      `json:"partitionBalance,omitempty"`
	TotalMessages     int64                  `json:"totalMessages"`
	MessagesPerSecond float64                `json:"messagesPerSecond"`
	BytesInPerSecond  float64                `json:"bytesInPerSecond"`
	BytesOutPerSecond float64                `json:"bytesOutPerSecond"`
	Issues            []string               `json:"issues"`
}

type BrokerInfo struct {
	ID          int    `json:"id"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	IsController bool  `json:"isController"`
}

type TopicInfo struct {
	Name            string          `json:"name"`
	Partitions      int             `json:"partitions"`
	ReplicationFactor int           `json:"replicationFactor"`
	MessageCount    int64           `json:"messageCount"`
	PartitionStats  []PartitionStat `json:"partitionStats"`
}

type PartitionStat struct {
	Partition    int   `json:"partition"`
	Leader       int   `json:"leader"`
	Replicas     []int `json:"replicas"`
	ISR          []int `json:"isr"`
	BeginOffset  int64 `json:"beginOffset"`
	EndOffset    int64 `json:"endOffset"`
	MessageCount int64 `json:"messageCount"`
}

type ConsumerGroupInfo struct {
	GroupID      string               `json:"groupId"`
	State        string               `json:"state"`
	Members      int                  `json:"members"`
	TotalLag     int64                `json:"totalLag"`
	PartitionLag []PartitionLagInfo   `json:"partitionLag"`
}

type PartitionLagInfo struct {
	Topic        string `json:"topic"`
	Partition    int    `json:"partition"`
	CurrentOffset int64 `json:"currentOffset"`
	LogEndOffset int64  `json:"logEndOffset"`
	Lag          int64  `json:"lag"`
	Consumer     string `json:"consumer"`
}

type PartitionBalance struct {
	TotalPartitions   int     `json:"totalPartitions"`
	BalanceScore      float64 `json:"balanceScore"` // 1.0 = perfect, 0.0 = all on one
	MaxMessagesOnPart int64   `json:"maxMessagesOnPartition"`
	MinMessagesOnPart int64   `json:"minMessagesOnPartition"`
	StdDeviation      float64 `json:"stdDeviation"`
	Skewed            bool    `json:"skewed"`
}

type BenchmarkSummary struct {
	Timestamp         string  `json:"timestamp"`
	TotalOps          int64   `json:"totalOps"`
	SuccessRate       float64 `json:"successRate"`
	AvgThroughput     float64 `json:"avgThroughput"`
	PeakThroughput    float64 `json:"peakThroughput"`
	AvgLatencyMs      float64 `json:"avgLatencyMs"`
	P99LatencyMs      float64 `json:"p99LatencyMs"`
	AvgCPU            float64 `json:"avgCpu"`
	PeakMemory        float64 `json:"peakMemory"`
	ThroughputStability float64 `json:"throughputStability"`
}

type Optimization struct {
	Category    string `json:"category"`
	Component   string `json:"component"`
	Current     string `json:"current"`
	Suggested   string `json:"suggested"`
	Impact      string `json:"impact"`
	Command     string `json:"command"`
	Priority    int    `json:"priority"`
}

type ComponentReport struct {
	Name           string          `json:"name"`
	Status         string          `json:"status"`
	HealthScore    int             `json:"healthScore"`
	Memory         MemoryReport    `json:"memory"`
	Timing         TimingReport    `json:"timing"`
	GC             *GCReport       `json:"gc,omitempty"`
	Buffers        *BufferReport   `json:"buffers,omitempty"`
	Issues         []string        `json:"issues"`
	Recommendations []string       `json:"recommendations"`
}

type MemoryReport struct {
	ContainerUsed   int64   `json:"containerUsedBytes"`
	ContainerLimit  int64   `json:"containerLimitBytes"`
	ContainerPct    float64 `json:"containerPercent"`
	HeapUsed        int64   `json:"heapUsedBytes,omitempty"`
	HeapMax         int64   `json:"heapMaxBytes,omitempty"`
	HeapPct         float64 `json:"heapPercent,omitempty"`
	NonHeapUsed     int64   `json:"nonHeapUsedBytes,omitempty"`
	DirectMemory    int64   `json:"directMemoryBytes,omitempty"`
	Threads         int     `json:"threads,omitempty"`
	PressureLevel   string  `json:"pressureLevel"`
}

type TimingReport struct {
	AvgLatencyMs   float64 `json:"avgLatencyMs"`
	P99LatencyMs   float64 `json:"p99LatencyMs"`
	RequestsPerSec float64 `json:"requestsPerSec"`
	ErrorRate      float64 `json:"errorRate"`
}

type GCReport struct {
	TotalPauses      int     `json:"totalPauses"`
	TotalPauseTimeMs float64 `json:"totalPauseTimeMs"`
	AvgPauseMs       float64 `json:"avgPauseMs"`
	MaxPauseMs       float64 `json:"maxPauseMs"`
	GCOverheadPct    float64 `json:"gcOverheadPercent"`
	YoungGCCount     int     `json:"youngGCCount"`
	FullGCCount      int     `json:"fullGCCount"`
	AllocationRate   string  `json:"allocationRate"`
}

type BufferReport struct {
	KafkaBufferUsed   int64 `json:"kafkaBufferUsedBytes,omitempty"`
	KafkaBufferMax    int64 `json:"kafkaBufferMaxBytes,omitempty"`
	FlinkNetworkUsed  int64 `json:"flinkNetworkUsedBytes,omitempty"`
	FlinkNetworkMax   int64 `json:"flinkNetworkMaxBytes,omitempty"`
	AsyncQueueSize    int   `json:"asyncQueueSize,omitempty"`
	AsyncQueueMax     int   `json:"asyncQueueMax,omitempty"`
}

type Bottleneck struct {
	Component    string  `json:"component"`
	Type         string  `json:"type"`
	Severity     string  `json:"severity"`
	Score        int     `json:"score"`
	Description  string  `json:"description"`
	Impact       string  `json:"impact"`
	Recommendation string `json:"recommendation"`
}

type OOMEvent struct {
	Container  string `json:"container"`
	Timestamp  string `json:"timestamp"`
	RestartCount int  `json:"restartCount"`
}

type Recommendation struct {
	Priority    int    `json:"priority"`
	Category    string `json:"category"`
	Component   string `json:"component"`
	Action      string `json:"action"`
	Expected    string `json:"expectedImprovement"`
}

// ============================================================================
// MAIN DIAGNOSTIC FUNCTION
// ============================================================================

func runDiagnoseFull(cmd *cobra.Command, args []string) {
	report := &DiagnosticReport{
		Timestamp:   time.Now().Format(time.RFC3339),
		Components:  make(map[string]*ComponentReport),
		Bottlenecks: []Bottleneck{},
		OOMEvents:   []OOMEvent{},
		Recommendations: []Recommendation{},
	}

	if !diagnoseJSON {
		printHeader("COMPREHENSIVE SYSTEM DIAGNOSTICS")
		fmt.Printf("Timestamp: %s\n\n", report.Timestamp)
	}

	// Collect diagnostics for each component
	components := []string{"gateway", "drools", "flink-taskmanager", "flink-jobmanager", "kafka", "otel-collector", "jaeger", "loki", "prometheus"}

	if diagnoseComponent != "" {
		components = []string{diagnoseComponent}
	}

	for _, comp := range components {
		compReport := collectComponentDiagnostics(comp)
		if compReport != nil {
			report.Components[comp] = compReport
		}
	}

	// Collect Kafka diagnostics
	report.Kafka = collectKafkaDiagnostics()

	// Analyze GC logs if available
	analyzeGCLogs(report)

	// Analyze recent traces from Jaeger
	report.TraceAnalysis = analyzeTraces(report)

	// Load last benchmark results for context
	report.LastBenchmark = loadLastBenchmark()

	// Detect OOM events
	detectOOMEvents(report)

	// Identify bottlenecks
	identifyBottlenecks(report)

	// Generate optimization opportunities
	generateOptimizations(report)

	// Generate recommendations
	generateRecommendations(report)

	// Calculate overall health
	calculateOverallHealth(report)

	// Output
	if diagnoseJSON {
		outputJSON(report)
	} else {
		outputHumanReadable(report)
	}
}

// ============================================================================
// COMPONENT DIAGNOSTICS COLLECTION
// ============================================================================

func collectComponentDiagnostics(component string) *ComponentReport {
	report := &ComponentReport{
		Name:   component,
		Status: "unknown",
		Issues: []string{},
		Recommendations: []string{},
	}

	// Find container by name pattern (handles docker-compose naming like reactive-system-01-gateway-1)
	containerName := findContainerByComponent(component)
	if containerName == "" {
		report.Status = "not_running"
		return report
	}

	// Check if container is running
	checkCmd := exec.Command("docker", "inspect", containerName, "--format", "{{.State.Status}}")
	output, err := checkCmd.Output()
	if err != nil {
		report.Status = "not_running"
		return report
	}
	report.Status = strings.TrimSpace(string(output))

	// Collect memory stats
	collectMemoryStats(containerName, report)

	// Collect JVM stats for Java components
	if isJavaComponent(component) {
		collectJVMStats(component, report)
	}

	// Collect timing stats from Prometheus
	collectTimingStats(component, report)

	// Calculate health score
	calculateComponentHealth(report)

	return report
}

func isJavaComponent(component string) bool {
	javaComponents := map[string]bool{
		"gateway": true,
		"drools": true,
		"flink-taskmanager": true,
		"flink-jobmanager": true,
	}
	return javaComponents[component]
}

func collectMemoryStats(containerName string, report *ComponentReport) {
	// Get memory usage from docker stats
	cmd := exec.Command("docker", "stats", containerName, "--no-stream", "--format", "{{.MemUsage}}|{{.MemPerc}}")
	output, err := cmd.Output()
	if err != nil {
		return
	}

	parts := strings.Split(strings.TrimSpace(string(output)), "|")
	if len(parts) < 2 {
		return
	}

	// Parse memory usage (e.g., "1.2GiB / 2GiB")
	memParts := strings.Split(parts[0], " / ")
	if len(memParts) == 2 {
		report.Memory.ContainerUsed = parseMemorySize(strings.TrimSpace(memParts[0]))
		report.Memory.ContainerLimit = parseMemorySize(strings.TrimSpace(memParts[1]))
	}

	// Parse percentage
	pctStr := strings.TrimSuffix(parts[1], "%")
	report.Memory.ContainerPct, _ = strconv.ParseFloat(pctStr, 64)

	// Set pressure level
	if report.Memory.ContainerPct > 90 {
		report.Memory.PressureLevel = "CRITICAL"
		report.Issues = append(report.Issues, "Memory usage above 90%")
	} else if report.Memory.ContainerPct > 75 {
		report.Memory.PressureLevel = "HIGH"
		report.Issues = append(report.Issues, "Memory usage above 75%")
	} else if report.Memory.ContainerPct > 50 {
		report.Memory.PressureLevel = "MEDIUM"
	} else {
		report.Memory.PressureLevel = "LOW"
	}
}

func parseMemorySize(s string) int64 {
	s = strings.TrimSpace(s)
	multiplier := int64(1)

	if strings.HasSuffix(s, "GiB") {
		multiplier = 1024 * 1024 * 1024
		s = strings.TrimSuffix(s, "GiB")
	} else if strings.HasSuffix(s, "MiB") {
		multiplier = 1024 * 1024
		s = strings.TrimSuffix(s, "MiB")
	} else if strings.HasSuffix(s, "KiB") {
		multiplier = 1024
		s = strings.TrimSuffix(s, "KiB")
	} else if strings.HasSuffix(s, "GB") {
		multiplier = 1000 * 1000 * 1000
		s = strings.TrimSuffix(s, "GB")
	} else if strings.HasSuffix(s, "MB") {
		multiplier = 1000 * 1000
		s = strings.TrimSuffix(s, "MB")
	}

	val, _ := strconv.ParseFloat(s, 64)
	return int64(val * float64(multiplier))
}

// findContainerByComponent finds a running container by component name
// Handles docker-compose naming patterns like: reactive-system-01-gateway-1
func findContainerByComponent(component string) string {
	// Map component names to container name patterns
	patterns := map[string][]string{
		"gateway":           {"gateway", "application"},
		"drools":            {"drools"},
		"flink-taskmanager": {"flink-taskmanager", "taskmanager"},
		"flink-jobmanager":  {"flink-jobmanager", "jobmanager"},
		"kafka":             {"kafka"},
		"otel-collector":    {"otel-collector", "otel"},
		"jaeger":            {"jaeger"},
		"loki":              {"loki"},
		"prometheus":        {"prometheus"},
	}

	searchPatterns := patterns[component]
	if len(searchPatterns) == 0 {
		searchPatterns = []string{component}
	}

	// Try each pattern - first look for running containers, then stopped
	for _, onlyRunning := range []bool{true, false} {
		for _, pattern := range searchPatterns {
			// Use docker ps with filter to find containers
			args := []string{"ps", "-a", "--filter", "name=" + pattern, "--format", "{{.Names}}|{{.Status}}"}
			cmd := exec.Command("docker", args...)
			output, err := cmd.Output()
			if err != nil {
				continue
			}

			var bestMatch string
			lines := strings.Split(strings.TrimSpace(string(output)), "\n")
			for _, line := range lines {
				parts := strings.Split(line, "|")
				if len(parts) < 2 {
					continue
				}
				name := strings.TrimSpace(parts[0])
				status := strings.TrimSpace(parts[1])

				if name == "" {
					continue
				}

				// Skip init containers and one-shot containers
				if strings.Contains(name, "-init") || strings.Contains(name, "-setup") {
					continue
				}

				// Check if container matches and is in desired state
				isRunning := strings.HasPrefix(status, "Up")
				if onlyRunning && !isRunning {
					continue
				}

				// Verify it matches the component (avoid false positives)
				if strings.Contains(name, component) || strings.Contains(name, pattern) {
					// Prefer exact service name matches
					if strings.HasSuffix(name, "-"+component+"-1") || strings.HasSuffix(name, "-"+component) ||
						name == "reactive-"+component {
						return name
					}
					if bestMatch == "" {
						bestMatch = name
					}
				}
			}
			if bestMatch != "" {
				return bestMatch
			}
		}
	}

	return ""
}

func collectJVMStats(component string, report *ComponentReport) {
	client := &http.Client{Timeout: 5 * time.Second}

	var actuatorURL string
	switch component {
	case "gateway":
		actuatorURL = "http://localhost:8080/actuator/metrics"
	case "drools":
		actuatorURL = "http://localhost:8180/actuator/metrics"
	default:
		return
	}

	// Get heap used
	if val := getActuatorMetric(client, actuatorURL+"/jvm.memory.used", "area:heap"); val > 0 {
		report.Memory.HeapUsed = int64(val)
	}

	// Get heap max
	if val := getActuatorMetric(client, actuatorURL+"/jvm.memory.max", "area:heap"); val > 0 {
		report.Memory.HeapMax = int64(val)
	}

	// Calculate heap percentage
	if report.Memory.HeapMax > 0 {
		report.Memory.HeapPct = float64(report.Memory.HeapUsed) / float64(report.Memory.HeapMax) * 100
		if report.Memory.HeapPct > 90 {
			report.Issues = append(report.Issues, "JVM heap usage above 90%")
		}
	}

	// Get thread count
	if val := getActuatorMetric(client, actuatorURL+"/jvm.threads.live", ""); val > 0 {
		report.Memory.Threads = int(val)
	}

	// Get non-heap
	if val := getActuatorMetric(client, actuatorURL+"/jvm.memory.used", "area:nonheap"); val > 0 {
		report.Memory.NonHeapUsed = int64(val)
	}
}

func getActuatorMetric(client *http.Client, url, tag string) float64 {
	fullURL := url
	if tag != "" {
		fullURL = fmt.Sprintf("%s?tag=%s", url, tag)
	}

	resp, err := client.Get(fullURL)
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

func collectTimingStats(component string, report *ComponentReport) {
	client := &http.Client{Timeout: 5 * time.Second}

	switch component {
	case "gateway":
		collectGatewayTiming(client, report)
	case "drools":
		collectDroolsTiming(client, report)
	case "flink-taskmanager":
		collectFlinkTiming(client, report)
	case "kafka":
		collectKafkaTiming(client, report)
	}
}

func collectGatewayTiming(client *http.Client, report *ComponentReport) {
	// Request rate (ops/sec)
	if val := queryPrometheus(client, `sum(rate(http_server_requests_seconds_count{job="gateway",uri!~".*actuator.*"}[1m]))`); val > 0 {
		report.Timing.RequestsPerSec = val
	}

	// Average latency
	if val := queryPrometheus(client, `sum(rate(http_server_requests_seconds_sum{job="gateway",uri!~".*actuator.*"}[1m]))/sum(rate(http_server_requests_seconds_count{job="gateway",uri!~".*actuator.*"}[1m]))`); val > 0 {
		report.Timing.AvgLatencyMs = val * 1000
	}

	// P99 latency (using histogram if available)
	if val := queryPrometheus(client, `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{job="gateway",uri!~".*actuator.*"}[1m])) by (le))`); val > 0 {
		report.Timing.P99LatencyMs = val * 1000
	}

	// Error rate
	total := queryPrometheus(client, `sum(rate(http_server_requests_seconds_count{job="gateway",uri!~".*actuator.*"}[1m]))`)
	errors := queryPrometheus(client, `sum(rate(http_server_requests_seconds_count{job="gateway",uri!~".*actuator.*",status=~"5.."}[1m]))`)
	if total > 0 {
		report.Timing.ErrorRate = (errors / total) * 100
	}
}

func collectDroolsTiming(client *http.Client, report *ComponentReport) {
	// Request rate
	if val := queryPrometheus(client, `sum(rate(http_server_requests_seconds_count{job="drools",uri="/api/evaluate"}[1m]))`); val > 0 {
		report.Timing.RequestsPerSec = val
	}

	// Average latency
	if val := queryPrometheus(client, `sum(rate(http_server_requests_seconds_sum{job="drools",uri="/api/evaluate"}[1m]))/sum(rate(http_server_requests_seconds_count{job="drools",uri="/api/evaluate"}[1m]))`); val > 0 {
		report.Timing.AvgLatencyMs = val * 1000
	}
}

func collectFlinkTiming(client *http.Client, report *ComponentReport) {
	// Flink metrics from Prometheus
	if val := queryPrometheus(client, `flink_taskmanager_job_task_operator_numRecordsOutPerSecond`); val > 0 {
		report.Timing.RequestsPerSec = val
	}
}

func collectKafkaTiming(client *http.Client, report *ComponentReport) {
	// Kafka broker metrics
	if val := queryPrometheus(client, `sum(rate(kafka_server_brokertopicmetrics_messagesin_total[1m]))`); val > 0 {
		report.Timing.RequestsPerSec = val
	}
}

// ============================================================================
// KAFKA DIAGNOSTICS COLLECTION
// ============================================================================

func collectKafkaDiagnostics() *KafkaReport {
	report := &KafkaReport{
		Brokers:        []BrokerInfo{},
		Topics:         []TopicInfo{},
		ConsumerGroups: []ConsumerGroupInfo{},
		Issues:         []string{},
	}

	// Find Kafka container
	kafkaContainer := findContainerByComponent("kafka")
	if kafkaContainer == "" {
		report.Issues = append(report.Issues, "Kafka container not found")
		return report
	}

	// Collect topic information with partition details
	collectTopicInfo(kafkaContainer, report)

	// Collect consumer group lag
	collectConsumerGroups(kafkaContainer, report)

	// Calculate partition balance
	calculatePartitionBalance(report)

	// Collect throughput metrics from Prometheus
	collectKafkaMetrics(report)

	return report
}

func collectTopicInfo(container string, report *KafkaReport) {
	// Get list of topics
	topicsCmd := exec.Command("docker", "exec", container, "kafka-topics",
		"--bootstrap-server", "localhost:9092", "--list")
	topicsOutput, err := topicsCmd.Output()
	if err != nil {
		report.Issues = append(report.Issues, "Failed to list Kafka topics")
		return
	}

	topics := strings.Split(strings.TrimSpace(string(topicsOutput)), "\n")

	// Filter to relevant topics (counter-events, counter-results, counter-alerts)
	relevantTopics := []string{}
	for _, t := range topics {
		t = strings.TrimSpace(t)
		if t != "" && (strings.Contains(t, "counter-") || strings.Contains(t, "events") || strings.Contains(t, "results") || strings.Contains(t, "alerts")) {
			relevantTopics = append(relevantTopics, t)
		}
	}

	for _, topic := range relevantTopics {
		topicInfo := TopicInfo{
			Name:           topic,
			PartitionStats: []PartitionStat{},
		}

		// Get topic description (partitions, replication)
		descCmd := exec.Command("docker", "exec", container, "kafka-topics",
			"--bootstrap-server", "localhost:9092", "--describe", "--topic", topic)
		descOutput, err := descCmd.Output()
		if err != nil {
			continue
		}

		// Parse topic description
		lines := strings.Split(string(descOutput), "\n")
		for _, line := range lines {
			line = strings.TrimSpace(line)

			// Parse topic header line (Topic: counter-events PartitionCount: 8 ReplicationFactor: 1)
			if strings.HasPrefix(line, "Topic:") && strings.Contains(line, "PartitionCount:") {
				parts := strings.Fields(line)
				for i, part := range parts {
					if part == "PartitionCount:" && i+1 < len(parts) {
						topicInfo.Partitions, _ = strconv.Atoi(parts[i+1])
					}
					if part == "ReplicationFactor:" && i+1 < len(parts) {
						topicInfo.ReplicationFactor, _ = strconv.Atoi(parts[i+1])
					}
				}
			}

			// Parse partition lines (Topic: counter-events Partition: 0 Leader: 1 Replicas: 1 Isr: 1)
			if strings.Contains(line, "Partition:") && strings.Contains(line, "Leader:") {
				stat := PartitionStat{}
				parts := strings.Fields(line)
				for i, part := range parts {
					if part == "Partition:" && i+1 < len(parts) {
						stat.Partition, _ = strconv.Atoi(parts[i+1])
					}
					if part == "Leader:" && i+1 < len(parts) {
						stat.Leader, _ = strconv.Atoi(parts[i+1])
					}
				}
				topicInfo.PartitionStats = append(topicInfo.PartitionStats, stat)
			}
		}

		// Get partition offsets to calculate message counts
		getPartitionOffsets(container, topic, &topicInfo)

		report.Topics = append(report.Topics, topicInfo)
	}
}

func getPartitionOffsets(container string, topic string, topicInfo *TopicInfo) {
	// Get earliest offsets
	earliestCmd := exec.Command("docker", "exec", container, "kafka-run-class",
		"kafka.tools.GetOffsetShell",
		"--broker-list", "localhost:9092",
		"--topic", topic,
		"--time", "-2") // -2 = earliest
	earliestOutput, _ := earliestCmd.Output()

	earliestOffsets := parseOffsetOutput(string(earliestOutput))

	// Get latest offsets
	latestCmd := exec.Command("docker", "exec", container, "kafka-run-class",
		"kafka.tools.GetOffsetShell",
		"--broker-list", "localhost:9092",
		"--topic", topic,
		"--time", "-1") // -1 = latest
	latestOutput, _ := latestCmd.Output()

	latestOffsets := parseOffsetOutput(string(latestOutput))

	// Update partition stats with offsets
	var totalMessages int64
	for i := range topicInfo.PartitionStats {
		partition := topicInfo.PartitionStats[i].Partition
		if begin, ok := earliestOffsets[partition]; ok {
			topicInfo.PartitionStats[i].BeginOffset = begin
		}
		if end, ok := latestOffsets[partition]; ok {
			topicInfo.PartitionStats[i].EndOffset = end
		}
		msgCount := topicInfo.PartitionStats[i].EndOffset - topicInfo.PartitionStats[i].BeginOffset
		topicInfo.PartitionStats[i].MessageCount = msgCount
		totalMessages += msgCount
	}
	topicInfo.MessageCount = totalMessages
}

func parseOffsetOutput(output string) map[int]int64 {
	offsets := make(map[int]int64)
	lines := strings.Split(output, "\n")
	for _, line := range lines {
		// Format: topic:partition:offset
		parts := strings.Split(strings.TrimSpace(line), ":")
		if len(parts) >= 3 {
			partition, _ := strconv.Atoi(parts[1])
			offset, _ := strconv.ParseInt(parts[2], 10, 64)
			offsets[partition] = offset
		}
	}
	return offsets
}

func collectConsumerGroups(container string, report *KafkaReport) {
	// List consumer groups
	listCmd := exec.Command("docker", "exec", container, "kafka-consumer-groups",
		"--bootstrap-server", "localhost:9092", "--list")
	listOutput, err := listCmd.Output()
	if err != nil {
		report.Issues = append(report.Issues, "Failed to list consumer groups")
		return
	}

	groups := strings.Split(strings.TrimSpace(string(listOutput)), "\n")

	for _, group := range groups {
		group = strings.TrimSpace(group)
		if group == "" {
			continue
		}

		groupInfo := ConsumerGroupInfo{
			GroupID:      group,
			PartitionLag: []PartitionLagInfo{},
		}

		// Describe consumer group
		descCmd := exec.Command("docker", "exec", container, "kafka-consumer-groups",
			"--bootstrap-server", "localhost:9092", "--describe", "--group", group)
		descOutput, err := descCmd.Output()
		if err != nil {
			continue
		}

		lines := strings.Split(string(descOutput), "\n")
		memberSet := make(map[string]bool)

		for _, line := range lines {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "GROUP") || strings.HasPrefix(line, "Consumer group") {
				continue
			}

			// Parse line: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
			fields := strings.Fields(line)
			if len(fields) >= 6 {
				lagInfo := PartitionLagInfo{}

				// Different output formats - try to parse
				if len(fields) >= 7 {
					lagInfo.Topic = fields[1]
					lagInfo.Partition, _ = strconv.Atoi(fields[2])
					lagInfo.CurrentOffset, _ = strconv.ParseInt(fields[3], 10, 64)
					lagInfo.LogEndOffset, _ = strconv.ParseInt(fields[4], 10, 64)
					lagInfo.Lag, _ = strconv.ParseInt(fields[5], 10, 64)
					if len(fields) > 6 && fields[6] != "-" {
						lagInfo.Consumer = fields[6]
						memberSet[fields[6]] = true
					}
				}

				if lagInfo.Topic != "" {
					groupInfo.PartitionLag = append(groupInfo.PartitionLag, lagInfo)
					groupInfo.TotalLag += lagInfo.Lag
				}
			}
		}

		groupInfo.Members = len(memberSet)

		// Determine state based on lag
		if groupInfo.TotalLag == 0 {
			groupInfo.State = "CAUGHT_UP"
		} else if groupInfo.TotalLag < 1000 {
			groupInfo.State = "LOW_LAG"
		} else if groupInfo.TotalLag < 10000 {
			groupInfo.State = "MODERATE_LAG"
		} else {
			groupInfo.State = "HIGH_LAG"
			report.Issues = append(report.Issues, fmt.Sprintf("Consumer group '%s' has high lag: %d", group, groupInfo.TotalLag))
		}

		report.ConsumerGroups = append(report.ConsumerGroups, groupInfo)
	}
}

func calculatePartitionBalance(report *KafkaReport) {
	// Check ALL topics for partition skew and add actionable recommendations
	var totalMessages int64

	for i := range report.Topics {
		topic := &report.Topics[i]
		if len(topic.PartitionStats) == 0 {
			continue
		}

		// Calculate per-topic distribution
		var maxOffset, minOffset int64 = 0, int64(^uint64(0) >> 1)
		var hotPartition, coldPartition int = -1, -1
		var topicTotal int64

		for _, ps := range topic.PartitionStats {
			topicTotal += ps.EndOffset
			if ps.EndOffset > maxOffset {
				maxOffset = ps.EndOffset
				hotPartition = ps.Partition
			}
			if ps.EndOffset < minOffset {
				minOffset = ps.EndOffset
				coldPartition = ps.Partition
			}
		}
		totalMessages += topicTotal

		// Check for skew: if one partition has >50% of all messages
		if topicTotal > 0 && maxOffset > 0 {
			hotRatio := float64(maxOffset) / float64(topicTotal)
			if hotRatio > 0.5 { // More than 50% on one partition = severe skew
				report.Issues = append(report.Issues,
					fmt.Sprintf("CRITICAL: Topic '%s' partition %d has %.0f%% of all messages (%d/%d). "+
						"Check Kafka producer partition key - likely using a constant key (e.g., sessionId) "+
						"instead of a distributed key (e.g., eventId/UUID).",
						topic.Name, hotPartition, hotRatio*100, maxOffset, topicTotal))
			} else if minOffset > 0 {
				ratio := float64(maxOffset) / float64(minOffset)
				if ratio > 2.0 {
					report.Issues = append(report.Issues,
						fmt.Sprintf("WARNING: Topic '%s' has partition imbalance (partition %d: %d, partition %d: %d, ratio: %.1fx)",
							topic.Name, hotPartition, maxOffset, coldPartition, minOffset, ratio))
				}
			}
		}
	}

	report.TotalMessages = totalMessages

	// Calculate balance for main events topic (for the summary)
	var eventsTopic *TopicInfo
	for i := range report.Topics {
		if strings.Contains(report.Topics[i].Name, "events") {
			eventsTopic = &report.Topics[i]
			break
		}
	}

	if eventsTopic == nil || len(eventsTopic.PartitionStats) == 0 {
		return
	}

	balance := &PartitionBalance{
		TotalPartitions: len(eventsTopic.PartitionStats),
	}

	var counts []int64
	var sum int64
	balance.MinMessagesOnPart = int64(^uint64(0) >> 1)
	balance.MaxMessagesOnPart = 0

	for _, ps := range eventsTopic.PartitionStats {
		written := ps.EndOffset
		counts = append(counts, written)
		sum += written
		if written > balance.MaxMessagesOnPart {
			balance.MaxMessagesOnPart = written
		}
		if written < balance.MinMessagesOnPart {
			balance.MinMessagesOnPart = written
		}
	}

	if sum == 0 {
		balance.BalanceScore = 1.0
		report.PartitionBalance = balance
		return
	}

	mean := float64(sum) / float64(len(counts))
	var variance float64
	for _, c := range counts {
		diff := float64(c) - mean
		variance += diff * diff
	}
	variance /= float64(len(counts))
	balance.StdDeviation = math.Sqrt(variance)

	if mean > 0 {
		cv := balance.StdDeviation / mean
		balance.BalanceScore = 1.0 / (1.0 + cv)
	}

	if balance.MinMessagesOnPart > 0 {
		ratio := float64(balance.MaxMessagesOnPart) / float64(balance.MinMessagesOnPart)
		if ratio > 2.0 || balance.BalanceScore < 0.7 {
			balance.Skewed = true
		}
	}

	report.PartitionBalance = balance
}

func collectKafkaMetrics(report *KafkaReport) {
	client := &http.Client{Timeout: 5 * time.Second}

	// Messages per second
	if val := queryPrometheus(client, `sum(rate(kafka_server_brokertopicmetrics_messagesin_total[1m]))`); val > 0 {
		report.MessagesPerSecond = val
	}

	// Bytes in per second
	if val := queryPrometheus(client, `sum(rate(kafka_server_brokertopicmetrics_bytesin_total[1m]))`); val > 0 {
		report.BytesInPerSecond = val
	}

	// Bytes out per second
	if val := queryPrometheus(client, `sum(rate(kafka_server_brokertopicmetrics_bytesout_total[1m]))`); val > 0 {
		report.BytesOutPerSecond = val
	}
}

func queryPrometheus(client *http.Client, query string) float64 {
	promURL := "http://localhost:9090/api/v1/query"
	resp, err := client.Get(fmt.Sprintf("%s?query=%s", promURL, query))
	if err != nil {
		return 0
	}
	defer resp.Body.Close()

	var promResult struct {
		Data struct {
			Result []struct {
				Value []interface{} `json:"value"`
			} `json:"result"`
		} `json:"data"`
	}

	body, _ := io.ReadAll(resp.Body)
	if json.Unmarshal(body, &promResult) == nil && len(promResult.Data.Result) > 0 {
		if len(promResult.Data.Result[0].Value) > 1 {
			if valStr, ok := promResult.Data.Result[0].Value[1].(string); ok {
				val, _ := strconv.ParseFloat(valStr, 64)
				if !isNaN(val) {
					return val
				}
			}
		}
	}
	return 0
}

func isNaN(f float64) bool {
	return f != f
}

// ============================================================================
// TRACE ANALYSIS (Jaeger)
// ============================================================================

type TraceAnalysis struct {
	TotalTraces    int                      `json:"totalTraces"`
	AvgSpanCount   float64                  `json:"avgSpanCount"`
	ServiceBreakdown map[string]ServiceTiming `json:"serviceBreakdown"`
	SlowOperations []SlowOperation          `json:"slowOperations"`
}

type ServiceTiming struct {
	Service      string  `json:"service"`
	AvgDurationMs float64 `json:"avgDurationMs"`
	MaxDurationMs float64 `json:"maxDurationMs"`
	SpanCount    int     `json:"spanCount"`
	PctOfTrace   float64 `json:"percentOfTrace"`
}

type SlowOperation struct {
	Operation   string  `json:"operation"`
	Service     string  `json:"service"`
	AvgDurationMs float64 `json:"avgDurationMs"`
	MaxDurationMs float64 `json:"maxDurationMs"`
	Count       int     `json:"count"`
}

func analyzeTraces(report *DiagnosticReport) *TraceAnalysis {
	client := &http.Client{Timeout: 10 * time.Second}
	analysis := &TraceAnalysis{
		ServiceBreakdown: make(map[string]ServiceTiming),
		SlowOperations:   []SlowOperation{},
	}

	// Fetch recent traces from Jaeger
	jaegerURL := "http://localhost:16686/api/traces"
	services := []string{"counter-application", "flink-taskmanager", "drools"}

	for _, service := range services {
		resp, err := client.Get(fmt.Sprintf("%s?service=%s&limit=10&lookback=5m", jaegerURL, service))
		if err != nil {
			continue
		}
		defer resp.Body.Close()

		var result struct {
			Data []struct {
				TraceID string `json:"traceID"`
				Spans   []struct {
					SpanID        string `json:"spanID"`
					OperationName string `json:"operationName"`
					Duration      int64  `json:"duration"` // microseconds
					ProcessID     string `json:"processID"`
				} `json:"spans"`
				Processes map[string]struct {
					ServiceName string `json:"serviceName"`
				} `json:"processes"`
			} `json:"data"`
		}

		body, _ := io.ReadAll(resp.Body)
		if json.Unmarshal(body, &result) != nil {
			continue
		}

		// Analyze spans
		operationStats := make(map[string]struct {
			totalDuration int64
			maxDuration   int64
			count         int
			service       string
		})

		for _, trace := range result.Data {
			analysis.TotalTraces++

			for _, span := range trace.Spans {
				proc := trace.Processes[span.ProcessID]
				key := proc.ServiceName + ":" + span.OperationName

				stats := operationStats[key]
				stats.totalDuration += span.Duration
				if span.Duration > stats.maxDuration {
					stats.maxDuration = span.Duration
				}
				stats.count++
				stats.service = proc.ServiceName
				operationStats[key] = stats

				// Update service breakdown
				if st, ok := analysis.ServiceBreakdown[proc.ServiceName]; ok {
					st.SpanCount++
					st.AvgDurationMs = (st.AvgDurationMs*float64(st.SpanCount-1) + float64(span.Duration)/1000) / float64(st.SpanCount)
					if float64(span.Duration)/1000 > st.MaxDurationMs {
						st.MaxDurationMs = float64(span.Duration) / 1000
					}
					analysis.ServiceBreakdown[proc.ServiceName] = st
				} else {
					analysis.ServiceBreakdown[proc.ServiceName] = ServiceTiming{
						Service:       proc.ServiceName,
						AvgDurationMs: float64(span.Duration) / 1000,
						MaxDurationMs: float64(span.Duration) / 1000,
						SpanCount:     1,
					}
				}
			}
		}

		// Find slow operations
		for key, stats := range operationStats {
			parts := strings.SplitN(key, ":", 2)
			if len(parts) == 2 && stats.count > 0 {
				avgMs := float64(stats.totalDuration) / float64(stats.count) / 1000
				if avgMs > 10 { // Only include operations > 10ms
					analysis.SlowOperations = append(analysis.SlowOperations, SlowOperation{
						Operation:     parts[1],
						Service:       parts[0],
						AvgDurationMs: avgMs,
						MaxDurationMs: float64(stats.maxDuration) / 1000,
						Count:         stats.count,
					})
				}
			}
		}
	}

	// Sort slow operations by avg duration
	sort.Slice(analysis.SlowOperations, func(i, j int) bool {
		return analysis.SlowOperations[i].AvgDurationMs > analysis.SlowOperations[j].AvgDurationMs
	})

	// Keep top 10
	if len(analysis.SlowOperations) > 10 {
		analysis.SlowOperations = analysis.SlowOperations[:10]
	}

	return analysis
}

// ============================================================================
// GC LOG ANALYSIS
// ============================================================================

func analyzeGCLogs(report *DiagnosticReport) {
	// Look for GC logs in diagnostics directory
	gcLogFiles := []string{
		"diagnostics/gc.log.0",
		"diagnostics/gc.log",
	}

	for _, logFile := range gcLogFiles {
		if _, err := os.Stat(logFile); err == nil {
			gcReport := parseGCLog(logFile)
			if gcReport != nil && gcReport.TotalPauses > 0 {
				// Assign to flink-taskmanager (where GC logs are from)
				if comp, ok := report.Components["flink-taskmanager"]; ok {
					comp.GC = gcReport

					// Check for GC issues
					if gcReport.MaxPauseMs > 500 {
						comp.Issues = append(comp.Issues, fmt.Sprintf("GC pause >500ms detected (max: %.1fms)", gcReport.MaxPauseMs))
					}
					if gcReport.GCOverheadPct > 5 {
						comp.Issues = append(comp.Issues, fmt.Sprintf("GC overhead >5%% (%.1f%%)", gcReport.GCOverheadPct))
					}
					if gcReport.FullGCCount > 0 {
						comp.Issues = append(comp.Issues, fmt.Sprintf("Full GC detected (%d times)", gcReport.FullGCCount))
					}
				}
			}
			break
		}
	}
}

func parseGCLog(filename string) *GCReport {
	file, err := os.Open(filename)
	if err != nil {
		return nil
	}
	defer file.Close()

	report := &GCReport{}

	// Regex patterns for G1GC log parsing
	pausePattern := regexp.MustCompile(`Pause (?:Young|Full|Mixed).*?(\d+\.?\d*)ms`)
	fullGCPattern := regexp.MustCompile(`Pause Full`)
	youngGCPattern := regexp.MustCompile(`Pause Young`)
	allocPattern := regexp.MustCompile(`Allocation Rate: (\d+\.?\d*) MB/s`)

	scanner := bufio.NewScanner(file)
	var pauses []float64
	lineCount := 0
	maxLines := 10000 // Limit for performance

	for scanner.Scan() && lineCount < maxLines {
		line := scanner.Text()
		lineCount++

		// Parse pause times
		if matches := pausePattern.FindStringSubmatch(line); len(matches) > 1 {
			pauseMs, _ := strconv.ParseFloat(matches[1], 64)
			pauses = append(pauses, pauseMs)
			report.TotalPauses++
			report.TotalPauseTimeMs += pauseMs
			if pauseMs > report.MaxPauseMs {
				report.MaxPauseMs = pauseMs
			}
		}

		// Count GC types
		if fullGCPattern.MatchString(line) {
			report.FullGCCount++
		}
		if youngGCPattern.MatchString(line) {
			report.YoungGCCount++
		}

		// Parse allocation rate
		if matches := allocPattern.FindStringSubmatch(line); len(matches) > 1 {
			report.AllocationRate = matches[1] + " MB/s"
		}
	}

	// Calculate averages
	if report.TotalPauses > 0 {
		report.AvgPauseMs = report.TotalPauseTimeMs / float64(report.TotalPauses)
	}

	return report
}

// ============================================================================
// OOM DETECTION
// ============================================================================

func detectOOMEvents(report *DiagnosticReport) {
	cmd := exec.Command("docker", "ps", "-a", "--format", "{{.Names}}")
	output, _ := cmd.Output()
	containers := strings.Split(strings.TrimSpace(string(output)), "\n")

	for _, container := range containers {
		if !strings.Contains(container, "reactive") && !strings.Contains(container, "flink") {
			continue
		}

		// Check OOMKilled status
		inspectCmd := exec.Command("docker", "inspect", container, "--format",
			"{{.State.OOMKilled}}|{{.RestartCount}}|{{.State.FinishedAt}}")
		out, err := inspectCmd.Output()
		if err != nil {
			continue
		}

		parts := strings.Split(strings.TrimSpace(string(out)), "|")
		if len(parts) >= 3 && parts[0] == "true" {
			restartCount, _ := strconv.Atoi(parts[1])
			report.OOMEvents = append(report.OOMEvents, OOMEvent{
				Container:    container,
				Timestamp:    parts[2],
				RestartCount: restartCount,
			})
		}
	}
}

// ============================================================================
// BOTTLENECK IDENTIFICATION
// ============================================================================

func identifyBottlenecks(report *DiagnosticReport) {
	for name, comp := range report.Components {
		// Memory bottleneck
		if comp.Memory.ContainerPct > 80 {
			severity := "WARNING"
			score := int(comp.Memory.ContainerPct)
			if comp.Memory.ContainerPct > 90 {
				severity = "CRITICAL"
			}
			report.Bottlenecks = append(report.Bottlenecks, Bottleneck{
				Component:    name,
				Type:         "MEMORY",
				Severity:     severity,
				Score:        score,
				Description:  fmt.Sprintf("Container at %.1f%% memory", comp.Memory.ContainerPct),
				Impact:       "Risk of OOM crash, degraded performance",
				Recommendation: fmt.Sprintf("Increase memory limit or reduce %s workload", name),
			})
		}

		// GC bottleneck
		if comp.GC != nil && comp.GC.MaxPauseMs > 200 {
			severity := "WARNING"
			score := int(comp.GC.MaxPauseMs / 10)
			if comp.GC.MaxPauseMs > 500 {
				severity = "CRITICAL"
			}
			report.Bottlenecks = append(report.Bottlenecks, Bottleneck{
				Component:    name,
				Type:         "GC_PAUSE",
				Severity:     severity,
				Score:        minInt(score, 100),
				Description:  fmt.Sprintf("Max GC pause: %.1fms", comp.GC.MaxPauseMs),
				Impact:       "Latency spikes, throughput degradation",
				Recommendation: "Increase heap size or tune GC settings",
			})
		}

		// Heap bottleneck
		if comp.Memory.HeapPct > 85 {
			severity := "WARNING"
			score := int(comp.Memory.HeapPct)
			if comp.Memory.HeapPct > 95 {
				severity = "CRITICAL"
			}
			report.Bottlenecks = append(report.Bottlenecks, Bottleneck{
				Component:    name,
				Type:         "HEAP",
				Severity:     severity,
				Score:        score,
				Description:  fmt.Sprintf("JVM heap at %.1f%%", comp.Memory.HeapPct),
				Impact:       "Frequent GC, potential OutOfMemoryError",
				Recommendation: "Increase -Xmx or reduce memory usage",
			})
		}
	}

	// OOM bottleneck
	if len(report.OOMEvents) > 0 {
		for _, oom := range report.OOMEvents {
			report.Bottlenecks = append(report.Bottlenecks, Bottleneck{
				Component:    oom.Container,
				Type:         "OOM_KILLED",
				Severity:     "CRITICAL",
				Score:        100,
				Description:  fmt.Sprintf("Container was OOM killed (restarts: %d)", oom.RestartCount),
				Impact:       "Service unavailability, data loss",
				Recommendation: "Increase memory limit immediately",
			})
		}
	}

	// Sort bottlenecks by score
	sort.Slice(report.Bottlenecks, func(i, j int) bool {
		return report.Bottlenecks[i].Score > report.Bottlenecks[j].Score
	})
}

// ============================================================================
// LAST BENCHMARK LOADING
// ============================================================================

func loadLastBenchmark() *BenchmarkSummary {
	// Try to load the most recent benchmark results
	resultsPath := "reports/full/results.json"
	data, err := os.ReadFile(resultsPath)
	if err != nil {
		return nil
	}

	var results map[string]interface{}
	if err := json.Unmarshal(data, &results); err != nil {
		return nil
	}

	summary := &BenchmarkSummary{}

	if v, ok := results["startTime"].(string); ok {
		summary.Timestamp = v
	}
	if v, ok := results["totalOperations"].(float64); ok {
		summary.TotalOps = int64(v)
	}
	if v, ok := results["successfulOperations"].(float64); ok && summary.TotalOps > 0 {
		summary.SuccessRate = v / float64(summary.TotalOps) * 100
	}
	if v, ok := results["avgThroughput"].(float64); ok {
		summary.AvgThroughput = v
	}
	if v, ok := results["peakThroughput"].(float64); ok {
		summary.PeakThroughput = v
	}
	if latency, ok := results["latency"].(map[string]interface{}); ok {
		if v, ok := latency["avg"].(float64); ok {
			summary.AvgLatencyMs = v
		}
		if v, ok := latency["p99"].(float64); ok {
			summary.P99LatencyMs = v
		}
	}
	if v, ok := results["avgCpu"].(float64); ok {
		summary.AvgCPU = v
	}
	if v, ok := results["peakMemory"].(float64); ok {
		summary.PeakMemory = v
	}
	if v, ok := results["throughputStability"].(float64); ok {
		summary.ThroughputStability = v
	}

	return summary
}

// ============================================================================
// OPTIMIZATION OPPORTUNITIES
// ============================================================================

func generateOptimizations(report *DiagnosticReport) {
	priority := 1

	// Calculate total memory usage and identify waste
	var totalMemoryUsed, totalMemoryLimit int64
	for _, comp := range report.Components {
		totalMemoryUsed += comp.Memory.ContainerUsed
		totalMemoryLimit += comp.Memory.ContainerLimit
	}

	// Memory optimization: containers using < 30% of allocated memory
	for name, comp := range report.Components {
		if comp.Status != "running" {
			continue
		}

		// Under-utilized memory (could reduce container limit)
		if comp.Memory.ContainerPct < 30 && comp.Memory.ContainerLimit > 512*1024*1024 {
			currentMB := comp.Memory.ContainerLimit / (1024 * 1024)
			suggestedMB := (comp.Memory.ContainerUsed * 2) / (1024 * 1024)
			if suggestedMB < 512 {
				suggestedMB = 512
			}
			if suggestedMB < currentMB-256 {
				report.Optimizations = append(report.Optimizations, Optimization{
					Category:  "MEMORY_REDUCE",
					Component: name,
					Current:   fmt.Sprintf("%dMB allocated, %.0f%% used", currentMB, comp.Memory.ContainerPct),
					Suggested: fmt.Sprintf("Reduce to %dMB", suggestedMB),
					Impact:    fmt.Sprintf("Save %dMB for other services", currentMB-suggestedMB),
					Command:   fmt.Sprintf("# In docker-compose.yml, %s.deploy.resources.limits.memory: %dM", name, suggestedMB),
					Priority:  priority,
				})
				priority++
			}
		}

		// Over-utilized memory (should increase to prevent OOM)
		if comp.Memory.ContainerPct > 70 {
			currentMB := comp.Memory.ContainerLimit / (1024 * 1024)
			suggestedMB := (comp.Memory.ContainerLimit * 15 / 10) / (1024 * 1024) // 50% increase
			report.Optimizations = append(report.Optimizations, Optimization{
				Category:  "MEMORY_INCREASE",
				Component: name,
				Current:   fmt.Sprintf("%dMB allocated, %.0f%% used", currentMB, comp.Memory.ContainerPct),
				Suggested: fmt.Sprintf("Increase to %dMB", suggestedMB),
				Impact:    "Prevent OOM during peak load",
				Command:   fmt.Sprintf("# In docker-compose.yml, %s.deploy.resources.limits.memory: %dM", name, suggestedMB),
				Priority:  priority,
			})
			priority++
		}

		// JVM heap optimization
		if comp.Memory.HeapPct > 70 {
			heapMB := comp.Memory.HeapMax / (1024 * 1024)
			suggestedHeapMB := heapMB * 15 / 10 // 50% increase
			report.Optimizations = append(report.Optimizations, Optimization{
				Category:  "HEAP_INCREASE",
				Component: name,
				Current:   fmt.Sprintf("-Xmx%dm (%.0f%% used)", heapMB, comp.Memory.HeapPct),
				Suggested: fmt.Sprintf("-Xmx%dm", suggestedHeapMB),
				Impact:    "Reduce GC frequency, prevent OutOfMemoryError",
				Command:   fmt.Sprintf("# In docker-compose.yml, %s JAVA_OPTS: add -Xmx%dm", name, suggestedHeapMB),
				Priority:  priority,
			})
			priority++
		}

		// GC tuning optimization
		if comp.GC != nil {
			if comp.GC.MaxPauseMs > 100 {
				report.Optimizations = append(report.Optimizations, Optimization{
					Category:  "GC_TUNING",
					Component: name,
					Current:   fmt.Sprintf("Max GC pause: %.0fms", comp.GC.MaxPauseMs),
					Suggested: "Target 50ms max pause",
					Impact:    "Reduce latency spikes, improve P99",
					Command:   fmt.Sprintf("# Add to JAVA_OPTS: -XX:MaxGCPauseMillis=50 -XX:+UseG1GC"),
					Priority:  priority,
				})
				priority++
			}
			if comp.GC.FullGCCount > 0 {
				report.Optimizations = append(report.Optimizations, Optimization{
					Category:  "GC_FULL_GC",
					Component: name,
					Current:   fmt.Sprintf("%d Full GC events detected", comp.GC.FullGCCount),
					Suggested: "Increase heap to eliminate Full GC",
					Impact:    "Major latency improvement (Full GC causes 100ms+ pauses)",
					Command:   "# Double heap size: -Xmx should be 2x current value",
					Priority:  priority,
				})
				priority++
			}
		}
	}

	// Benchmark-based optimizations
	if report.LastBenchmark != nil {
		// Throughput stability optimization
		if report.LastBenchmark.ThroughputStability < 0.7 {
			report.Optimizations = append(report.Optimizations, Optimization{
				Category:  "THROUGHPUT_STABILITY",
				Component: "system",
				Current:   fmt.Sprintf("Stability: %.0f%% (inconsistent)", report.LastBenchmark.ThroughputStability*100),
				Suggested: "Target >80% stability",
				Impact:    "Predictable performance, better user experience",
				Command:   "# Investigate GC pauses, Kafka batching, connection pooling",
				Priority:  priority,
			})
			priority++
		}

		// High latency optimization
		if report.LastBenchmark.P99LatencyMs > 100 {
			report.Optimizations = append(report.Optimizations, Optimization{
				Category:  "LATENCY_P99",
				Component: "system",
				Current:   fmt.Sprintf("P99 latency: %.0fms", report.LastBenchmark.P99LatencyMs),
				Suggested: "Target <50ms P99",
				Impact:    "Better tail latency, improved reliability",
				Command:   "# Check GC logs, enable async processing, tune timeouts",
				Priority:  priority,
			})
			priority++
		}

		// CPU optimization
		if report.LastBenchmark.AvgCPU > 300 {
			report.Optimizations = append(report.Optimizations, Optimization{
				Category:  "CPU_USAGE",
				Component: "system",
				Current:   fmt.Sprintf("Avg CPU: %.0f%% across all containers", report.LastBenchmark.AvgCPU),
				Suggested: "Reduce CPU-intensive operations",
				Impact:    "Lower costs, room for growth",
				Command:   "# Profile hot spots, optimize serialization, reduce logging",
				Priority:  priority,
			})
			priority++
		}
	}

	// Sort by priority
	sort.Slice(report.Optimizations, func(i, j int) bool {
		return report.Optimizations[i].Priority < report.Optimizations[j].Priority
	})
}

// ============================================================================
// RECOMMENDATIONS
// ============================================================================

func generateRecommendations(report *DiagnosticReport) {
	priority := 1

	// Based on bottlenecks
	for _, bn := range report.Bottlenecks {
		if bn.Severity == "CRITICAL" {
			report.Recommendations = append(report.Recommendations, Recommendation{
				Priority:    priority,
				Category:    bn.Type,
				Component:   bn.Component,
				Action:      bn.Recommendation,
				Expected:    "Immediate stability improvement",
			})
			priority++
		}
	}

	// Memory optimization recommendations with specific actions
	for name, comp := range report.Components {
		if comp.Memory.ContainerPct > 60 && comp.Memory.ContainerPct < 80 {
			action := fmt.Sprintf("Monitor %s during peak load (currently %.0f%%)", name, comp.Memory.ContainerPct)
			report.Recommendations = append(report.Recommendations, Recommendation{
				Priority:    priority,
				Category:    "MEMORY",
				Component:   name,
				Action:      action,
				Expected:    "Prevent OOM during benchmarks",
			})
			priority++
		}

		// GC recommendations
		if comp.GC != nil && comp.GC.FullGCCount > 0 {
			report.Recommendations = append(report.Recommendations, Recommendation{
				Priority:    priority,
				Category:    "GC",
				Component:   name,
				Action:      "Increase heap size in docker-compose.yml to avoid Full GC",
				Expected:    "Reduced latency variance by 50%+",
			})
			priority++
		}

		// Latency recommendations
		if comp.Timing.AvgLatencyMs > 50 {
			report.Recommendations = append(report.Recommendations, Recommendation{
				Priority:    priority,
				Category:    "LATENCY",
				Component:   name,
				Action:      fmt.Sprintf("Investigate high latency (%.1fms avg)", comp.Timing.AvgLatencyMs),
				Expected:    "Improved user experience and throughput",
			})
			priority++
		}
	}

	// Trace-based recommendations
	if report.TraceAnalysis != nil && len(report.TraceAnalysis.SlowOperations) > 0 {
		slowest := report.TraceAnalysis.SlowOperations[0]
		if slowest.AvgDurationMs > 100 {
			report.Recommendations = append(report.Recommendations, Recommendation{
				Priority:    priority,
				Category:    "TRACE",
				Component:   slowest.Service,
				Action:      fmt.Sprintf("Optimize slow operation '%s' (avg %.1fms)", slowest.Operation, slowest.AvgDurationMs),
				Expected:    "Significant end-to-end latency improvement",
			})
			priority++
		}
	}

	// System-wide recommendations
	if len(report.OOMEvents) == 0 && len(report.Bottlenecks) == 0 {
		report.Recommendations = append(report.Recommendations, Recommendation{
			Priority:    priority,
			Category:    "BENCHMARK",
			Component:   "system",
			Action:      "Run benchmark: reactive bench full",
			Expected:    "Baseline performance metrics",
		})
	}
}

// ============================================================================
// HEALTH CALCULATION
// ============================================================================

func calculateComponentHealth(comp *ComponentReport) {
	score := 100

	// Deduct for memory pressure
	if comp.Memory.ContainerPct > 90 {
		score -= 40
	} else if comp.Memory.ContainerPct > 75 {
		score -= 20
	} else if comp.Memory.ContainerPct > 60 {
		score -= 10
	}

	// Deduct for heap pressure
	if comp.Memory.HeapPct > 90 {
		score -= 30
	} else if comp.Memory.HeapPct > 80 {
		score -= 15
	}

	// Deduct for GC issues
	if comp.GC != nil {
		if comp.GC.FullGCCount > 0 {
			score -= 20
		}
		if comp.GC.MaxPauseMs > 500 {
			score -= 15
		} else if comp.GC.MaxPauseMs > 200 {
			score -= 5
		}
	}

	// Deduct for issues
	score -= len(comp.Issues) * 5

	if score < 0 {
		score = 0
	}
	comp.HealthScore = score
}

func calculateOverallHealth(report *DiagnosticReport) {
	if len(report.Components) == 0 {
		report.HealthScore = 0
		report.OverallHealth = "UNKNOWN"
		return
	}

	totalScore := 0
	for _, comp := range report.Components {
		totalScore += comp.HealthScore
	}
	report.HealthScore = totalScore / len(report.Components)

	// Penalize for OOM events
	report.HealthScore -= len(report.OOMEvents) * 20
	if report.HealthScore < 0 {
		report.HealthScore = 0
	}

	// Determine overall health
	switch {
	case report.HealthScore >= 80:
		report.OverallHealth = "HEALTHY"
		report.Summary = "System is healthy and ready for benchmarks"
	case report.HealthScore >= 60:
		report.OverallHealth = "WARNING"
		report.Summary = "System has some issues that should be addressed"
	case report.HealthScore >= 40:
		report.OverallHealth = "DEGRADED"
		report.Summary = "System is degraded, fix critical issues before benchmarks"
	default:
		report.OverallHealth = "CRITICAL"
		report.Summary = "System is in critical state, immediate action required"
	}
}

// ============================================================================
// OUTPUT FUNCTIONS
// ============================================================================

func outputJSON(report *DiagnosticReport) {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	encoder.Encode(report)
}

func outputHumanReadable(report *DiagnosticReport) {
	// Overall Health
	fmt.Println()
	healthColor := ""
	switch report.OverallHealth {
	case "HEALTHY":
		healthColor = "\033[32m" // Green
	case "WARNING":
		healthColor = "\033[33m" // Yellow
	case "DEGRADED":
		healthColor = "\033[33m" // Yellow
	case "CRITICAL":
		healthColor = "\033[31m" // Red
	}
	resetColor := "\033[0m"

	fmt.Printf("  Overall Health: %s%s%s (Score: %d/100)\n", healthColor, report.OverallHealth, resetColor, report.HealthScore)
	fmt.Printf("  %s\n\n", report.Summary)

	// Component breakdown
	printHeader("COMPONENT BREAKDOWN")
	fmt.Printf("  %-20s %-10s %-18s %-15s %-10s\n", "COMPONENT", "STATUS", "MEMORY", "HEAP", "HEALTH")
	fmt.Println("  " + strings.Repeat("-", 80))

	for name, comp := range report.Components {
		memStr := fmt.Sprintf("%s / %s (%.0f%%)",
			formatBytes(comp.Memory.ContainerUsed),
			formatBytes(comp.Memory.ContainerLimit),
			comp.Memory.ContainerPct)

		heapStr := "-"
		if comp.Memory.HeapMax > 0 {
			heapStr = fmt.Sprintf("%.0f%%", comp.Memory.HeapPct)
		}

		healthBar := healthBar(comp.HealthScore)

		fmt.Printf("  %-20s %-10s %-18s %-15s %s\n",
			name, comp.Status, memStr, heapStr, healthBar)
	}
	fmt.Println()

	// GC Analysis (if available)
	hasGC := false
	for _, comp := range report.Components {
		if comp.GC != nil && comp.GC.TotalPauses > 0 {
			hasGC = true
			break
		}
	}
	if hasGC {
		printHeader("GC ANALYSIS")
		for name, comp := range report.Components {
			if comp.GC != nil && comp.GC.TotalPauses > 0 {
				fmt.Printf("  %s:\n", name)
				fmt.Printf("    Total Pauses:    %d\n", comp.GC.TotalPauses)
				fmt.Printf("    Avg Pause:       %.2f ms\n", comp.GC.AvgPauseMs)
				fmt.Printf("    Max Pause:       %.2f ms\n", comp.GC.MaxPauseMs)
				fmt.Printf("    Young GC:        %d\n", comp.GC.YoungGCCount)
				fmt.Printf("    Full GC:         %d\n", comp.GC.FullGCCount)
				if comp.GC.AllocationRate != "" {
					fmt.Printf("    Allocation Rate: %s\n", comp.GC.AllocationRate)
				}
				fmt.Println()
			}
		}
	}

	// Timing Analysis
	hasTiming := false
	for _, comp := range report.Components {
		if comp.Timing.RequestsPerSec > 0 || comp.Timing.AvgLatencyMs > 0 {
			hasTiming = true
			break
		}
	}
	if hasTiming {
		printHeader("TIMING ANALYSIS")
		fmt.Printf("  %-20s %12s %12s %12s %10s\n", "COMPONENT", "REQ/SEC", "AVG (ms)", "P99 (ms)", "ERR RATE")
		fmt.Println("  " + strings.Repeat("-", 70))
		for name, comp := range report.Components {
			if comp.Timing.RequestsPerSec > 0 || comp.Timing.AvgLatencyMs > 0 {
				p99Str := "-"
				if comp.Timing.P99LatencyMs > 0 {
					p99Str = fmt.Sprintf("%.1f", comp.Timing.P99LatencyMs)
				}
				errStr := "-"
				if comp.Timing.ErrorRate > 0 {
					errStr = fmt.Sprintf("%.1f%%", comp.Timing.ErrorRate)
				}
				fmt.Printf("  %-20s %12.1f %12.2f %12s %10s\n",
					name, comp.Timing.RequestsPerSec, comp.Timing.AvgLatencyMs, p99Str, errStr)
			}
		}
		fmt.Println()
	}

	// Kafka Diagnostics
	if report.Kafka != nil {
		printHeader("KAFKA DIAGNOSTICS")
		cyan := "\033[36m"

		// Throughput metrics
		fmt.Printf("  %sThroughput:%s %.0f msg/s in, %.1f KB/s in, %.1f KB/s out\n",
			cyan, resetColor, report.Kafka.MessagesPerSecond,
			report.Kafka.BytesInPerSecond/1024, report.Kafka.BytesOutPerSecond/1024)
		fmt.Printf("  %sTotal Messages:%s %d across all topics\n\n", cyan, resetColor, report.Kafka.TotalMessages)

		// Topics with partition distribution
		if len(report.Kafka.Topics) > 0 {
			fmt.Println("  ── Topics & Partition Distribution ──")
			for _, topic := range report.Kafka.Topics {
				fmt.Printf("\n  %s%s%s (partitions: %d, replication: %d, messages: %d)\n",
					cyan, topic.Name, resetColor, topic.Partitions, topic.ReplicationFactor, topic.MessageCount)

				if len(topic.PartitionStats) > 0 {
					// Calculate total written for balance detection
					var totalWritten int64
					for _, ps := range topic.PartitionStats {
						totalWritten += ps.EndOffset
					}
					avgWritten := float64(totalWritten) / float64(len(topic.PartitionStats))

					fmt.Printf("  %-12s %15s %12s %12s\n", "PARTITION", "TOTAL WRITTEN", "PENDING", "BALANCE")
					fmt.Println("  " + strings.Repeat("-", 55))
					for _, ps := range topic.PartitionStats {
						// Color-code based on message distribution (using EndOffset which is total written)
						balanceStr := "OK"
						balanceColor := ""
						if avgWritten > 0 {
							ratio := float64(ps.EndOffset) / avgWritten
							if ratio > 1.3 {
								balanceColor = "\033[33m" // Yellow for hot partition
								balanceStr = fmt.Sprintf("HOT (+%.0f%%)", (ratio-1)*100)
							} else if ratio < 0.7 && ps.EndOffset > 0 {
								balanceColor = "\033[36m" // Cyan for cold partition
								balanceStr = fmt.Sprintf("COLD (-%.0f%%)", (1-ratio)*100)
							}
						}
						pending := ps.EndOffset - ps.BeginOffset
						fmt.Printf("  %-12d %15d %12d %s%12s%s\n",
							ps.Partition, ps.EndOffset, pending, balanceColor, balanceStr, resetColor)
					}
				}
			}
			fmt.Println()
		}

		// Partition balance summary
		if report.Kafka.PartitionBalance != nil {
			pb := report.Kafka.PartitionBalance
			balanceColor := "\033[32m" // Green
			balanceStatus := "BALANCED"
			if pb.Skewed {
				balanceColor = "\033[33m" // Yellow
				balanceStatus = "SKEWED"
			}
			if pb.BalanceScore < 0.5 {
				balanceColor = "\033[31m" // Red
				balanceStatus = "HEAVILY SKEWED"
			}

			fmt.Println("  ── Partition Balance ──")
			fmt.Printf("  Status: %s%s%s (score: %.2f)\n", balanceColor, balanceStatus, resetColor, pb.BalanceScore)
			fmt.Printf("  Distribution: min=%d, max=%d, stddev=%.0f\n",
				pb.MinMessagesOnPart, pb.MaxMessagesOnPart, pb.StdDeviation)
			if pb.MinMessagesOnPart > 0 {
				ratio := float64(pb.MaxMessagesOnPart) / float64(pb.MinMessagesOnPart)
				fmt.Printf("  Max/Min Ratio: %.1fx\n", ratio)
			}
			fmt.Println()
		}

		// Consumer Groups
		if len(report.Kafka.ConsumerGroups) > 0 {
			fmt.Println("  ── Consumer Groups ──")
			fmt.Printf("  %-25s %-15s %10s %12s\n", "GROUP", "STATE", "MEMBERS", "TOTAL LAG")
			fmt.Println("  " + strings.Repeat("-", 65))
			for _, cg := range report.Kafka.ConsumerGroups {
				stateColor := "\033[32m" // Green
				if cg.State == "HIGH_LAG" {
					stateColor = "\033[31m" // Red
				} else if cg.State == "MODERATE_LAG" {
					stateColor = "\033[33m" // Yellow
				}
				fmt.Printf("  %-25s %s%-15s%s %10d %12d\n",
					cg.GroupID, stateColor, cg.State, resetColor, cg.Members, cg.TotalLag)

				// Show partition-level lag if there's any
				if cg.TotalLag > 0 && len(cg.PartitionLag) > 0 {
					fmt.Println("    Partition lag breakdown:")
					for _, pl := range cg.PartitionLag {
						if pl.Lag > 0 {
							lagColor := ""
							if pl.Lag > 1000 {
								lagColor = "\033[31m" // Red
							} else if pl.Lag > 100 {
								lagColor = "\033[33m" // Yellow
							}
							fmt.Printf("      %s partition %d: %sLAG=%d%s (offset: %d/%d)\n",
								pl.Topic, pl.Partition, lagColor, pl.Lag, resetColor, pl.CurrentOffset, pl.LogEndOffset)
						}
					}
				}
			}
			fmt.Println()
		}

		// Kafka Issues
		if len(report.Kafka.Issues) > 0 {
			fmt.Println("  ── Kafka Issues ──")
			for _, issue := range report.Kafka.Issues {
				fmt.Printf("  \033[33m⚠\033[0m %s\n", issue)
			}
			fmt.Println()
		}
	}

	// Trace Analysis (from Jaeger)
	if report.TraceAnalysis != nil && len(report.TraceAnalysis.SlowOperations) > 0 {
		printHeader("TRACE ANALYSIS (Slow Operations)")
		fmt.Printf("  Recent traces analyzed: %d\n\n", report.TraceAnalysis.TotalTraces)
		fmt.Printf("  %-30s %-20s %12s %12s %8s\n", "OPERATION", "SERVICE", "AVG (ms)", "MAX (ms)", "COUNT")
		fmt.Println("  " + strings.Repeat("-", 85))
		for i, op := range report.TraceAnalysis.SlowOperations {
			if i >= 5 {
				break
			}
			opName := op.Operation
			if len(opName) > 28 {
				opName = opName[:28] + ".."
			}
			fmt.Printf("  %-30s %-20s %12.1f %12.1f %8d\n",
				opName, op.Service, op.AvgDurationMs, op.MaxDurationMs, op.Count)
		}
		fmt.Println()

		// Service breakdown
		if len(report.TraceAnalysis.ServiceBreakdown) > 0 {
			fmt.Println("  Service timing breakdown:")
			for svc, timing := range report.TraceAnalysis.ServiceBreakdown {
				fmt.Printf("    %s: avg=%.1fms, max=%.1fms (%d spans)\n",
					svc, timing.AvgDurationMs, timing.MaxDurationMs, timing.SpanCount)
			}
			fmt.Println()
		}
	}

	// Last Benchmark Summary
	if report.LastBenchmark != nil {
		printHeader("LAST BENCHMARK RESULTS")
		cyan := "\033[36m"
		fmt.Printf("  %sTimestamp:%s %s\n", cyan, resetColor, report.LastBenchmark.Timestamp)
		fmt.Printf("  %sThroughput:%s %.0f ops/s avg, %.0f ops/s peak\n",
			cyan, resetColor, report.LastBenchmark.AvgThroughput, report.LastBenchmark.PeakThroughput)
		fmt.Printf("  %sLatency:%s %.1fms avg, %.1fms P99\n",
			cyan, resetColor, report.LastBenchmark.AvgLatencyMs, report.LastBenchmark.P99LatencyMs)
		fmt.Printf("  %sSuccess Rate:%s %.1f%% (%d ops)\n",
			cyan, resetColor, report.LastBenchmark.SuccessRate, report.LastBenchmark.TotalOps)

		// Stability indicator
		stabilityColor := "\033[32m" // Green
		stabilityLabel := "STABLE"
		if report.LastBenchmark.ThroughputStability < 0.7 {
			stabilityColor = "\033[33m" // Yellow
			stabilityLabel = "UNSTABLE"
		}
		if report.LastBenchmark.ThroughputStability < 0.5 {
			stabilityColor = "\033[31m" // Red
			stabilityLabel = "VERY UNSTABLE"
		}
		fmt.Printf("  %sStability:%s %s%.0f%%%s (%s)\n",
			cyan, resetColor, stabilityColor, report.LastBenchmark.ThroughputStability*100, resetColor, stabilityLabel)
		fmt.Printf("  %sResources:%s %.0f%% CPU, %.0f%% peak memory\n\n",
			cyan, resetColor, report.LastBenchmark.AvgCPU, report.LastBenchmark.PeakMemory)
	}

	// Bottlenecks
	if len(report.Bottlenecks) > 0 {
		printHeader("BOTTLENECKS IDENTIFIED")
		for i, bn := range report.Bottlenecks {
			if i >= 5 {
				fmt.Printf("  ... and %d more\n", len(report.Bottlenecks)-5)
				break
			}
			severityColor := "\033[33m" // Yellow
			if bn.Severity == "CRITICAL" {
				severityColor = "\033[31m" // Red
			}
			fmt.Printf("  %s[%s]%s %s (%s)\n", severityColor, bn.Severity, resetColor, bn.Description, bn.Component)
			fmt.Printf("    Impact: %s\n", bn.Impact)
			fmt.Printf("    Fix: %s\n\n", bn.Recommendation)
		}
	}

	// OOM Events
	if len(report.OOMEvents) > 0 {
		printHeader("OOM EVENTS DETECTED")
		for _, oom := range report.OOMEvents {
			fmt.Printf("  \033[31mCRITICAL\033[0m: %s was OOM killed (restarts: %d)\n",
				oom.Container, oom.RestartCount)
		}
		fmt.Println()
	}

	// Optimization Opportunities
	if len(report.Optimizations) > 0 {
		printHeader("OPTIMIZATION OPPORTUNITIES")
		green := "\033[32m"
		cyan := "\033[36m"
		for i, opt := range report.Optimizations {
			if i >= 5 {
				fmt.Printf("  ... and %d more optimizations available\n\n", len(report.Optimizations)-5)
				break
			}
			fmt.Printf("  %s%d. [%s] %s%s\n", green, i+1, opt.Category, opt.Component, resetColor)
			fmt.Printf("     Current:   %s\n", opt.Current)
			fmt.Printf("     Suggested: %s\n", opt.Suggested)
			fmt.Printf("     Impact:    %s\n", opt.Impact)
			fmt.Printf("     %sCommand:%s  %s\n\n", cyan, resetColor, opt.Command)
		}
	}

	// Recommendations
	if len(report.Recommendations) > 0 {
		printHeader("RECOMMENDATIONS")
		for i, rec := range report.Recommendations {
			if i >= 5 {
				break
			}
			fmt.Printf("  %d. [%s] %s\n", rec.Priority, rec.Component, rec.Action)
			fmt.Printf("     Expected: %s\n\n", rec.Expected)
		}
	}

	// Next Steps
	printHeader("NEXT STEPS")
	if report.OverallHealth == "HEALTHY" {
		fmt.Println("  1. Run benchmark:  reactive bench full")
		fmt.Println("  2. Check results:  open reports/full/index.html")
	} else {
		fmt.Println("  1. Fix critical issues first")
		if len(report.Recommendations) > 0 {
			fmt.Printf("  2. %s\n", report.Recommendations[0].Action)
		}
		fmt.Println("  3. Re-run diagnostics: reactive diagnose")
	}
	fmt.Println()
}

func formatBytes(bytes int64) string {
	if bytes >= 1024*1024*1024 {
		return fmt.Sprintf("%.1fG", float64(bytes)/(1024*1024*1024))
	}
	if bytes >= 1024*1024 {
		return fmt.Sprintf("%.0fM", float64(bytes)/(1024*1024))
	}
	return fmt.Sprintf("%.0fK", float64(bytes)/1024)
}

func healthBar(score int) string {
	width := 10
	filled := score * width / 100
	if filled > width {
		filled = width
	}
	bar := strings.Repeat("▓", filled) + strings.Repeat("░", width-filled)
	color := "\033[32m" // Green
	if score < 60 {
		color = "\033[33m" // Yellow
	}
	if score < 40 {
		color = "\033[31m" // Red
	}
	return fmt.Sprintf("%s%s\033[0m %d%%", color, bar, score)
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
