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

### Iteration 5: Optional Patterns
- Replaced null checks with Optional in `PlatformConfig`
- Updated `CounterState.fromString()` to use Optional patterns

### Iteration 6: KafkaPublisher Configuration
- Added `publisher` section to `reference.conf` with presets
- Created `PublisherConfig` class with `FireAndForgetConfig` and `HighThroughputConfig`
- Updated `KafkaPublisher.Builder` to load defaults from HOCON
- Added `fireAndForget()` and `highThroughput()` preset methods

### Iteration 7: Microbatch Configuration
- Added `microbatch` section to `reference.conf`
- Created `MicrobatchConfig` class in `PlatformConfig`
- Updated `MicrobatchCollector` to use config for pressure window
- Updated `FastRingBuffer` to use config for default capacity

### Iteration 8: Shared BenchmarkResult Types
- Added `SimpleResult` and `BatchExplorationResult` to `BenchmarkResult.java`
- Updated `BatchSizeExplorer` to use shared result types

### Iteration 9: Hide Jackson Types
- Removed public `mapper()` method from `JsonCodec`
- Added `JsonConfig` builder with fluent API: `strict()`, `lenient()`, `prettyPrint()`, `datesAsStrings()`
- Jackson `ObjectMapper` is now internal implementation detail

### Iteration 10: Clean Wildcard Imports
- Expanded wildcard imports in `FastGateway.java`
- Expanded wildcard imports in `MicrobatchingGateway.java`

### Iteration 11-12: Documentation
- Updated workflow.md with usage examples
- Documented all utility classes with purposes

### Iteration 13: Dead Code Cleanup
- Verified no stale TODOs remain in codebase

### Iteration 14: Gateway Producer Optimization
- Documented high-throughput Kafka producer config in `application.yml`
- Verified optimal settings: 128KB batch, 5ms linger, LZ4 compression

### Iteration 15: Final Benchmark Verification
- Ran full Kafka baseline benchmark
- **Result: 104.9M msg/s** (1.05 billion messages in 10 seconds)
- All messages verified in Kafka

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

# Expected: 100M+ msg/s (BULK mode with batching)
```

## File Naming Conventions

- `*Config.java` - Configuration classes
- `*Factory.java` - Factory patterns
- `*Utils.java` - Static utility methods
- `*Benchmark.java` - Performance benchmarks
- `*Test.java` - Unit tests
