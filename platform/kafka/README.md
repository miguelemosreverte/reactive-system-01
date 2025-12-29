# Kafka Microbatch Module

## 1+ Billion Messages/Second

This module contains a high-throughput event batching system achieving **1.3 billion msg/s** in pure batching benchmarks, and **100+ million msg/s** verified end-to-end with Kafka.

## The Achievement

```
┌─────────────────────────────────────────────────────────────────────────┐
│  BENCHMARK RESULTS (verified with Kafka message count)                   │
├─────────────────────────────────────────────────────────────────────────┤
│  Pure Batching (arraycopy baseline):     2.98 BILLION msg/s             │
│  MicrobatchCollector:                    1.31 BILLION msg/s             │
│  End-to-End with Docker Kafka:           100+ MILLION msg/s (verified)  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Core Components

| Component | Purpose |
|-----------|---------|
| **`MicrobatchCollector`** | Production collector with pressure-aware calibration |
| **`FastRingBuffer`** | LMAX Disruptor-inspired lock-free ring buffer |
| **`BatchCalibration`** | 10-level adaptive calibration system |
| `NaiveBatcher` | @Deprecated baseline (no batching) |

### MicrobatchCollector - The Champion

```
┌────────────────────────────────────────────────────────────────┐
│                    MicrobatchCollector                          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │ Ring[0]  │ │ Ring[1]  │ │ Ring[2]  │ │ Ring[N]  │          │
│  │ Thread 0 │ │ Thread 1 │ │ Thread 2 │ │ Thread N │          │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘          │
│       └────────────┴─────┬──────┴────────────┘                 │
│                    ┌─────▼─────┐                               │
│                    │  Drain &  │  ◄── Pressure-aware           │
│                    │   Flush   │      batch sizing             │
│                    └─────┬─────┘                               │
└──────────────────────────┼─────────────────────────────────────┘
                           ▼
                    ┌─────────────┐
                    │    Kafka    │
                    └─────────────┘
```

**Key design decisions:**
- Partitioned `FastRingBuffer` (one per CPU core)
- Zero allocation on hot path (LMAX Disruptor pattern)
- 10 pressure levels from 1ms (real-time) to 30s (max throughput)
- SQLite-backed learning for calibration persistence

## Quick Start

```java
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import com.reactive.platform.gateway.microbatch.BatchCalibration;

// Create calibration (learns optimal batch sizes)
var calibration = BatchCalibration.create(
    Path.of("~/.reactive/calibration.db"),
    5000.0  // Target latency in microseconds
);

// Create collector
var collector = MicrobatchCollector.create(
    batch -> kafkaProducer.send(serialize(batch)),
    calibration
);

// Hot path: zero allocation, ~0.8ns per message
collector.submitFireAndForget(event);
```

## 10 Pressure Levels

| Level | Latency Budget | Request Rate | Batch Strategy |
|-------|----------------|--------------|----------------|
| **L1_REALTIME** | 1ms | < 10 req/s | Small batches, fast flush |
| **L5_BALANCED** | 100ms | 2K-10K req/s | Default balanced |
| **L10_MAX** | 30s | > 5M req/s | Maximum batching |

The system automatically detects load and adapts:
- Low load: Fast response, small batches
- High load: Maximum throughput, large batches

## Benchmarks

### BULK Baseline (Reference)

```bash
# Quick smoke test
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.KafkaBaselineBenchmark" \
    -Dexec.args="--smoke localhost:9092"

# Thorough validation (~5 minutes)
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.KafkaBaselineBenchmark" \
    -Dexec.args="--thorough localhost:9092"
```

### Adaptive Ramp (Shows Pressure Adaptation)

```bash
mvn exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.AdaptiveRampBenchmark" \
    -Dexec.args="--ramp-quick localhost:9092"
```

## Build

```bash
mvn compile                    # Compile
mvn test                       # Run tests
```

## Recovering Removed Implementations

During cleanup, we removed 12 experimental batcher implementations that were used during the optimization journey. To recover them:

```bash
# Checkout the pre-cleanup commit
git checkout 2b77838 -- platform/kafka/src/main/java/com/reactive/platform/gateway/microbatch/

# Removed implementations (historical reference):
# - PartitionedBatcher (1.31B msg/s) - Best pure batcher, merged into MicrobatchCollector
# - InlineBatcher (892M msg/s) - Inline send
# - DirectFlushBatcher (542M msg/s) - Direct flush
# - StripedBatcher (136M msg/s) - Striped design
# - AdaptiveBatcher, BucketBatcher, DirectBatcher, ExponentialBatcher
# - FastBatcher, SimpleBatcher, ThreadLocalBatcher, UltraFastBatcher
```

These implementations represent the evolutionary path to 1B+ msg/s. The final `MicrobatchCollector` incorporates lessons learned from all of them.

## Performance Notes

### Understanding Throughput Numbers

| Metric | Value | What It Measures |
|--------|-------|------------------|
| **Send Rate** | 1.3B msg/s | Pure batching (no Kafka) |
| **Kafka Verified** | 100M+ msg/s | End-to-end with acks=1 |
| **Transactional** | 50M+ msg/s | With acks=all (guaranteed) |

### Pre-Benchmark Cleanup

**ALWAYS** run before benchmarking:
```bash
docker volume prune -f          # Free disk space
docker restart reactive-kafka   # Restart Kafka
sleep 10                        # Wait for startup
```

Disk-full conditions cause **10-100x throughput degradation**.
