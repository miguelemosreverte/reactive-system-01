// Types matching the Go benchmark tool's types.go

export interface LatencyStats {
  min: number;
  max: number;
  avg: number;
  p50: number;
  p95: number;
  p99: number;
}

export interface ComponentTiming {
  gatewayMs: number;
  kafkaMs: number;
  flinkMs: number;
  droolsMs: number;
}

export interface JaegerSpan {
  traceID: string;
  spanID: string;
  operationName: string;
  startTime: number; // microseconds
  duration: number; // microseconds
  processID: string;
  tags?: Record<string, unknown>[];
  references?: Record<string, unknown>[];
}

export interface JaegerProcess {
  serviceName: string;
}

export interface JaegerTrace {
  traceID: string;
  spans: JaegerSpan[];
  processes: Record<string, JaegerProcess>;
}

export interface LokiLogEntry {
  timestamp: string;
  line: string;
  labels: Record<string, string>;
  fields?: Record<string, unknown>;
}

export interface TraceData {
  trace?: JaegerTrace;
  logs?: LokiLogEntry[];
}

export interface SampleEvent {
  id: string;
  traceId: string;
  otelTraceId: string;
  timestamp: number;
  latencyMs: number;
  status: 'success' | 'error' | 'timeout';
  error?: string;
  componentTiming?: ComponentTiming;
  traceData?: TraceData;
}

export interface BenchmarkResult {
  component: string;
  name: string;
  description: string;
  startTime: string;
  endTime: string;
  durationMs: number;
  totalOperations: number;
  successfulOperations: number;
  failedOperations: number;
  peakThroughput: number;
  avgThroughput: number;
  throughputTimeline: number[];
  latency: LatencyStats;
  cpuTimeline: number[];
  memoryTimeline: number[];
  peakCpu: number;
  peakMemory: number;
  avgCpu: number;
  avgMemory: number;
  componentTiming?: ComponentTiming;
  sampleEvents: SampleEvent[];
  status: string;
  errorMessage?: string;
}

export interface RankingItem {
  id: string;
  name: string;
  peakThroughput: number;
  barWidth: number;
  isBottleneck: boolean;
}

export interface BenchmarkIndex {
  components: Array<{
    id: string;
    name: string;
    peakThroughput: number;
    latencyP99: number;
    status: string;
  }>;
  rankings: RankingItem[];
  generatedAt: string;
}
