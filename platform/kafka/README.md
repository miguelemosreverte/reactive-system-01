# Adaptive Microbatch System for Kafka

A high-throughput event batching system that automatically optimizes batch sizes based on observed request rate and available latency budget.

## Core Concept

**The Problem:** Kafka achieves maximum throughput with large batches, but users expect responsive systems.

**The Solution:** Use the HTTP timeout as a latency budget. If a request can wait up to 30 seconds for a response, we can batch aggressively during that window.

## Simple Flush Strategy

The flush logic is intentionally simple:

```
FLUSH when:
  (batch_size >= N)  OR  (time_elapsed >= T)

Where N and T are dynamically selected based on the current pressure level.
```

This gives us two tunable knobs:
- **N (batch size)**: How many events to collect before flushing
- **T (time interval)**: Maximum time to wait before flushing a partial batch

## 10 Pressure Levels

We define exactly **10 levels** spanning from real-time (1ms) to max-throughput (30s):

| Level | Latency Budget | Request Rate | Use Case |
|-------|----------------|--------------|----------|
| **L1_REALTIME** | 1ms | < 10 req/s | Real-time systems |
| **L2_FAST** | 5ms | 10-100 req/s | Fast response |
| **L3_LOW** | 10ms | 100-500 req/s | Low latency |
| **L4_MODERATE** | 50ms | 500-2K req/s | Moderate batching |
| **L5_BALANCED** | 100ms | 2K-10K req/s | Balanced (default) |
| **L6_THROUGHPUT** | 500ms | 10K-50K req/s | Throughput focus |
| **L7_HIGH** | 1s | 50K-200K req/s | High batch |
| **L8_AGGRESSIVE** | 5s | 200K-1M req/s | Aggressive batching |
| **L9_EXTREME** | 15s | 1M-5M req/s | Extreme batching |
| **L10_MAX** | 30s | > 5M req/s | Maximum throughput |

The levels are designed as a gradient:
- **Lower levels** (L1-L4): Faster response, smaller batches
- **Higher levels** (L6-L10): Higher throughput, larger batches, more latency

## How Level Selection Works

Every 10 seconds, the system:
1. Counts requests processed in the window
2. Calculates request rate
3. Selects the appropriate pressure level
4. Loads learned configuration (batch size N, interval T) for that level

```java
long reqPer10Sec = itemsInWindow * 10_000_000_000L / elapsedNanos;
PressureLevel level = PressureLevel.fromRequestRate(reqPer10Sec);
Config config = calibration.getBestConfigForPressure(level);
```

## Architecture

### LMAX Disruptor-Inspired Ring Buffer

We use a lock-free ring buffer pattern inspired by the [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/):

```
┌──────────────────────────────────────────────────────────────┐
│                    FastRingBuffer (per partition)            │
├──────────────────────────────────────────────────────────────┤
│  • Pre-allocated array (zero allocation on hot path)         │
│  • Atomic sequence numbers for coordination                  │
│  • Cache-line padding to avoid false sharing                 │
│  • SPSC pattern: one producer thread per partition           │
│  • Lock-free, wait-free for producers                        │
└──────────────────────────────────────────────────────────────┘
```

**Performance:** 5-6x throughput improvement over `ConcurrentLinkedQueue`.

### Bootstrap Configuration

Each level starts with a bootstrap config derived from the latency budget:

```java
// Batch hint: latency_ms * 1000 events
// Interval hint: latency_ms * 1000 microseconds
int batchHint = targetLatencyMs * 1000;
int intervalHint = targetLatencyMs * 1000;
```

The system then learns the optimal through experimentation.

## Components

### 1. BatchCalibration

SQLite-backed learning system that:
- Records performance observations per pressure level
- Learns optimal batch/interval configurations
- Uses EMA (Exponential Moving Average) for expected throughput
- Detects regressions (>10% throughput drop)

```java
BatchCalibration calibration = BatchCalibration.create(
    Path.of("~/.reactive/calibration.db"),
    5000.0  // Target latency in microseconds
);
```

### 2. MicrobatchCollector

High-throughput collector that:
- Uses partitioned FastRingBuffer (one per CPU core)
- Drains batches based on learned configuration
- Adapts to pressure changes in real-time

```java
MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
    batch -> kafkaProducer.send(serialize(batch)),
    calibration
);

// Hot path: zero allocation
collector.submitFireAndForget(event);
```

### 3. FastRingBuffer

Lock-free SPSC ring buffer:
- Pre-allocated `AtomicReferenceArray`
- `lazySet` for producer sequence (release semantics)
- Batch drain for consumer efficiency

## How It Works

```
                    ┌─────────────────┐
                    │  HTTP Request   │
                    └────────┬────────┘
                             │
                             ▼
┌────────────────────────────────────────────────────────────────┐
│                    MicrobatchCollector                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │ Ring[0]  │ │ Ring[1]  │ │ Ring[2]  │ │ Ring[N]  │          │
│  │ Thread 0 │ │ Thread 1 │ │ Thread 2 │ │ Thread N │          │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘          │
│       │            │            │            │                 │
│       └────────────┴─────┬──────┴────────────┘                 │
│                          │                                     │
│                    ┌─────▼─────┐                               │
│                    │  Drain &  │  ◄── Batch size from          │
│                    │   Flush   │      BatchCalibration         │
│                    └─────┬─────┘                               │
└──────────────────────────┼─────────────────────────────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │    Kafka    │
                    │   Producer  │
                    └─────────────┘
```

## Pressure Detection

Every 10 seconds, the system:
1. Counts requests processed in the window
2. Calculates request rate (requests per 10 seconds)
3. Maps to appropriate pressure level
4. Loads learned configuration for that level

```java
// Automatic adaptation
long reqPer10Sec = itemsInWindow * 10_000_000_000L / elapsedNanos;
PressureLevel level = PressureLevel.fromRequestRate(reqPer10Sec);
Config config = calibration.getBestConfigForPressure(level);
```

## Learning & Calibration

The system learns through experimentation:

1. **Bootstrap:** Start with latency-budget-based defaults
2. **Explore:** 20% random exploration, 80% local refinement
3. **Record:** Store observations in SQLite
4. **Optimize:** Keep best config per pressure level
5. **Adapt:** EMA smoothing for stable expectations

```bash
# View current calibration status
mvn exec:java -Dexec.mainClass="...CalibrationBenchmark" -Dexec.args="--status"

# Run calibration for specific buckets
mvn exec:java -Dexec.mainClass="...CalibrationBenchmark" \
    -Dexec.args="localhost:9092 L9_EXTREME L7_HIGH L10_MAX --rounds 5 --duration 30"
```

## BULK Baseline Benchmark

**This is the most important benchmark** - it establishes the theoretical maximum throughput.

### What It Measures

The BULK baseline batches N messages into a single Kafka `send()` call:
- **BULK mode**: 1000 messages per send
- **MEGA mode**: 10000 messages per send

### Baseline Results (Optimized Hardware)

On optimized hardware with native Kafka:

| Mode | Throughput | Configuration |
|------|-----------|---------------|
| **BULK** | **131.8M msg/s** | 1000 msg/batch, acks=0, LZ4 |
| MEGA | 150M+ msg/s | 10000 msg/batch, acks=0, LZ4 |

This is the ceiling we're trying to reach with the adaptive system.

### Running the Baseline

```bash
# Run BULK baseline (1000 messages per Kafka send)
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.KafkaBaselineBenchmark" \
    -Dexec.args="BULK 30 localhost:9092"

# Run MEGA baseline (10000 messages per Kafka send)
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.KafkaBaselineBenchmark" \
    -Dexec.args="MEGA 30 localhost:9092"

# Run ALL modes and generate comparison report
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.KafkaBaselineBenchmark" \
    -Dexec.args="ALL 30 localhost:9092 reports/kafka-baseline"
```

### Compare Adaptive vs BULK

```bash
# Full comparison: BULK baseline vs Adaptive collector
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.AdaptiveVsBulkBenchmark" \
    -Dexec.args="30 localhost:9092"
```

This runs multiple tests:
1. BULK baseline (reference)
2. Adaptive with auto pressure detection
3. Adaptive forced to L10_MAX (30s latency budget)
4. Adaptive forced to L9_EXTREME (15s latency budget)
5. Collector only (no Kafka - pure collection speed)
6. Parallel Kafka sends (bypass collector)

### Regression Detection

The system compares current throughput against the BULK baseline (131.8M msg/s):
- **< 10%**: SEVERE REGRESSION
- **< 50%**: REGRESSION
- **< 80%**: Acceptable
- **> 80%**: Good

## Running Other Benchmarks

### Calibration Status
```bash
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.CalibrationBenchmark" \
    -Dexec.args="--status"
```

### Quick Batch Test
```bash
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.QuickBatchTest" \
    -Dexec.args="localhost:9092"
```

### Full Calibration
```bash
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.CalibrationBenchmark" \
    -Dexec.args="localhost:9092 --all --rounds 5 --duration 30"
```

### Using Brochures
```bash
./reactive bench brochure run calibration-buckets
```

## Performance Results

Based on benchmarking (hardware-dependent):

| Component | Throughput | Notes |
|-----------|-----------|-------|
| Raw Kafka Producer | 70-100M msg/s | Theoretical max |
| FastRingBuffer (SPSC) | 35-40M msg/s | 5.7x vs CLQ |
| MicrobatchCollector | 25-30M msg/s | Full adaptive |
| With Kafka sending | 10-20M msg/s | End-to-end |

## Key Design Decisions

1. **No per-request tracking:** Infer from aggregate rate
2. **Latency budget as constraint:** Only limit is user tolerance
3. **Pre-allocated buffers:** Zero allocation on hot path (LMAX Disruptor pattern)
4. **Partitioned queues:** Avoid producer contention
5. **Fewer flush threads:** Reduce Kafka contention (2 threads default)
6. **Persistent learning:** SQLite stores calibration across restarts

## Kafka Producer Settings (for max throughput)

```properties
acks=1
linger.ms=10
batch.size=16777216      # 16MB
buffer.memory=536870912  # 512MB
compression.type=lz4
```

## Files

```
src/main/java/com/reactive/platform/
├── gateway/microbatch/
│   ├── BatchCalibration.java      # Learning & pressure detection (131.8M baseline)
│   ├── MicrobatchCollector.java   # Main collector with ring buffers
│   ├── FastRingBuffer.java        # LMAX-inspired lock-free buffer
│   └── MpscRingBuffer.java        # Alternative MPSC implementation
└── kafka/benchmark/
    ├── KafkaBaselineBenchmark.java   # ⭐ BULK baseline (131.8M msg/s reference)
    ├── AdaptiveVsBulkBenchmark.java  # ⭐ Compare adaptive vs BULK
    ├── CalibrationBenchmark.java     # Per-bucket benchmarking
    ├── BatchSizeExplorer.java        # Batch size impact analysis
    ├── QuickBatchTest.java           # Fast hypothesis testing
    ├── RingBufferMicroBenchmark.java # Ring buffer performance
    └── OnlineLearningBenchmark.java  # Continuous learning
```

## Theory: Why This Works

Kafka's internal batching (linger.ms, batch.size) is optimized for:
- Fewer, larger network requests
- Better compression ratios
- Reduced broker overhead

By batching at the application level **before** the Kafka producer, we:
1. Reduce the number of `producer.send()` calls
2. Allow Kafka's internal batching to work on already-large payloads
3. Maximize throughput within our latency budget

The 30-second HTTP timeout gives us room to batch aggressively when under high load, while still responding quickly when load is low.

## Brochures

Located in `brochures/` and `reports/brochures/calibration-buckets/`:

| Brochure | Description |
|----------|-------------|
| `kafka-fast` | Fire-and-forget (acks=0), maximum throughput |
| `kafka-tuned` | Optimized batch settings (acks=1) |
| `calibration-buckets` | Per-bucket calibration with regression detection |
