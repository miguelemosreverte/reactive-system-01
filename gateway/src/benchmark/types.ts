/**
 * Benchmark System Types
 *
 * Standard interfaces for the DRY benchmark architecture.
 * All component benchmarks implement the same BenchmarkInterface
 * and return the same BenchmarkResult format.
 */

// ============================================================================
// Component Identifiers
// ============================================================================

export type BenchmarkComponentId =
  | 'http'
  | 'kafka'
  | 'flink'
  | 'drools'
  | 'gateway'
  | 'full';

export const BENCHMARK_COMPONENTS: BenchmarkComponentId[] = [
  'http',
  'kafka',
  'flink',
  'drools',
  'gateway',
  'full'
];

// ============================================================================
// Configuration
// ============================================================================

export interface BenchmarkConfig {
  /** Duration to run the benchmark in milliseconds */
  durationMs: number;
  /** Target number of events (0 = use duration instead) */
  targetEventCount: number;
  /** Warmup period before measuring in milliseconds */
  warmupMs: number;
  /** Cooldown before starting in milliseconds */
  cooldownMs: number;
  /** Number of concurrent sessions/connections */
  concurrency: number;
  /** Batch size for bulk operations */
  batchSize: number;
  /** Component-specific options */
  options?: Record<string, unknown>;
}

export const DEFAULT_BENCHMARK_CONFIG: BenchmarkConfig = {
  durationMs: 30000,
  targetEventCount: 0,
  warmupMs: 3000,
  cooldownMs: 2000,
  concurrency: 8,
  batchSize: 100,
};

// ============================================================================
// Results
// ============================================================================

export interface LatencyStats {
  min: number;
  max: number;
  avg: number;
  p50: number;
  p95: number;
  p99: number;
}

export interface ComponentTimingStats {
  gatewayMs: number;
  kafkaMs: number;
  flinkMs: number;
  droolsMs: number;
}

export interface BenchmarkResult {
  /** Which component was benchmarked */
  component: BenchmarkComponentId;
  /** Human-readable name */
  name: string;
  /** Description of what was tested */
  description: string;

  /** When benchmark started */
  startTime: Date;
  /** When benchmark ended */
  endTime: Date;
  /** Total duration in milliseconds */
  durationMs: number;

  // Throughput metrics
  /** Total operations attempted */
  totalOperations: number;
  /** Operations that succeeded */
  successfulOperations: number;
  /** Operations that failed */
  failedOperations: number;
  /** Peak throughput (ops/sec) */
  peakThroughput: number;
  /** Average throughput (ops/sec) */
  avgThroughput: number;
  /** Per-second throughput samples for charts */
  throughputTimeline: number[];

  // Latency metrics (milliseconds)
  latency: LatencyStats;

  // Resource metrics
  /** Per-second CPU usage samples for charts */
  cpuTimeline: number[];
  /** Per-second memory usage samples for charts */
  memoryTimeline: number[];
  /** Peak CPU percentage */
  peakCpu: number;
  /** Peak memory percentage */
  peakMemory: number;
  /** Average CPU percentage */
  avgCpu: number;
  /** Average memory percentage */
  avgMemory: number;

  // Component timing (optional, for multi-component benchmarks)
  componentTiming?: ComponentTimingStats;

  // Sample events for report
  sampleEvents: SampleEvent[];

  // Status
  status: 'completed' | 'stopped' | 'error';
  errorMessage?: string;
}

// ============================================================================
// Sample Events
// ============================================================================

export interface SampleEvent {
  /** Event/operation ID */
  id: string;
  /** OpenTelemetry trace ID */
  traceId: string;
  /** When the operation was initiated */
  timestamp: number;
  /** Total latency in milliseconds */
  latencyMs: number;
  /** Outcome of the operation */
  status: 'success' | 'error' | 'timeout';
  /** Error message if status is 'error' */
  error?: string;
  /** Per-component timing breakdown */
  componentTiming?: ComponentTimingStats;
  /** Embedded Jaeger trace data (for offline viewing) */
  jaegerTrace?: JaegerTrace;
  /** Embedded Loki logs (for offline viewing) */
  lokiLogs?: LokiLogEntry[];
}

// ============================================================================
// Jaeger Trace Types
// ============================================================================

export interface JaegerTrace {
  traceID: string;
  spans: JaegerSpan[];
  processes: Record<string, JaegerProcess>;
}

export interface JaegerSpan {
  traceID: string;
  spanID: string;
  operationName: string;
  /** Start time in microseconds */
  startTime: number;
  /** Duration in microseconds */
  duration: number;
  processID: string;
  tags?: JaegerTag[];
  logs?: JaegerLog[];
  references?: JaegerReference[];
}

export interface JaegerProcess {
  serviceName: string;
  tags?: JaegerTag[];
}

export interface JaegerTag {
  key: string;
  type: string;
  value: string | number | boolean;
}

export interface JaegerLog {
  timestamp: number;
  fields: JaegerTag[];
}

export interface JaegerReference {
  refType: 'CHILD_OF' | 'FOLLOWS_FROM';
  traceID: string;
  spanID: string;
}

// ============================================================================
// Loki Log Types
// ============================================================================

export interface LokiLogEntry {
  /** Timestamp in nanoseconds */
  timestamp: string;
  /** Log line content */
  line: string;
  /** Parsed JSON fields if log was JSON */
  fields?: Record<string, unknown>;
  /** Labels from Loki */
  labels?: Record<string, string>;
}

export interface LokiQueryResult {
  status: string;
  data: {
    resultType: string;
    result: LokiStream[];
  };
}

export interface LokiStream {
  stream: Record<string, string>;
  values: [string, string][]; // [timestamp, line]
}

// ============================================================================
// Benchmark Interface
// ============================================================================

/**
 * Standard interface that all component benchmarks implement.
 * This enables DRY code - common logic in BaseBenchmark,
 * component-specific logic in each implementation.
 */
export interface BenchmarkInterface {
  /** Unique identifier for this benchmark type */
  readonly id: BenchmarkComponentId;

  /** Human-readable name */
  readonly name: string;

  /** Description of what this benchmark tests */
  readonly description: string;

  /** Run the benchmark with given configuration */
  run(config?: Partial<BenchmarkConfig>): Promise<BenchmarkResult>;

  /** Stop a running benchmark */
  stop(): void;

  /** Check if benchmark is currently running */
  isRunning(): boolean;

  /** Get current configuration */
  getConfig(): BenchmarkConfig;

  /** Set progress callback for live updates */
  onProgress(callback: ProgressCallback): void;
}

// ============================================================================
// Progress Tracking
// ============================================================================

export interface BenchmarkProgress {
  /** Operations completed so far */
  operationsCompleted: number;
  /** Current throughput (ops/sec) */
  currentThroughput: number;
  /** Elapsed time in milliseconds */
  elapsedMs: number;
  /** Remaining time in milliseconds */
  remainingMs: number;
  /** Percentage complete (0-100) */
  percentComplete: number;
  /** Current CPU usage */
  currentCpu: number;
  /** Current memory usage */
  currentMemory: number;
}

export type ProgressCallback = (progress: BenchmarkProgress) => void;

// ============================================================================
// Report Data Structure
// ============================================================================

/**
 * Complete data structure for report generation.
 * Used by both HTML and Markdown serialization.
 */
export interface BenchmarkReportData {
  metadata: {
    generatedAt: string;
    commit: string;
    component: BenchmarkComponentId;
    name: string;
    description: string;
  };

  results: BenchmarkResult;

  sampleEvents: {
    /** 1-2 successful events for verification */
    successful: SampleEvent[];
    /** Up to 10 error/timeout events for investigation */
    errors: SampleEvent[];
  };

  charts: {
    throughputTimeline: {
      labels: string[];
      values: number[];
    };
    cpuTimeline: {
      labels: string[];
      values: number[];
    };
    memoryTimeline: {
      labels: string[];
      values: number[];
    };
    componentBreakdown: {
      labels: string[];
      values: number[];
      colors: string[];
    };
    latencyDistribution: {
      labels: string[];
      values: number[];
      colors: string[];
    };
  };

  /** Embedded trace data keyed by traceId */
  traces: Record<string, JaegerTrace>;

  /** Embedded logs keyed by traceId */
  logs: Record<string, LokiLogEntry[]>;
}
