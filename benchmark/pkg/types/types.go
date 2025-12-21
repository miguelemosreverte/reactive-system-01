package types

import "time"

// ComponentID identifies a benchmark component
type ComponentID string

const (
	ComponentHTTP    ComponentID = "http"
	ComponentKafka   ComponentID = "kafka"
	ComponentFlink   ComponentID = "flink"
	ComponentDrools  ComponentID = "drools"
	ComponentGateway ComponentID = "gateway"
	ComponentFull    ComponentID = "full"
)

var AllComponents = []ComponentID{
	ComponentHTTP,
	ComponentKafka,
	ComponentFlink,
	ComponentDrools,
	ComponentGateway,
	ComponentFull,
}

// Config holds benchmark configuration
type Config struct {
	DurationMs       int64  `json:"durationMs"`
	TargetEventCount int    `json:"targetEventCount"`
	WarmupMs         int64  `json:"warmupMs"`
	CooldownMs       int64  `json:"cooldownMs"`
	Concurrency      int    `json:"concurrency"`
	BatchSize        int    `json:"batchSize"`
	GatewayURL       string `json:"gatewayUrl"`
	DroolsURL        string `json:"droolsUrl"`
	KafkaBrokers     string `json:"kafkaBrokers"`
}

// DefaultConfig returns default benchmark configuration
func DefaultConfig() Config {
	return Config{
		DurationMs:       30000,
		TargetEventCount: 0,
		WarmupMs:         3000,
		CooldownMs:       2000,
		Concurrency:      8,
		BatchSize:        100,
		GatewayURL:       "http://gateway:3000",
		DroolsURL:        "http://drools:8080",
		KafkaBrokers:     "kafka:29092",
	}
}

// LatencyStats holds latency percentiles
type LatencyStats struct {
	Min int64 `json:"min"`
	Max int64 `json:"max"`
	Avg int64 `json:"avg"`
	P50 int64 `json:"p50"`
	P95 int64 `json:"p95"`
	P99 int64 `json:"p99"`
}

// ComponentTiming holds per-component timing breakdown
type ComponentTiming struct {
	GatewayMs int64 `json:"gatewayMs"`
	KafkaMs   int64 `json:"kafkaMs"`
	FlinkMs   int64 `json:"flinkMs"`
	DroolsMs  int64 `json:"droolsMs"`
}

// JaegerSpan represents a span from Jaeger
type JaegerSpan struct {
	TraceID       string                 `json:"traceID"`
	SpanID        string                 `json:"spanID"`
	OperationName string                 `json:"operationName"`
	StartTime     int64                  `json:"startTime"` // microseconds
	Duration      int64                  `json:"duration"`  // microseconds
	ProcessID     string                 `json:"processID"`
	Tags          []map[string]interface{} `json:"tags,omitempty"`
	References    []map[string]interface{} `json:"references,omitempty"`
}

// JaegerProcess represents a process in Jaeger trace
type JaegerProcess struct {
	ServiceName string `json:"serviceName"`
}

// JaegerTrace represents a trace from Jaeger
type JaegerTrace struct {
	TraceID   string                   `json:"traceID"`
	Spans     []JaegerSpan             `json:"spans"`
	Processes map[string]JaegerProcess `json:"processes"`
}

// LokiLogEntry represents a log entry from Loki
type LokiLogEntry struct {
	Timestamp string                 `json:"timestamp"`
	Line      string                 `json:"line"`
	Labels    map[string]string      `json:"labels"`
	Fields    map[string]interface{} `json:"fields,omitempty"`
}

// TraceData holds combined trace and log data
type TraceData struct {
	Trace *JaegerTrace   `json:"trace,omitempty"`
	Logs  []LokiLogEntry `json:"logs,omitempty"`
}

// SampleEvent holds a sample event for reporting
type SampleEvent struct {
	ID              string           `json:"id"`
	TraceID         string           `json:"traceId"`         // Our custom app.traceId for log correlation
	OtelTraceID     string           `json:"otelTraceId"`     // OpenTelemetry trace ID for Jaeger lookup
	Timestamp       int64            `json:"timestamp"`
	LatencyMs       int64            `json:"latencyMs"`
	Status          string           `json:"status"` // success, error, timeout
	Error           string           `json:"error,omitempty"`
	ComponentTiming *ComponentTiming `json:"componentTiming,omitempty"`
	TraceData       *TraceData       `json:"traceData,omitempty"`
}

// Result holds benchmark results
type Result struct {
	Component            ComponentID    `json:"component"`
	Name                 string         `json:"name"`
	Description          string         `json:"description"`
	StartTime            time.Time      `json:"startTime"`
	EndTime              time.Time      `json:"endTime"`
	DurationMs           int64          `json:"durationMs"`
	TotalOperations      int64          `json:"totalOperations"`
	SuccessfulOperations int64          `json:"successfulOperations"`
	FailedOperations     int64          `json:"failedOperations"`
	PeakThroughput       int64          `json:"peakThroughput"`
	AvgThroughput        int64          `json:"avgThroughput"`
	ThroughputTimeline   []int64        `json:"throughputTimeline"`
	Latency              LatencyStats   `json:"latency"`
	CPUTimeline          []float64      `json:"cpuTimeline"`
	MemoryTimeline       []float64      `json:"memoryTimeline"`
	PeakCPU              float64        `json:"peakCpu"`
	PeakMemory           float64        `json:"peakMemory"`
	AvgCPU               float64        `json:"avgCpu"`
	AvgMemory            float64        `json:"avgMemory"`
	ComponentTiming      *ComponentTiming `json:"componentTiming,omitempty"`
	SampleEvents         []SampleEvent  `json:"sampleEvents"`
	Status               string         `json:"status"` // completed, stopped, error
	ErrorMessage         string         `json:"errorMessage,omitempty"`
}

// Progress holds real-time benchmark progress
type Progress struct {
	OperationsCompleted int64   `json:"operationsCompleted"`
	CurrentThroughput   int64   `json:"currentThroughput"`
	ElapsedMs           int64   `json:"elapsedMs"`
	RemainingMs         int64   `json:"remainingMs"`
	PercentComplete     int     `json:"percentComplete"`
	CurrentCPU          float64 `json:"currentCpu"`
	CurrentMemory       float64 `json:"currentMemory"`
}

// Benchmark is the interface all benchmarks implement
type Benchmark interface {
	ID() ComponentID
	Name() string
	Description() string
	Run(config Config) (*Result, error)
	Stop()
	IsRunning() bool
}
