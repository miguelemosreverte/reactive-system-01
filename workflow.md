# Codebase Cleanup Workflow

## Overview

This document describes the iterative cleanup workflow used to improve code quality, maintainability, and performance of the reactive system codebase.

## Guiding Principles

1. **DRY (Don't Repeat Yourself)** - Eliminate duplicate code patterns
2. **HOCON Configuration** - Externalize hardcoded values to config files
3. **Interface Abstraction** - Minimize exposure to third-party libraries
4. **Optional Patterns** - Replace null with Optional for safety
5. **Functional Programming** - Prefer immutable data, streams, lambdas

## Protected Code

**DO NOT MODIFY** performance-critical implementations:
- `PartitionedBatcher.java` - Highest throughput batcher (1.1B msg/s)
- `FastRingBuffer.java` - Lock-free ring buffer
- Other batcher implementations in `gateway/microbatch/`

## Iteration Workflow

### Step 1: Analyze
- Search for duplicate patterns (3+ occurrences)
- Find hardcoded numeric values
- Identify null checks that could use Optional
- Look for third-party types leaking through interfaces

### Step 2: Prioritize
| Priority | Criteria |
|----------|----------|
| HIGH | Affects multiple files, high impact, low-medium effort |
| MEDIUM | Localized impact, medium effort |
| LOW | Cosmetic, housekeeping |

### Step 3: Implement
- Create shared utilities for duplicate patterns
- Move constants to `reference.conf` / `application.yml`
- Wrap third-party types in interfaces
- Replace null with Optional

### Step 4: Verify
- Ensure code compiles: `mvn compile`
- Run tests if applicable
- Check no regressions in functionality

### Step 5: Commit
- Descriptive commit message
- Reference the iteration number
- List files changed

## Completed Iterations

### Iteration 1-2: Foundation
- Created `BenchmarkConstants`, `ProducerFactory`, `FormattingUtils`
- Created `BenchmarkResult`, `KafkaVerifier`
- Deleted deprecated batcher benchmarks
- Extracted cli.sh magic numbers

### Iteration 3: Configuration & Abstraction
- Added `kafka-benchmark` section to `reference.conf`
- Created `KafkaBenchmarkConfig` in `PlatformConfig`
- Created `Publisher` interface to abstract Kafka
- Added Optional patterns in benchmark files

### Iteration 4: Utilities & Gateway Config
- Created `BenchmarkThreading` utility (eliminates 23+ duplicate patterns)
- Created `RateLimiter` utility (eliminates 6+ duplicate patterns)
- Externalized Gateway `KafkaConfig` to `application.yml`
- Expanded `FormattingUtils` usage

## Configuration Files

| File | Purpose |
|------|---------|
| `platform/src/main/resources/reference.conf` | HOCON defaults for platform |
| `platform/gateway/src/main/resources/application.yml` | Spring Boot gateway config |
| `platform/kafka/src/main/resources/reference.conf` | Kafka module defaults |

## Key Utility Classes

| Class | Purpose |
|-------|---------|
| `BenchmarkConstants` | Centralized benchmark configuration (loads from HOCON) |
| `BenchmarkThreading` | Thread pool and parallel execution utilities (eliminates 23+ duplicates) |
| `RateLimiter` | Throughput limiting for benchmarks (eliminates 6+ duplicates) |
| `ProducerFactory` | Kafka producer creation with presets |
| `FormattingUtils` | Human-readable formatting (throughput, bytes, time) |
| `BenchmarkResult` | Shared result types (RunResult, PhaseResult, etc.) |
| `Publisher<A>` | Abstract interface hiding Kafka types |
| `JsonCodec.JsonConfig` | Builder for JSON codec config (hides Jackson) |

## Usage Examples

### BenchmarkThreading
```java
// Run timed benchmark across all CPUs
long total = BenchmarkThreading.runTimed(durationSec, (threadId, endTimeNanos) -> {
    long count = 0;
    while (System.nanoTime() < endTimeNanos) {
        doWork();
        count++;
    }
    return count;
});
```

### RateLimiter
```java
RateLimiter limiter = RateLimiter.perSecond(1_000_000); // 1M ops/sec
while (running) {
    doWork();
    limiter.acquire();
}
```

### JsonCodec with config
```java
// Before: JsonCodec.forClass(Event.class, objectMapper)  // exposed Jackson
// After:
Codec<Event> codec = JsonCodec.forClass(Event.class, c -> c.lenient().prettyPrint());
```

## Benchmark Verification

After completing iterations, run the full benchmark:

```bash
# Start infrastructure
./cli.sh start

# Run Kafka baseline benchmark
mvn -q exec:java -Dexec.mainClass="com.reactive.platform.kafka.benchmark.KafkaBaselineBenchmark" \
  -Dexec.args="BULK 10 localhost:9092" -pl platform/kafka

# Expected: 15,000+ msg/s baseline
```

## File Naming Conventions

- `*Config.java` - Configuration classes
- `*Factory.java` - Factory patterns
- `*Utils.java` - Static utility methods
- `*Benchmark.java` - Performance benchmarks
- `*Test.java` - Unit tests
