# Unified Diagnostic System - Design & Implementation Plan

## Overview

A comprehensive diagnostic telemetry system that enables every component in the reactive system to emit standardized, high-granularity operational data. This data can be rendered as static HTML (for humans), JSON dumps (for tooling), and Markdown reports (for AI analysis).

---

## Goals

1. **Standardized Schema**: Every component emits the same data format (Protobuf)
2. **High Granularity**: Method-level CPU, allocation sites, per-stage timing
3. **Self-Describing**: App tells us its pipeline structure, hot paths, dependencies
4. **Multi-Format Output**: HTML reports, JSON dumps, Markdown summaries
5. **Accumulation**: Ring buffer of recent data, Prometheus-style scraping
6. **Actionable**: Data that directly points to bottlenecks and issues

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Code                          │
│  (Gateway, Flink, Drools)                                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    DiagnosticEmitter (Java)                      │
│  - Collects metrics at defined points                            │
│  - Maintains ring buffer (last 60s)                              │
│  - Exposes /diagnostics endpoint                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Protobuf Schema                               │
│  - DiagnosticSnapshot                                            │
│  - PipelineDefinition                                            │
│  - StageMetrics, HotPath, Dependency, etc.                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Renderers (Go CLI)                            │
│  - HTML: Static dashboard with charts                            │
│  - JSON: Raw data for tooling                                    │
│  - Markdown: Structured report for AI analysis                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Schema Design (Protobuf)

### File: `platform/src/main/proto/diagnostics.proto`

```protobuf
syntax = "proto3";

package com.reactive.diagnostics;

option java_package = "com.reactive.platform.diagnostics.proto";
option java_multiple_files = true;

// ============================================================================
// Top-Level Messages
// ============================================================================

// Complete diagnostic snapshot from a component
message DiagnosticSnapshot {
  ComponentInfo component = 1;
  MemorySnapshot memory = 2;
  repeated GCEvent gc_events = 3;
  ThreadSnapshot threads = 4;
  repeated StageMetrics stages = 5;
  repeated HotPath hot_paths = 6;
  repeated HotMethod hot_methods = 7;
  repeated AllocationSite allocations = 8;
  repeated DependencyMetrics dependencies = 9;
  BackpressureState backpressure = 10;
  repeated CacheMetrics caches = 11;
  BusinessMetrics business = 12;
  repeated Anomaly anomalies = 13;
  repeated RequestJourney sampled_journeys = 14;
}

// Component self-identification
message ComponentInfo {
  string name = 1;                    // "gateway", "flink-taskmanager", "drools"
  string version = 2;                 // "1.0.0"
  int64 timestamp_ms = 3;             // Snapshot timestamp
  int64 uptime_ms = 4;                // Time since start
  string instance_id = 5;             // Unique instance identifier
  PipelineDefinition pipeline = 6;   // Self-described pipeline structure
}

// ============================================================================
// Pipeline Self-Description
// ============================================================================

// How the component describes its processing pipeline
message PipelineDefinition {
  string name = 1;                    // "counter-processing"
  repeated PipelineStage stages = 2;
  repeated string entry_points = 3;   // ["POST /api/counter", "kafka:counter-events"]
  repeated string exit_points = 4;    // ["kafka:counter-results", "websocket"]
}

message PipelineStage {
  string name = 1;                    // "serialize-avro"
  StageType type = 2;
  string component = 3;               // Which component owns this stage
  bool async = 4;                     // Does this stage run async?
  string next_stage = 5;              // Next stage in pipeline (if linear)
  repeated string fan_out = 6;        // Multiple next stages (if branching)
}

enum StageType {
  STAGE_TYPE_UNKNOWN = 0;
  INGRESS = 1;                        // Receiving data (HTTP, Kafka consume)
  EGRESS = 2;                         // Sending data (HTTP response, Kafka produce)
  TRANSFORM = 3;                      // Data transformation
  EXTERNAL_CALL = 4;                  // Call to external service
  COMPUTE = 5;                        // CPU-bound computation
  IO = 6;                             // Disk/network I/O
}

// ============================================================================
// Memory Metrics
// ============================================================================

message MemorySnapshot {
  int64 timestamp_ms = 1;

  // Heap breakdown
  int64 heap_used_bytes = 2;
  int64 heap_committed_bytes = 3;
  int64 heap_max_bytes = 4;

  // Generation breakdown (for generational GCs)
  int64 eden_used_bytes = 5;
  int64 eden_max_bytes = 6;
  int64 survivor_used_bytes = 7;
  int64 old_gen_used_bytes = 8;
  int64 old_gen_max_bytes = 9;

  // Non-heap
  int64 metaspace_used_bytes = 10;
  int64 metaspace_committed_bytes = 11;
  int64 code_cache_used_bytes = 12;

  // Off-heap / Direct
  int64 direct_used_bytes = 13;
  int64 direct_max_bytes = 14;
  int64 mapped_used_bytes = 15;

  // Native/RSS
  int64 native_rss_bytes = 16;
  int64 native_tracked_bytes = 17;    // From NativeMemoryTracking

  // Rates (since last snapshot)
  int64 allocations_bytes = 18;       // Bytes allocated since last
  int64 promotions_bytes = 19;        // Bytes promoted to old gen

  // Computed
  MemoryPressure pressure = 20;
}

enum MemoryPressure {
  MEMORY_PRESSURE_UNKNOWN = 0;
  LOW = 1;                            // < 50%
  MEDIUM = 2;                         // 50-75%
  HIGH = 3;                           // 75-90%
  CRITICAL = 4;                       // > 90%
}

// ============================================================================
// GC Events
// ============================================================================

message GCEvent {
  int64 timestamp_ms = 1;
  string gc_type = 2;                 // "G1 Young", "G1 Full", "ZGC Cycle"
  string cause = 3;                   // "G1 Evacuation Pause", "Metadata GC Threshold"
  int64 duration_us = 4;

  // Heap state
  int64 heap_before_bytes = 5;
  int64 heap_after_bytes = 6;
  int64 freed_bytes = 7;

  // Generation details
  int64 eden_before_bytes = 8;
  int64 eden_after_bytes = 9;
  int64 survivor_before_bytes = 10;
  int64 survivor_after_bytes = 11;
  int64 old_before_bytes = 12;
  int64 old_after_bytes = 13;
  int64 promoted_bytes = 14;

  // Flags
  bool concurrent = 15;               // Was this a concurrent collection?
  bool stop_the_world = 16;           // Did this pause all threads?
}

// ============================================================================
// Thread Metrics
// ============================================================================

message ThreadSnapshot {
  int64 timestamp_ms = 1;

  // Counts by state
  int32 total = 2;
  int32 runnable = 3;
  int32 blocked = 4;
  int32 waiting = 5;
  int32 timed_waiting = 6;
  int32 new_count = 7;
  int32 terminated = 8;

  // Additional info
  int32 daemon_count = 9;
  int32 peak_count = 10;
  int32 created_since_last = 11;

  // Top CPU consumers
  repeated ThreadInfo top_cpu_threads = 12;

  // Blocked threads (potential deadlock)
  repeated BlockedThread blocked_threads = 13;
}

message ThreadInfo {
  string name = 1;
  string state = 2;
  int64 cpu_time_ms = 3;
  int64 user_time_ms = 4;
  int64 allocated_bytes = 5;          // Thread-local allocations
}

message BlockedThread {
  string name = 1;
  string blocked_on = 2;              // Lock/monitor being waited on
  string owner = 3;                   // Thread holding the lock
  int64 blocked_time_ms = 4;
}

// ============================================================================
// Stage Timing Metrics
// ============================================================================

message StageMetrics {
  string stage_name = 1;
  int64 window_start_ms = 2;
  int64 window_duration_ms = 3;

  // Counts
  int64 invocations = 4;
  int64 successes = 5;
  int64 failures = 6;

  // Timing (microseconds)
  int64 total_time_us = 7;
  int64 min_time_us = 8;
  int64 max_time_us = 9;
  int64 avg_time_us = 10;
  int64 p50_time_us = 11;
  int64 p95_time_us = 12;
  int64 p99_time_us = 13;

  // Wait vs execution
  int64 total_wait_time_us = 14;      // Time spent queued
  int64 total_exec_time_us = 15;      // Time spent executing

  // Throughput
  int64 bytes_in = 16;
  int64 bytes_out = 17;

  // Queue state at entry (for async stages)
  int32 avg_queue_depth = 18;
  int32 max_queue_depth = 19;
  int32 pool_active_avg = 20;
  int32 pool_max = 21;
}

// ============================================================================
// Hot Path Analysis
// ============================================================================

message HotPath {
  string path = 1;                    // "POST /api/counter -> kafka.publish -> ack"
  int64 window_start_ms = 2;
  int64 window_duration_ms = 3;

  int64 count = 4;
  int64 total_time_us = 5;
  int64 avg_time_us = 6;
  int64 p99_time_us = 7;
  int64 allocation_bytes = 8;

  // Breakdown by stage within this path
  repeated PathSegment segments = 9;
}

message PathSegment {
  string stage = 1;
  int64 avg_time_us = 2;
  double percent_of_path = 3;         // What % of total path time
}

// ============================================================================
// CPU Profiling (Hot Methods)
// ============================================================================

message HotMethod {
  string method = 1;                  // "com.reactive.JsonCodec.serialize"
  string class_name = 2;
  int64 samples = 3;                  // CPU sample count
  double percent = 4;                 // % of total CPU
  bool is_native = 5;                 // Native method?
  bool is_gc = 6;                     // GC-related?
}

// ============================================================================
// Allocation Tracking
// ============================================================================

message AllocationSite {
  string site = 1;                    // "CounterEvent.<init>"
  string class_name = 2;
  string method = 3;
  int64 bytes = 4;
  int64 count = 5;
  double percent = 6;                 // % of total allocations
}

// ============================================================================
// Dependency Tracking
// ============================================================================

message DependencyMetrics {
  string name = 1;                    // "kafka-broker", "drools-service"
  DependencyType type = 2;
  int64 window_start_ms = 3;
  int64 window_duration_ms = 4;

  // Call stats
  int64 calls = 5;
  int64 successes = 6;
  int64 failures = 7;
  int64 timeouts = 8;

  // Timing
  int64 latency_avg_us = 9;
  int64 latency_p99_us = 10;
  int64 latency_max_us = 11;

  // Data volume
  int64 bytes_sent = 12;
  int64 bytes_received = 13;

  // Connection pool
  int32 connections_active = 14;
  int32 connections_idle = 15;
  int32 connections_max = 16;
  int32 connection_wait_count = 17;   // Threads waiting for connection

  // Circuit breaker
  CircuitBreakerState circuit_state = 18;
}

enum DependencyType {
  DEPENDENCY_TYPE_UNKNOWN = 0;
  HTTP = 1;
  KAFKA = 2;
  DATABASE = 3;
  GRPC = 4;
  REDIS = 5;
}

enum CircuitBreakerState {
  CIRCUIT_BREAKER_UNKNOWN = 0;
  CLOSED = 1;                         // Normal operation
  OPEN = 2;                           // Failing fast
  HALF_OPEN = 3;                      // Testing recovery
}

// ============================================================================
// Backpressure
// ============================================================================

message BackpressureState {
  bool active = 1;
  string source = 2;                  // "drools-async-pool", "kafka-producer"
  string reason = 3;                  // "pool_exhausted", "queue_full"
  int32 queue_depth = 4;
  int32 queue_max = 5;
  int64 wait_time_avg_us = 6;
  int64 rejected_count = 7;
  int64 duration_ms = 8;              // How long backpressure has been active
}

// ============================================================================
// Cache/State Metrics
// ============================================================================

message CacheMetrics {
  string name = 1;                    // "session-state"
  string type = 2;                    // "LRU", "TTL", "Caffeine"
  int64 size = 3;                     // Current entries
  int64 max_size = 4;
  int64 hits = 5;
  int64 misses = 6;
  double hit_rate = 7;
  int64 evictions = 8;
  int64 memory_bytes = 9;
}

// ============================================================================
// Business Metrics
// ============================================================================

message BusinessMetrics {
  int64 window_start_ms = 1;
  int64 window_duration_ms = 2;

  int64 events_received = 3;
  int64 events_processed = 4;
  int64 events_pending = 5;
  int64 events_failed = 6;

  // Custom counters (flexible key-value)
  map<string, int64> counters = 7;    // e.g., "alerts.WARNING": 45
  map<string, double> gauges = 8;     // e.g., "avg_processing_time_ms": 12.5
}

// ============================================================================
// Anomaly Detection
// ============================================================================

message Anomaly {
  string metric = 1;                  // "drools.latency_p99"
  double current_value = 2;
  double baseline_value = 3;
  double deviation = 4;               // How many times baseline
  double sigma = 5;                   // Standard deviations from mean
  int64 started_at_ms = 6;
  int64 duration_ms = 7;
  AnomalySeverity severity = 8;
}

enum AnomalySeverity {
  ANOMALY_SEVERITY_UNKNOWN = 0;
  INFO = 1;                           // Notable but not concerning
  WARNING = 2;                        // Needs attention
  CRITICAL = 3;                       // Immediate action required
}

// ============================================================================
// Request Journey (Sampled Full Traces)
// ============================================================================

message RequestJourney {
  string trace_id = 1;
  int64 started_at_ms = 2;
  int64 total_duration_us = 3;
  int64 wait_time_us = 4;             // Total time waiting
  int64 work_time_us = 5;             // Total time executing
  int64 allocation_bytes = 6;
  int32 thread_switches = 7;

  repeated JourneyStep steps = 8;
}

message JourneyStep {
  string stage = 1;
  int64 relative_start_us = 2;        // Microseconds from journey start
  int64 duration_us = 3;
  string thread = 4;                  // null if waiting
  int64 allocation_bytes = 5;

  // Optional enrichment
  map<string, string> attributes = 6; // e.g., "status": "200", "size": "128"
}

// ============================================================================
// Error Events
// ============================================================================

message ErrorEvent {
  int64 timestamp_ms = 1;
  string trace_id = 2;
  string error_type = 3;              // "java.lang.OutOfMemoryError"
  string message = 4;
  string stack_hash = 5;              // Dedupe key for stack trace
  repeated string stack_top = 6;      // Top 5 stack frames
  string thread = 7;
  int64 count_last_minute = 8;        // Dedupe count

  // Context at time of error
  MemorySnapshot memory_at_error = 9;
}
```

---

## Phase 2: Renderers (Go CLI)

### File Structure
```
platform/cli/cmd/
├── diagnostic_render.go      # Main rendering logic
├── diagnostic_html.go        # HTML template rendering
├── diagnostic_json.go        # JSON output
├── diagnostic_markdown.go    # Markdown report generation
└── templates/
    └── diagnostic.html       # HTML template
```

### Renderer Interface
```go
type DiagnosticRenderer interface {
    Render(snapshot *DiagnosticSnapshot, w io.Writer) error
}

type HTMLRenderer struct { /* ... */ }
type JSONRenderer struct { /* ... */ }
type MarkdownRenderer struct { /* ... */ }
```

### CLI Commands
```bash
# Fetch and render diagnostics
reactive diagnostics                    # Default: human-readable
reactive diagnostics --json             # JSON output
reactive diagnostics --markdown         # Markdown report
reactive diagnostics --html             # Generate HTML file

# Target specific component
reactive diagnostics --component gateway

# Historical (from accumulated data)
reactive diagnostics --since 5m         # Last 5 minutes
reactive diagnostics --range 10:00-10:30

# Watch mode
reactive diagnostics --watch            # Live updates

# Export
reactive diagnostics --export report.html
reactive diagnostics --export report.md
reactive diagnostics --export report.json
```

---

## Phase 3: Demo Scenarios

Before implementing the Java library, we'll create mock data that demonstrates the value of the system.

### Scenario 1: Normal Operation
- All metrics within baseline
- Low latency, no backpressure
- Healthy memory, infrequent GC

### Scenario 2: Memory Pressure (Pre-OOM)
- Old gen growing steadily
- Frequent Full GCs
- Allocation rate exceeds GC throughput
- Anomaly: heap usage trending to 100%

### Scenario 3: Dependency Bottleneck
- Drools latency spiking
- Connection pool exhausted
- Backpressure active on async pool
- Hot path shows 80% time in drools.evaluate

### Scenario 4: Thread Contention
- Multiple threads blocked on same lock
- High blocked thread count
- Low throughput despite low memory

### Scenario 5: Kafka Backpressure
- Producer queue at max
- Send latency elevated
- Rejected messages increasing

---

## Phase 4: Java Library Implementation

### Package Structure
```
platform/src/main/java/com/reactive/platform/diagnostics/
├── DiagnosticEmitter.java           # Main entry point
├── DiagnosticCollector.java         # Collects metrics from various sources
├── DiagnosticRingBuffer.java        # Accumulates snapshots over time
├── DiagnosticEndpoint.java          # Spring Boot actuator endpoint
├── collectors/
│   ├── MemoryCollector.java
│   ├── GCCollector.java
│   ├── ThreadCollector.java
│   ├── StageCollector.java
│   ├── DependencyCollector.java
│   └── JFRCollector.java            # Java Flight Recorder integration
├── annotations/
│   ├── @DiagnosticStage             # Mark methods as pipeline stages
│   ├── @DiagnosticDependency        # Mark external calls
│   └── @DiagnosticPath              # Define hot paths
└── proto/                           # Generated protobuf classes
```

### Integration Points

```java
// 1. Define pipeline at startup
DiagnosticEmitter.definePipeline("counter-processing", pipeline -> pipeline
    .stage("receive", StageType.INGRESS)
    .stage("serialize", StageType.TRANSFORM)
    .stage("kafka-produce", StageType.EGRESS, async(true))
    .stage("kafka-consume", StageType.INGRESS, async(true))
    .stage("enrich-drools", StageType.EXTERNAL_CALL, async(true))
    .stage("apply-result", StageType.TRANSFORM)
    .build());

// 2. Annotate methods
@DiagnosticStage("serialize")
public byte[] serialize(CounterEvent event) {
    return codec.encode(event);
}

// 3. Track dependencies
@DiagnosticDependency(name = "drools", type = HTTP)
public DroolsResult evaluate(CounterEvent event) {
    return droolsClient.evaluate(event);
}

// 4. Manual timing for complex paths
try (var timing = DiagnosticEmitter.time("kafka-produce")) {
    publisher.send(record);
    timing.attr("topic", record.topic());
    timing.attr("partition", record.partition());
}

// 5. Record business metrics
DiagnosticEmitter.counter("alerts.WARNING").increment();
DiagnosticEmitter.gauge("pending_events").set(queue.size());

// 6. Sample request journeys
try (var journey = DiagnosticEmitter.journey(traceId)) {
    journey.step("parse-json", () -> parseJson(body));
    journey.step("validate", () -> validate(event));
    journey.step("process", () -> process(event));
}
```

### Actuator Endpoint

```java
@Endpoint(id = "diagnostics")
public class DiagnosticEndpoint {

    @ReadOperation
    public DiagnosticSnapshot snapshot() {
        return collector.collect();
    }

    @ReadOperation
    public List<DiagnosticSnapshot> history(
            @Selector String duration) {  // "5m", "1h"
        return ringBuffer.getRange(duration);
    }
}
```

Endpoint URLs:
- `GET /actuator/diagnostics` - Current snapshot
- `GET /actuator/diagnostics/history?duration=5m` - Historical data
- `GET /actuator/diagnostics/pipeline` - Pipeline definition only

---

## Phase 5: Integration & Testing

### Update Existing Components

1. **Gateway (CounterController)**
   - Add @DiagnosticStage annotations
   - Track Kafka dependency
   - Record business metrics

2. **Flink (CounterJob)**
   - Emit stage timing for each operator
   - Track Drools HTTP dependency
   - Report backpressure state

3. **Drools (RuleController)**
   - Track rule evaluation timing
   - Report cache metrics
   - Emit per-rule firing stats

### CLI Integration

```bash
# Add new diagnostic commands
reactive diag                     # Alias for diagnostics
reactive diag summary             # Quick overview
reactive diag memory              # Memory deep-dive
reactive diag latency             # Latency analysis
reactive diag hotpaths            # Hot path report
reactive diag deps                # Dependency health
reactive diag journey <trace-id>  # Full request journey
```

---

## Phase 6: Output Formats

### HTML Report Structure
```
┌─────────────────────────────────────────────────────────────────┐
│  Reactive System Diagnostics - 2025-12-24 05:30:00              │
│  Component: gateway | Uptime: 2h 34m | Health: HEALTHY          │
├─────────────────────────────────────────────────────────────────┤
│  MEMORY                          │  THROUGHPUT                  │
│  ┌────────────────────┐          │  Requests/sec: 1,523         │
│  │ Heap [████████░░] 78%│        │  Bytes in: 1.2 MB/s          │
│  │ Old  [██████░░░░] 62%│        │  Bytes out: 2.4 MB/s         │
│  │ Meta [███░░░░░░░] 31%│        │  Pending: 12                 │
│  └────────────────────┘          │                              │
├─────────────────────────────────────────────────────────────────┤
│  HOT PATHS                                                      │
│  1. POST /api/counter (1,523/s, avg 3.2ms, p99 8.5ms)          │
│     └─ serialize: 15% | kafka-produce: 45% | wait-ack: 40%     │
├─────────────────────────────────────────────────────────────────┤
│  DEPENDENCIES                                                   │
│  kafka-broker    ✓ 1.2ms avg  │ 3 active / 5 max connections   │
│  drools-service  ⚠ 25ms avg   │ 8 active / 50 max connections  │
├─────────────────────────────────────────────────────────────────┤
│  ANOMALIES                                                      │
│  ⚠ drools.latency_p99: 85ms (baseline: 25ms, 3.4x deviation)  │
└─────────────────────────────────────────────────────────────────┘
```

### Markdown Report Structure
```markdown
# Diagnostic Report: gateway
**Timestamp**: 2025-12-24T05:30:00Z
**Uptime**: 2h 34m
**Health**: HEALTHY (Score: 85/100)

## Memory
| Pool | Used | Max | Percent |
|------|------|-----|---------|
| Heap | 800 MB | 1024 MB | 78% |
| Old Gen | 450 MB | 716 MB | 62% |
| Metaspace | 67 MB | 256 MB | 31% |
| Direct | 32 MB | 1024 MB | 3% |

**Allocation Rate**: 52 MB/s
**GC Overhead**: 2.3%

## Hot Paths
1. **POST /api/counter** - 1,523/s
   - avg: 3.2ms, p99: 8.5ms
   - Breakdown: serialize (15%) → kafka-produce (45%) → wait-ack (40%)

## Dependencies
| Name | Status | Latency (avg) | Latency (p99) | Connections |
|------|--------|---------------|---------------|-------------|
| kafka-broker | ✓ | 1.2ms | 4.5ms | 3/5 |
| drools-service | ⚠ | 25ms | 85ms | 8/50 |

## Anomalies
- **WARNING**: drools.latency_p99 at 85ms (baseline: 25ms, 3.4σ)

## Recommendations
1. Drools latency elevated - check Drools container resources
2. Consider increasing drools connection pool (currently 50)
```

### JSON Structure
```json
{
  "component": {
    "name": "gateway",
    "version": "1.0.0",
    "timestamp_ms": 1735020600000,
    "uptime_ms": 9240000
  },
  "memory": { ... },
  "gc_events": [ ... ],
  "stages": [ ... ],
  "hot_paths": [ ... ],
  "dependencies": [ ... ],
  "anomalies": [ ... ]
}
```

---

## Implementation Order

### Week 1: Foundation
1. ✅ Design protobuf schema (this document)
2. Create demo scenarios with mock data
3. Implement Go renderers (HTML, JSON, Markdown)
4. Test renderers with mock data

### Week 2: Java Library
5. Generate protobuf Java classes
6. Implement DiagnosticCollector (memory, GC, threads)
7. Implement DiagnosticRingBuffer
8. Create DiagnosticEndpoint

### Week 3: Integration
9. Add annotations and AOP support
10. Integrate into Gateway
11. Integrate into Drools
12. Create Flink adapter

### Week 4: Polish
13. Add CLI commands
14. Create benchmark integration
15. Add watch mode
16. Documentation

---

## File Locations Summary

```
platform/
├── src/main/proto/
│   └── diagnostics.proto                    # Schema definition
├── src/main/java/com/reactive/platform/diagnostics/
│   ├── DiagnosticEmitter.java
│   ├── DiagnosticCollector.java
│   ├── DiagnosticRingBuffer.java
│   ├── DiagnosticEndpoint.java
│   ├── collectors/
│   └── annotations/
└── cli/cmd/
    ├── diagnostic_render.go
    ├── diagnostic_html.go
    ├── diagnostic_json.go
    ├── diagnostic_markdown.go
    └── templates/
        └── diagnostic.html

docs/
└── DIAGNOSTIC_SYSTEM_PLAN.md               # This document

reports/
└── diagnostics/                            # Generated reports
    ├── gateway-2025-12-24.html
    ├── gateway-2025-12-24.md
    └── gateway-2025-12-24.json
```

---

## Success Criteria

1. **Pre-OOM Detection**: Can detect memory exhaustion 30+ seconds before crash
2. **Bottleneck Identification**: Immediately identify which stage/dependency is slow
3. **Root Cause Data**: Enough granularity to determine WHY something is slow
4. **Zero Overhead When Not Used**: Minimal impact on normal operation
5. **AI-Friendly Output**: Markdown reports that Claude can analyze and draw conclusions from
6. **Human-Friendly Output**: HTML dashboards that operators can use

---

## Next Steps

1. Review and approve this plan
2. Create demo mock data files
3. Implement renderers
4. Test with demo scenarios
5. Proceed to Java implementation
