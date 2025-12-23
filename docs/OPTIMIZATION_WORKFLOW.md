# Benchmark Optimization Workflow

This document describes the tools and workflow for identifying and fixing performance bottlenecks in the reactive system.

## Architecture Understanding

Before optimizing, understand the event flow:

```
HTTP Request → Kafka (counter-events) → Flink (aggregation) → Drools (rules, 1/sec) → Kafka (counter-results) → WebSocket
```

**Key insight:** Drools is called once per aggregation window (1 second), NOT per event. A benchmark showing Drools at 3,482 ops/sec is measuring the Drools HTTP endpoint capacity, not the actual system bottleneck.

---

## Available Tools

### 1. Diagnostic Endpoints (Most Important)

#### POST /api/diagnostic/run
Runs a full E2E diagnostic test with detailed trace analysis.

```bash
curl -X POST http://localhost:8080/api/diagnostic/run | jq
```

Returns:
- `requestId`, `otelTraceId` - Correlation IDs
- `validation.isComplete` - Whether all services were traced
- `validation.spanCount` - Total spans in trace
- `validation.spanCountByService` - Breakdown by service
- `validation.operations` - List of operations performed
- `logs` - Correlated logs from Loki
- `summary.status` - PASS/FAIL with explanation

#### GET /api/diagnostic/validate/{traceId}
Validates an existing trace for completeness.

```bash
curl http://localhost:8080/api/diagnostic/validate/{traceId} | jq
```

#### GET /api/diagnostic/services
Lists services registered in Jaeger vs expected E2E services.

```bash
curl http://localhost:8080/api/diagnostic/services | jq
```

---

### 2. Replay Endpoints (Deep Debugging)

The replay system allows replaying historical events with full tracing to understand exactly what happened.

#### POST /api/replay/session/{sessionId}
Replays all events for a session with detailed tracing.

```bash
curl -X POST http://localhost:8080/api/replay/session/my-session | jq
```

Returns:
- `eventsReplayed` - Number of events replayed
- `replayTraceId` - New trace ID for this replay (view in Jaeger)
- `durationMs` - Replay duration
- `initialState` - State before any events
- `finalState` - State after all events

**Use case:** When an event behaves unexpectedly, replay it to see the full trace in Jaeger with state transitions.

#### GET /api/replay/session/{sessionId}/events
Lists all stored events for a session.

```bash
curl http://localhost:8080/api/replay/session/my-session/events | jq
```

Returns:
- `count` - Number of events
- `events[]` - List with eventId, action, timestamp, traceId

#### GET /api/replay/session/{sessionId}/history
Gets state history showing state before/after each event.

```bash
curl http://localhost:8080/api/replay/session/my-session/history | jq
```

Returns:
- `transitions[]` - Array of { eventId, stateBefore, stateAfter }

**Use case:** Understand exactly how state evolved over time.

---

### 3. Debug Endpoints (Trace Inspection)

#### GET /api/debug/trace/{traceId}
Fetches and enriches a trace from Jaeger.

```bash
curl http://localhost:8080/api/debug/trace/{traceId} | jq
```

#### GET /api/debug/request/{requestId}
Searches for a trace by business requestId.

```bash
curl http://localhost:8080/api/debug/request/{requestId} | jq
```

#### POST /api/debug/diagnose
Alternative diagnostic endpoint in gateway module.

---

### 4. Benchmark Commands

```bash
# Run individual component benchmarks
./cli.sh benchmark http      # HTTP endpoint latency
./cli.sh benchmark kafka     # Kafka produce/consume
./cli.sh benchmark flink     # Flink processing
./cli.sh benchmark drools    # Drools direct HTTP
./cli.sh benchmark gateway   # Gateway (HTTP + Kafka)
./cli.sh benchmark full      # Full E2E pipeline

# Run all with regression tracking
./cli.sh benchmark all -d 60

# View history and compare
./cli.sh benchmark history
./cli.sh benchmark compare
./cli.sh benchmark compare {sha}

# Open reports
./cli.sh benchmark report
```

---

### 5. Benchmark Doctor

```bash
# Interactive diagnostics
./cli.sh benchmark doctor

# JSON output for automation
./scripts/benchmark-doctor.sh --json

# Use API endpoint
./scripts/benchmark-doctor.sh --api
```

Checks:
- Service health (gateway, drools, jaeger, loki, otel-collector, flink)
- Trace propagation (sends test request, validates trace)
- Log correlation (checks requestId correlation in logs)
- OTEL Collector status
- Benchmark report quality

---

## Optimization Workflow

### Step 1: Establish Baseline

```bash
# Run full benchmark suite
./cli.sh benchmark all -d 60

# Save to history
./cli.sh benchmark save

# View results
./cli.sh benchmark report
```

### Step 2: Identify Bottleneck (Don't Guess!)

```bash
# Run diagnostic to get detailed trace
curl -X POST http://localhost:8080/api/diagnostic/run | jq

# Look at:
# - validation.spanCountByService (where is time spent?)
# - logs (any errors or warnings?)
# - operations (what operations are slow?)
```

### Step 3: Deep Dive with Replay

If a specific session behaves poorly:

```bash
# List events
curl http://localhost:8080/api/replay/session/{sessionId}/events | jq

# Replay with tracing
curl -X POST http://localhost:8080/api/replay/session/{sessionId} | jq

# View replay trace in Jaeger
open http://localhost:16686/trace/{replayTraceId}

# See state transitions
curl http://localhost:8080/api/replay/session/{sessionId}/history | jq
```

### Step 4: Make Changes

After identifying the actual bottleneck:
1. Make code changes
2. Rebuild affected service
3. Restart: `./cli.sh restart {service}`

### Step 5: Validate Improvement

```bash
# Run benchmark for affected component
./cli.sh benchmark {component} -d 30

# Run full E2E
./cli.sh benchmark full -d 60

# Compare with baseline
./cli.sh benchmark compare
```

### Step 6: Verify with Diagnostic

```bash
# Confirm traces are still complete
curl -X POST http://localhost:8080/api/diagnostic/run | jq '.summary'
```

---

## Understanding Benchmark Results

### What Each Benchmark Measures

| Benchmark | Measures | NOT a bottleneck if... |
|-----------|----------|------------------------|
| `http` | Raw HTTP latency | Always fast (>10k ops/s) |
| `kafka` | Produce/consume round-trip | Fast enough (>5k ops/s) |
| `flink` | Stream processing | Processing matches event rate |
| `drools` | Rule evaluation HTTP | Called once/second, capacity >> 1/sec |
| `gateway` | HTTP + Kafka publish | Fast enough (>10k ops/s) |
| `full` | Complete E2E | **This is the real throughput** |

### Reading the Full E2E Results

The `full` benchmark is the only one that measures actual system throughput. Component benchmarks measure capacity, not actual usage.

**Example interpretation:**
- Full E2E: 931 ops/sec (actual system throughput)
- Drools: 3,482 ops/sec (capacity is 3.7x higher than needed per second)
- Kafka P99 latency: 26s (indicates consumer lag - look at Flink processing)

---

## Reading Component Timing

The benchmark results include `componentTiming` in sample events:

```json
{
  "latencyMs": 24,
  "componentTiming": {
    "gatewayMs": 11,    // HTTP handling + Kafka publish
    "kafkaMs": 0,       // Kafka transit (usually ~0)
    "flinkMs": 13,      // Flink processing + async Drools
    "droolsMs": 0       // Included in flinkMs (async call)
  }
}
```

This tells you exactly where time is spent per request.

---

## Automated Analysis (Future)

The diagnostic endpoint should be enhanced to provide:

1. **Bottleneck identification** - Which span/operation is slowest
2. **Capacity analysis** - Is component capacity sufficient for load
3. **Recommendations** - Specific actions to improve performance

For now, use the tools manually following this workflow.

---

---

## Core Philosophy: Data for AI Analysis

> **Date**: 2025-12-23

The following principles must guide all diagnostic tooling development.

### The Workflow

```
CLI provides rich, detailed data  →  AI analyzes and interprets
```

**The CLI's job**: Provide the most detailed amount of information possible.
**The AI's job**: Analyze that data and draw conclusions.

**What we must NOT do**: Hardcode analysis logic in software/CPU. The code should not try to interpret - it should only collect and present data.

### User's Words (Protected)

> "You are still guessing. We need low-level granularity to know exactly, with very high precision, where exactly is the bottleneck."

> "We have traces, we have logs, we should be able to answer this question just fine. We even have the memory diagnostic."

> "Don't add new metrics - USE existing observability (Jaeger traces, Loki logs, memory diag)"

> "It should provide an overview and then pair components and so on. Hierarchical: overview → components → operations"

> "The amount of information should be very clear and detailed with very low level of granularity. It should increase - provide overview then drill into components."

> "Get as much understanding thanks to diagnosis of the state of the pipeline that we can have a conclusion that is pretty much obvious thanks to our analysis"

### Requirements for Diagnostic Output

1. **Hierarchical Structure**
   - Level 1: System overview (saturation, throughput)
   - Level 2: Component breakdown (gateway, kafka, flink, drools)
   - Level 3: Operation details within each component
   - Level 4: Individual spans and timings

2. **Use Existing Observability**
   - Jaeger traces (span timing, service breakdown)
   - Loki logs (correlated by traceId)
   - Docker stats (CPU, memory per container)
   - Memory diagnostics (JVM heap, pressure)

3. **Data Richness**
   - The data must be so detailed that the bottleneck becomes obvious
   - No guessing required - the answer should be self-evident from the data
   - The AI can then provide interpretation and recommendations

---

## Iteration Protocol (Protected User Words)

> **Date**: 2025-12-23

> "This is iteration 1, and there will be 20. Continue to iterate 20 times, taking notes on each result of the impact and committing. Don't commit regressions to throughput."

### Rules:
1. **20 iterations** of optimization
2. **Track impact** of each change
3. **No regressions** - only commit improvements
4. **Measure before and after** each change

---

## Quick Reference

```bash
# Full diagnostic
curl -X POST http://localhost:8080/api/diagnostic/run | jq

# Replay a session
curl -X POST http://localhost:8080/api/replay/session/{id} | jq

# View trace
curl http://localhost:8080/api/debug/trace/{traceId} | jq

# Run benchmarks
./cli.sh benchmark all -d 60

# Compare with baseline
./cli.sh benchmark compare

# Check system health
./cli.sh benchmark doctor
```
