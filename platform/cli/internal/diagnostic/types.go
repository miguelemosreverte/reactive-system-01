package diagnostic

// DiagnosticSnapshot represents the complete diagnostic state of a component
type DiagnosticSnapshot struct {
	Component   string `json:"component"`
	InstanceID  string `json:"instance_id"`
	TimestampMs int64  `json:"timestamp_ms"`
	UptimeMs    int64  `json:"uptime_ms"`
	Version     string `json:"version"`

	// THE TWO CORE METRICS
	Throughput ThroughputMetrics `json:"throughput"`
	Latency    LatencyMetrics    `json:"latency"`

	// EVERYTHING ELSE EXPLAINS WHY THROUGHPUT/LATENCY ARE WHAT THEY ARE
	Saturation   ResourceSaturation   `json:"saturation"`
	Trends       RateOfChange         `json:"trends"`
	Contention   ContentionAnalysis   `json:"contention"`
	Memory       MemoryPressure       `json:"memory"`
	GC           GCAnalysis           `json:"gc"`
	Stages       []PipelineStage      `json:"stages"`
	Dependencies []DependencyAnalysis `json:"dependencies"`
	Errors       ErrorAnalysis        `json:"errors"`
	Logs         LogAnalysis          `json:"logs"`
	Capacity     CapacityAnalysis     `json:"capacity"`
	History      []HistoricalBucket   `json:"history"`
	SampledRequests []RequestTrace    `json:"sampled_requests"`
}

// ThroughputMetrics - events processed per unit time
type ThroughputMetrics struct {
	EventsPerSecond         float64 `json:"events_per_second"`
	BytesPerSecond          int64   `json:"bytes_per_second"`
	EventsPerSecondCapacity float64 `json:"events_per_second_capacity"`
	CapacityUtilizationPct  float64 `json:"capacity_utilization_percent"`
	BatchSizeAvg            float64 `json:"batch_size_avg"`
	BatchSizeP99            int64   `json:"batch_size_p99"`
	BatchesPerSecond        float64 `json:"batches_per_second"`
	BatchEfficiencyPct      float64 `json:"batch_efficiency_percent"`
	ParallelismActive       int     `json:"parallelism_active"`
	ParallelismMax          int     `json:"parallelism_max"`
	QueueDepth              int64   `json:"queue_depth"`
	QueueCapacity           int64   `json:"queue_capacity"`
	RejectedCount           int64   `json:"rejected_count"`
	DroppedCount            int64   `json:"dropped_count"`
}

// LatencyMetrics - time breakdown for processing
type LatencyMetrics struct {
	TotalMsP50      float64            `json:"total_ms_p50"`
	TotalMsP95      float64            `json:"total_ms_p95"`
	TotalMsP99      float64            `json:"total_ms_p99"`
	TotalMsMax      float64            `json:"total_ms_max"`
	CPUTimeMs       float64            `json:"cpu_time_ms"`
	QueueWaitMs     float64            `json:"queue_wait_ms"`
	LockWaitMs      float64            `json:"lock_wait_ms"`
	GCPauseMs       float64            `json:"gc_pause_ms"`
	NetworkMs       float64            `json:"network_ms"`
	SerializationMs float64            `json:"serialization_ms"`
	DeserializationMs float64          `json:"deserialization_ms"`
	DependencyWaitMs float64           `json:"dependency_wait_ms"`
	BreakdownPercent map[string]float64 `json:"breakdown_percent"`
}

// ResourceSaturation - how full are our resources
type ResourceSaturation struct {
	CPUPercent            float64 `json:"cpu_percent"`
	HeapUsedPercent       float64 `json:"heap_used_percent"`
	HeapUsedBytes         int64   `json:"heap_used_bytes"`
	HeapMaxBytes          int64   `json:"heap_max_bytes"`
	OldGenPercent         float64 `json:"old_gen_percent"`
	OldGenBytes           int64   `json:"old_gen_bytes"`
	OldGenMaxBytes        int64   `json:"old_gen_max_bytes"`
	ThreadPoolActive      int     `json:"thread_pool_active"`
	ThreadPoolMax         int     `json:"thread_pool_max"`
	ThreadPoolQueueDepth  int64   `json:"thread_pool_queue_depth"`
	ConnectionPoolActive  int     `json:"connection_pool_active"`
	ConnectionPoolMax     int     `json:"connection_pool_max"`
	BufferPoolUsedPercent float64 `json:"buffer_pool_used_percent"`
	FileDescriptorsUsed   int     `json:"file_descriptors_used"`
	FileDescriptorsMax    int     `json:"file_descriptors_max"`
}

// RateOfChange - trends that predict future problems
type RateOfChange struct {
	ThroughputTrend            float64 `json:"throughput_trend"`
	LatencyTrend               float64 `json:"latency_trend"`
	HeapGrowthBytesPerSec      int64   `json:"heap_growth_bytes_per_sec"`
	OldGenGrowthBytesPerSec    int64   `json:"old_gen_growth_bytes_per_sec"`
	QueueDepthTrend            float64 `json:"queue_depth_trend"`
	ErrorRateTrend             float64 `json:"error_rate_trend"`
	GCFrequencyTrend           float64 `json:"gc_frequency_trend"`
	EstimatedHeapExhaustionSec int64   `json:"estimated_heap_exhaustion_sec"`
	EstimatedOldGenExhaustionSec int64 `json:"estimated_old_gen_exhaustion_sec"`
}

// ContentionAnalysis - what's blocking us
type ContentionAnalysis struct {
	LockContentionPercent float64     `json:"lock_contention_percent"`
	MostContendedLocks    []LockInfo  `json:"most_contended_locks"`
	BlockedThreads        int         `json:"blocked_threads"`
	WaitingThreads        int         `json:"waiting_threads"`
	DeadlockDetected      bool        `json:"deadlock_detected"`
	IOWaitPercent         float64     `json:"io_wait_percent"`
	QueueBlockingPercent  float64     `json:"queue_blocking_percent"`
}

// LockInfo - details about a contended lock
type LockInfo struct {
	Name              string  `json:"name"`
	ContentionPercent float64 `json:"contention_percent"`
	WaitCount         int64   `json:"wait_count"`
	AvgWaitMs         float64 `json:"avg_wait_ms"`
}

// MemoryPressure - detailed memory state
type MemoryPressure struct {
	HeapUsedBytes              int64        `json:"heap_used_bytes"`
	HeapCommittedBytes         int64        `json:"heap_committed_bytes"`
	HeapMaxBytes               int64        `json:"heap_max_bytes"`
	NonHeapUsedBytes           int64        `json:"non_heap_used_bytes"`
	DirectBufferUsedBytes      int64        `json:"direct_buffer_used_bytes"`
	DirectBufferMaxBytes       int64        `json:"direct_buffer_max_bytes"`
	AllocationRateBytesPerSec  int64        `json:"allocation_rate_bytes_per_sec"`
	PromotionRateBytesPerSec   int64        `json:"promotion_rate_bytes_per_sec"`
	SurvivorSpaceUsedPercent   float64      `json:"survivor_space_used_percent"`
	TenuringThreshold          int          `json:"tenuring_threshold"`
	LargeObjectAllocsPerSec    int64        `json:"large_object_allocations_per_sec"`
	FragmentationPercent       float64      `json:"fragmentation_percent"`
	NativeMemoryUsedBytes      int64        `json:"native_memory_used_bytes"`
	MemoryPools                []MemoryPool `json:"memory_pools"`
}

// MemoryPool - individual memory pool status
type MemoryPool struct {
	Name        string  `json:"name"`
	UsedBytes   int64   `json:"used_bytes"`
	MaxBytes    int64   `json:"max_bytes"`
	UsedPercent float64 `json:"used_percent"`
}

// GCAnalysis - garbage collection behavior
type GCAnalysis struct {
	YoungGCCount         int64              `json:"young_gc_count"`
	YoungGCTimeMs        int64              `json:"young_gc_time_ms"`
	OldGCCount           int64              `json:"old_gc_count"`
	OldGCTimeMs          int64              `json:"old_gc_time_ms"`
	GCOverheadPercent    float64            `json:"gc_overhead_percent"`
	AvgYoungGCPauseMs    float64            `json:"avg_young_gc_pause_ms"`
	AvgOldGCPauseMs      float64            `json:"avg_old_gc_pause_ms"`
	MaxGCPauseMs         float64            `json:"max_gc_pause_ms"`
	GCFrequencyPerMin    float64            `json:"gc_frequency_per_min"`
	TimeSinceLastGCMs    int64              `json:"time_since_last_gc_ms"`
	ConsecutiveFullGCs   int                `json:"consecutive_full_gcs"`
	GCCPUPercent         float64            `json:"gc_cpu_percent"`
	ObjectsPromotedPerGC int64              `json:"objects_promoted_per_gc"`
	GCAlgorithm          string             `json:"gc_algorithm"`
	GCCauseHistogram     map[string]int64   `json:"gc_cause_histogram"`
}

// PipelineStage - per-stage processing metrics
type PipelineStage struct {
	Name               string  `json:"name"`
	EventsProcessed    int64   `json:"events_processed"`
	EventsPerSecond    float64 `json:"events_per_second"`
	LatencyMsP50       float64 `json:"latency_ms_p50"`
	LatencyMsP99       float64 `json:"latency_ms_p99"`
	QueueDepth         int64   `json:"queue_depth"`
	IsBottleneck       bool    `json:"is_bottleneck"`
	SaturationPercent  float64 `json:"saturation_percent"`
	ErrorCount         int64   `json:"error_count"`
	BackpressureApplied bool   `json:"backpressure_applied"`
}

// DependencyAnalysis - external dependency health
type DependencyAnalysis struct {
	Name               string  `json:"name"`
	Type               string  `json:"type"`
	LatencyMsP50       float64 `json:"latency_ms_p50"`
	LatencyMsP99       float64 `json:"latency_ms_p99"`
	CallsPerSecond     float64 `json:"calls_per_second"`
	ErrorRatePercent   float64 `json:"error_rate_percent"`
	CircuitBreakerState string `json:"circuit_breaker_state"`
	ConnectionPoolUsed int     `json:"connection_pool_used"`
	ConnectionPoolMax  int     `json:"connection_pool_max"`
	SaturationPercent  float64 `json:"saturation_percent"`
	LastError          *string `json:"last_error"`
	LastErrorTimeMs    int64   `json:"last_error_time_ms"`
}

// ErrorAnalysis - error patterns and crash risk
type ErrorAnalysis struct {
	TotalErrorCount  int64            `json:"total_error_count"`
	ErrorRatePercent float64          `json:"error_rate_percent"`
	ErrorsByType     map[string]int64 `json:"errors_by_type"`
	ErrorsByStage    map[string]int64 `json:"errors_by_stage"`
	RecentErrors     []ErrorInfo      `json:"recent_errors"`
	CrashIndicators  CrashIndicators  `json:"crash_indicators"`
	ErrorPatterns    []ErrorPattern   `json:"error_patterns"`
}

// ErrorInfo - individual error details
type ErrorInfo struct {
	Type           string   `json:"type"`
	Message        string   `json:"message"`
	TimestampMs    int64    `json:"timestamp_ms"`
	StackTraceHash string   `json:"stack_trace_hash"`
	Stage          string   `json:"stage"`
	TraceID        string   `json:"trace_id"`
	SpanID         string   `json:"span_id"`
	Attributes     map[string]string `json:"attributes"`
}

// ErrorPattern - detected error patterns for AI analysis
type ErrorPattern struct {
	Pattern        string  `json:"pattern"`
	Count          int64   `json:"count"`
	FirstSeenMs    int64   `json:"first_seen_ms"`
	LastSeenMs     int64   `json:"last_seen_ms"`
	AffectedStages []string `json:"affected_stages"`
	SampleTraceIDs []string `json:"sample_trace_ids"`
}

// CrashIndicators - signs of imminent failure
type CrashIndicators struct {
	OOMRisk              bool    `json:"oom_risk"`
	OOMRiskPercent       float64 `json:"oom_risk_percent"`
	GCThrashing          bool    `json:"gc_thrashing"`
	MemoryLeakSuspected  bool    `json:"memory_leak_suspected"`
	ThreadExhaustionRisk bool    `json:"thread_exhaustion_risk"`
}

// CapacityAnalysis - raw data about headroom and limiting factors
type CapacityAnalysis struct {
	CurrentThroughput        float64          `json:"current_throughput"`
	MaxObservedThroughput    float64          `json:"max_observed_throughput"`
	TheoreticalMaxThroughput float64          `json:"theoretical_max_throughput"`
	HeadroomPercent          float64          `json:"headroom_percent"`
	LimitingFactors          []LimitingFactor `json:"limiting_factors"`
}

// LimitingFactor - what's limiting capacity (pure data, no recommendations)
type LimitingFactor struct {
	Factor         string  `json:"factor"`
	ImpactPercent  float64 `json:"impact_percent"`
	CurrentValue   float64 `json:"current_value"`
	ThresholdValue float64 `json:"threshold_value"`
	Unit           string  `json:"unit"`
}

// HistoricalBucket - time-windowed metrics
type HistoricalBucket struct {
	BucketStartMs     int64   `json:"bucket_start_ms"`
	BucketDurationMs  int64   `json:"bucket_duration_ms"`
	AvgThroughput     float64 `json:"avg_throughput"`
	AvgLatencyMs      float64 `json:"avg_latency_ms"`
	MaxLatencyMs      float64 `json:"max_latency_ms"`
	ErrorCount        int64   `json:"error_count"`
	HeapUsedAvgPercent float64 `json:"heap_used_avg_percent"`
}

// RequestTrace - sampled request with detailed timing and span breakdown
type RequestTrace struct {
	TraceID             string             `json:"trace_id"`
	ParentSpanID        string             `json:"parent_span_id"`
	TotalTimeMs         float64            `json:"total_time_ms"`
	TimeBreakdown       map[string]float64 `json:"time_breakdown"`
	GCPausesEncountered int                `json:"gc_pauses_encountered"`
	Retries             int                `json:"retries"`
	FinalStatus         string             `json:"final_status"`
	Spans               []SpanInfo         `json:"spans"`
	Attributes          map[string]string  `json:"attributes"`
	Logs                []TraceLog         `json:"logs"`
}

// SpanInfo - individual span within a trace
type SpanInfo struct {
	SpanID       string             `json:"span_id"`
	ParentSpanID string             `json:"parent_span_id"`
	Name         string             `json:"name"`
	Service      string             `json:"service"`
	StartTimeMs  int64              `json:"start_time_ms"`
	DurationMs   float64            `json:"duration_ms"`
	Status       string             `json:"status"`
	Attributes   map[string]string  `json:"attributes"`
	Events       []SpanEvent        `json:"events"`
}

// SpanEvent - event within a span (e.g., exception, log)
type SpanEvent struct {
	Name        string            `json:"name"`
	TimestampMs int64             `json:"timestamp_ms"`
	Attributes  map[string]string `json:"attributes"`
}

// TraceLog - log entry associated with a trace
type TraceLog struct {
	TimestampMs int64             `json:"timestamp_ms"`
	Level       string            `json:"level"`
	Message     string            `json:"message"`
	Logger      string            `json:"logger"`
	ThreadName  string            `json:"thread_name"`
	Attributes  map[string]string `json:"attributes"`
}

// LogAnalysis - aggregated log information
type LogAnalysis struct {
	TotalLogCount    int64            `json:"total_log_count"`
	LogsByLevel      map[string]int64 `json:"logs_by_level"`
	LogsByLogger     map[string]int64 `json:"logs_by_logger"`
	RecentLogs       []LogEntry       `json:"recent_logs"`
	LogPatterns      []LogPattern     `json:"log_patterns"`
	WarningsPerMin   float64          `json:"warnings_per_min"`
	ErrorsPerMin     float64          `json:"errors_per_min"`
}

// LogEntry - individual log entry
type LogEntry struct {
	TimestampMs int64             `json:"timestamp_ms"`
	Level       string            `json:"level"`
	Message     string            `json:"message"`
	Logger      string            `json:"logger"`
	ThreadName  string            `json:"thread_name"`
	TraceID     string            `json:"trace_id"`
	SpanID      string            `json:"span_id"`
	Attributes  map[string]string `json:"attributes"`
	Exception   *ExceptionInfo    `json:"exception,omitempty"`
}

// ExceptionInfo - exception details from logs
type ExceptionInfo struct {
	Type       string   `json:"type"`
	Message    string   `json:"message"`
	StackTrace []string `json:"stack_trace"`
}

// LogPattern - detected log patterns
type LogPattern struct {
	Pattern     string   `json:"pattern"`
	Count       int64    `json:"count"`
	Level       string   `json:"level"`
	Loggers     []string `json:"loggers"`
	FirstSeenMs int64    `json:"first_seen_ms"`
	LastSeenMs  int64    `json:"last_seen_ms"`
	SampleMsg   string   `json:"sample_message"`
}
