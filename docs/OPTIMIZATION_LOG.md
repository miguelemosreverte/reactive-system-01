# Optimization Log

## Baseline Analysis (2025-12-22)

### Current Performance

| Metric | Value |
|--------|-------|
| Peak Throughput | 931 ops/sec |
| Avg Throughput | 495 ops/sec |
| P50 Latency | 11ms |
| P99 Latency | 39ms |

### Trace Analysis

Using `POST /api/diagnostic/run`, captured trace `578dd463e80f1d12dcbf2b7d3659f824`.

**Span Count by Service:**
- counter-application: 9 spans
- flink-taskmanager: 6 spans
- drools: 3 spans

**E2E Timeline (single request):**

| Step | Operation | Duration | Cumulative |
|------|-----------|----------|------------|
| 1 | POST /api/counter | 8.5ms | 8.5ms |
| 2 | counter.submit | 3.5ms | - |
| 3 | kafka.publish.fast | 0.5ms | - |
| 4 | counter-events publish | 7.0ms | ~7ms |
| 5 | **Kafka transit** | ~4.7ms | ~12ms |
| 6 | counter-events process | 0.3ms | - |
| 7 | flink.process_counter | 0.7ms | - |
| 8 | async.drools.enrich | 4.7ms | - |
| 9 | drools.evaluate | 0.4ms | - |
| 10 | counter-results publish | 3.7ms | ~17ms |
| 11 | counter-results consume | 0.5ms | - |
| 12 | websocket.broadcast | 0.015ms | ~15ms |

**Key Observations:**
1. Total E2E latency: ~15ms per request
2. Kafka publish takes 7ms (includes broker ack)
3. Kafka transit (producer to consumer) adds ~5ms
4. Flink processing is fast (0.7ms)
5. Drools async call is fast (4.7ms total, 0.4ms evaluation)

### Bottleneck Analysis

The per-request latency (~15ms) is not the bottleneck. At 15ms/request, theoretical max is 66 requests/sec per thread.

With 8 concurrent workers, theoretical max = 8 * 66 = 528 ops/sec (close to observed 495 avg).

**Limiting Factors to Investigate:**
1. Flink parallelism (taskmanager slots)
2. Kafka partition count
3. Consumer group parallelism
4. Connection pool sizes

---

## Investigation Phase

### Flink Parallelism Analysis

**Flink Cluster Status:**
```
taskmanagers: 1
slotsTotal: 4
slotsAvailable: 0
jobsRunning: 1
```

**Job Parallelism:**
- Source: Kafka Counter Events - parallelism 4
- KeyedProcess (processing + sinks) - parallelism 4

### Kafka Configuration

**Topic Partitions:**
- counter-events: 8 partitions
- counter-results: 8 partitions

### Identified Bottleneck

**Parallelism Mismatch:**
- Kafka has 8 partitions
- Flink has only 4 task slots

Flink can only consume from 4 partitions at a time, leaving 4 partitions underutilized.

**Theoretical Impact:**
- Current: 4 parallel consumers
- Potential: 8 parallel consumers
- Expected improvement: up to 2x throughput

---

## Optimization 1: Increase Flink Parallelism

**Change:** Increased Flink task slots from 4 to 8 to match Kafka partition count.

**Files Modified:**
- `docker-compose.yml`:
  - jobmanager: `parallelism.default: 8`
  - taskmanager: `taskmanager.numberOfTaskSlots: 8`, `parallelism.default: 8`

**Commands to Apply:**
```bash
docker compose restart flink-jobmanager flink-taskmanager
```

### Results After Optimization 1

**FAILED** - Job could not start due to insufficient resources.

**Error:** `NoResourceAvailableException: Could not acquire the minimum required resources.`

**Root Cause:**
The Flink job consists of TWO operator chains:
1. Source: Kafka Counter Events (parallelism N)
2. KeyedProcess -> Sinks (parallelism N)

With parallelism 8, the job requires 16 task slots (8 + 8), but only 8 slots are available.

**Solution Options:**
1. Add another taskmanager (doubles slots)
2. Increase memory and slots per taskmanager
3. Keep parallelism at 4 (current working configuration)

**Reverted Changes:**
- Reset `FLINK_PARALLELISM=4` in docker-compose.yml

**Lesson Learned:**
- Don't assume parallelism = task slots. Each operator chain uses its own set of slots.
- With 8 slots: max parallelism = 4 (to accommodate 2 operator chains)
- With 16 slots: max parallelism = 8

---

## Current Working Configuration

| Parameter | Value |
|-----------|-------|
| Taskmanagers | 1 |
| Slots per TM | 8 |
| Job Parallelism | 4 |
| Total Tasks | 8 (4 source + 4 keyed process) |
| Kafka Partitions | 8 |

**Kafka Partition Utilization:**
- Flink consumes from 4 of 8 partitions
- 4 partitions are underutilized (but still receive events)
- Kafka will rebalance load, but max consumer parallelism is 4

---

## Next Optimization Opportunities

1. **Add Second Taskmanager** (Recommended)
   - Would provide 16 slots total
   - Could set parallelism to 8 to match Kafka partitions
   - Expected: ~2x throughput improvement

2. **Reduce Kafka Partitions to 4**
   - Would match current parallelism
   - Simpler configuration
   - No throughput improvement

3. **Optimize Individual Component Latency**
   - Kafka publish: 7ms (could reduce with async acks)
   - Drools call: 4.7ms (already async)
   - Focus on reducing per-request latency

---

## Benchmark Comparison

### Issues Fixed Before Benchmarking

1. **Maven Build Issue**: Platform module wasn't installed in Docker Maven volume
   - Fix: `docker run ... mvn -f platform/pom.xml install`

2. **Action Case Bug**: FullBenchmark sent `"action": "INCREMENT"` but Flink expects lowercase `"increment"`
   - Fix: Changed `FullBenchmark.java` to use lowercase action

### Results (2025-12-22)

| Metric | Baseline | Current | Improvement |
|--------|----------|---------|-------------|
| Peak Throughput | 931 ops/s | 5,256 ops/s | **5.6x** |
| Avg Throughput | 495 ops/s | 1,056 ops/s | **2.1x** |
| P50 Latency | 11ms | 3ms | **3.7x faster** |
| P99 Latency | 39ms | 29ms | **1.3x faster** |

### Analysis

The significant improvement came from fixing the benchmark bugs rather than system optimization:

1. **Action case mismatch** was causing Flink to reject events (logged as "Unknown action")
2. **Proper benchmark infrastructure** (Java HTTP client vs shell curl) provides accurate measurements

### Remaining Throughput Degradation

The benchmark still shows throughput dropping from peak (~5,256/s) to low (~44/s) over time:
- This suggests backpressure from Kafka → Flink → Drools pipeline
- The async Drools calls may be creating a bottleneck when queued
- Flink parallelism (4) still below Kafka partitions (8)

### Recommendations

1. **Add second taskmanager** to enable parallelism 8
2. **Tune async capacity** (currently ASYNC_CAPACITY=200)
3. **Profile Drools latency** under sustained load

---

## Meta-Optimization: Benchmarking the Benchmark (2025-12-22)

### Goal

Reduce the turnaround time for getting benchmark results and improve the quality of feedback.

### Turnaround Time Analysis

**Before Optimization (60s benchmark):**
- Total wall-clock time: ~75s
- Breakdown:
  - Compilation: ~7s
  - Warmup: 3s
  - Measurement: 60s
  - Cooldown: 2s
  - Trace enrichment: ~8s (fetching from Jaeger/Loki)
- Overhead ratio: 1.25x

**Before Optimization (10s benchmark):**
- Total wall-clock time: ~29s
- Overhead ratio: 2.9x
- Major overhead: trace enrichment (8s)

### Quick Mode Implementation

Added `--quick` flag to benchmark CLI:

```bash
./cli.sh benchmark full --quick
```

**Quick Mode Settings:**
- Duration: 5s (vs 60s default)
- Warmup: 1s (vs 3s default)
- Cooldown: 0s (vs 2s default)
- Trace enrichment: skipped

**Files Modified:**
- `platform/.../BenchmarkTypes.java`: Added `skipEnrichment`, `quickMode` flags
- `platform/.../BenchmarkResult.java`: Added `throughputStability` metric
- `platform/.../BaseBenchmark.java`: Skip enrichment in quick mode
- `platform/.../BenchmarkCli.java`: Added `--quick` flag
- `scripts/run-benchmarks-java.sh`: Support for quick mode

### Results After Meta-Optimization

| Mode | Duration | Wall-clock | Overhead |
|------|----------|------------|----------|
| Default | 60s | ~75s | 1.25x |
| Quick | 5s | ~14s | 2.8x |

**Quick mode provides ~5x faster feedback** (14s vs 75s) for rapid iteration.

### Throughput Stability Metric

Added coefficient of variation (CV) to measure result consistency:

```
CV = standard_deviation / mean
```

- Lower CV = more stable throughput
- CV < 0.2 = highly stable
- CV > 0.5 = unstable (investigate backpressure)

**Quick Mode Results:**
```
Peak throughput: 2009 ops/sec
Avg throughput: 1398 ops/sec
Stability: 0.59 (CV - unstable, as expected under load)
```

### Workflow Recommendation

1. **Development iteration**: Use `--quick` for fast feedback (~14s)
2. **Pre-commit verification**: Use default mode for accurate metrics (~75s)
3. **CI/CD pipeline**: Use full 60s+ benchmarks with trace enrichment

### Next Steps

1. Add automatic bottleneck detection from trace analysis
2. Add benchmark timing to HTML report (meta-metrics display)
3. Improve throughput stability through Flink/Kafka tuning

