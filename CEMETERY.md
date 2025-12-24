# Optimization Cemetery

This document tracks rejected optimizations that were tested but did not improve performance. Each entry preserves the work done, explains why it was rejected, and provides a branch reference for future investigation.

## Purpose

When optimizing a system, many promising ideas turn out to be counterproductive. Rather than losing this knowledge, we document:

1. **What was tried** - The specific change and rationale
2. **What happened** - Benchmark results showing the regression
3. **Why it failed** - Root cause analysis
4. **Branch reference** - Preserved code for future re-evaluation

This prevents:
- Repeating the same failed experiments
- Losing valuable insights about system behavior
- Wasting time on approaches already proven ineffective

---

## Rejected Optimizations

### 1. Flink Kafka Producer Batching Increase

**Date:** 2024-12-24
**Branch:** `cemetery/flink-kafka-linger-5ms`
**Status:** REJECTED - Causes regression

#### Change Description
Aligned Flink's Kafka producer settings with the application module:
- `linger.ms`: 1 → 5
- `batch.size`: 32KB → 64KB

#### Rationale
The application module uses these higher batching settings successfully. The hypothesis was that consistent settings across the pipeline would improve throughput.

#### Benchmark Results
| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Total Ops | ~70K | ~50K | -29% |
| Peak Throughput | 33K/s | 30K/s | -9% |
| P99 Latency | 3ms | ~5ms | +67% |

#### Root Cause Analysis
**Flink is latency-sensitive in the middle of the pipeline.**

The key insight is that different components have different optimal settings based on their position in the data flow:

```
[Application] → [Kafka] → [Flink] → [Drools] → [Kafka] → [Consumer]
     ^                        ^
     |                        |
  Edge: Batching OK      Middle: Low latency needed
```

- **Application (Edge):** Events arrive in bursts from HTTP requests. Batching (5ms linger) allows grouping multiple events efficiently before entering the pipeline.

- **Flink (Middle):** Each event's latency compounds. A 5ms delay in Flink means the result takes 5ms longer to reach the consumer. At high throughput, this creates backpressure.

- **Result:** The 5ms linger in Flink added latency to every event in the pipeline, reducing the rate at which complete request-response cycles could finish.

#### Files Changed
- `platform/deployment/docker/flink/src/main/java/com/reactive/flink/CounterJob.java`

#### How to Re-test
```bash
git checkout cemetery/flink-kafka-linger-5ms
./reactive rebuild flink
# Run 10+ warmup iterations
for i in {1..10}; do ./reactive bench full --quick; done
# Compare with main branch
```

#### Conditions That Might Change This Decision
- If the benchmark changes to measure throughput without waiting for results
- If Flink parallelism increases significantly (more events processed per linger window)
- If downstream consumers become the bottleneck instead of pipeline latency

---

### 2. Flink Checkpoint Interval Increase

**Date:** 2024-12-24
**Branch:** `cemetery/flink-checkpoint-10s`
**Status:** REJECTED - Causes regression

#### Change Description
Increased Flink checkpointing interval from 5 seconds to 10 seconds:
- `env.enableCheckpointing(5000)` → `env.enableCheckpointing(10000)`

#### Rationale
Checkpointing adds overhead as Flink must snapshot state to durable storage. Reducing checkpoint frequency should reduce this overhead and allow more resources for actual processing.

#### Benchmark Results
| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Total Ops | 315,506 | 291,176 | -8% |
| Peak Throughput | 31,589/s | 22,577/s | -29% |
| Avg Throughput | 4,852/s | 4,477/s | -8% |
| P99 Latency | 6ms | 9ms | +50% |

#### Root Cause Analysis
**Flink's Kafka source depends on checkpoints for offset commits.**

The Kafka source in Flink commits offsets during checkpoints. With a 10-second interval:
1. Offsets are committed less frequently
2. If the consumer falls behind, it takes longer to recognize and handle backpressure
3. The consumer can accumulate more unprocessed messages before acknowledging them
4. This creates memory pressure and slower recovery from any hiccups

The 5-second interval provides better feedback loop for the Kafka consumer to stay synchronized with actual processing capacity.

#### Files Changed
- `platform/deployment/docker/flink/src/main/java/com/reactive/flink/CounterJob.java`

#### How to Re-test
```bash
git checkout cemetery/flink-checkpoint-10s
./cli.sh rebuild flink
# Run 10+ warmup iterations
for i in {1..10}; do ./cli.sh bench full; done
# Compare with main branch
```

#### Conditions That Might Change This Decision
- If using incremental checkpointing with RocksDB (more efficient checkpoints)
- If running with more parallelism (less per-task state to checkpoint)
- If the Kafka consumer lag monitoring shows checkpoints aren't the bottleneck

---

### 3. Flink Parallelism Increase

**Date:** 2024-12-24
**Branch:** `cemetery/flink-parallelism-12`
**Status:** REJECTED - No improvement

#### Change Description
Increased Flink parallelism from 8 to 12:
- `FLINK_PARALLELISM`: 8 → 12

#### Rationale
More parallel threads should allow better CPU utilization and higher throughput, especially with 8 Kafka partitions allowing up to 8 concurrent consumers.

#### Benchmark Results
| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Total Ops | ~316K | ~295K | -7% |
| Consistency | Stable | Variable | Worse |

#### Root Cause Analysis
**The bottleneck is network I/O, not CPU.**

With 12 threads but only 8 Kafka partitions:
1. 4 threads have no partitions to consume from (idle)
2. More threads compete for the same HTTP connection pool to Drools
3. Context switching overhead increases
4. No improvement in actual processing capacity

The system is limited by:
- Kafka partition count (8)
- HTTP connection pool to Drools (200)
- Drools processing capacity

#### Files Changed
- `platform/deployment/docker/flink/src/main/java/com/reactive/flink/CounterJob.java`

#### How to Re-test
```bash
git checkout cemetery/flink-parallelism-12
./cli.sh rebuild flink
for i in {1..10}; do ./cli.sh bench full; done
```

#### Conditions That Might Change This Decision
- If Kafka partition count is increased to 12+
- If running on a machine with more CPU cores
- If Drools becomes faster and HTTP I/O is no longer the bottleneck

---

### 4. Async Capacity Increase to 1000

**Date:** 2024-12-24
**Branch:** `cemetery/flink-async-capacity-1000`
**Status:** REJECTED - Overwhelms Drools

#### Change Description
Increased ASYNC_CAPACITY from 250 to 1000:
- Docker-compose: `ASYNC_CAPACITY=250` → `ASYNC_CAPACITY=1000`
- Code default: aligned to 1000

#### Rationale
Higher async capacity should allow more concurrent Drools calls, overlapping HTTP latency and improving throughput. With 200 HTTP connections available, 1000 capacity should better utilize the connection pool.

#### Benchmark Results
| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Total Ops | 315,901 | 198,657 | -37% |
| Peak Throughput | 30,091/s | 13,792/s | -54% |
| Avg Throughput | 4,858/s | 3,053/s | -37% |
| P99 Latency | 6ms | 17ms | +183% |

#### Root Cause Analysis
**Too much concurrency overwhelms Drools.**

The async capacity controls how many Drools calls can be in-flight simultaneously. At 1000 concurrent calls:
1. Drools service receives 1000 simultaneous requests
2. Drools thread pool (400 threads) becomes saturated
3. Requests queue up, increasing latency
4. Backpressure propagates back through the pipeline
5. Overall throughput drops significantly

The optimal value (250) balances:
- Enough concurrency to hide HTTP latency
- Not so much that Drools becomes overwhelmed
- Memory pressure from 1000 in-flight requests vs 250

#### Files Changed
- `docker-compose.yml` (ASYNC_CAPACITY environment variable)
- `platform/deployment/docker/flink/src/main/java/com/reactive/flink/CounterJob.java`

#### How to Re-test
```bash
git checkout cemetery/flink-async-capacity-1000
docker compose up -d flink-taskmanager flink-jobmanager
# Verify env: docker compose exec flink-taskmanager env | grep ASYNC
for i in {1..10}; do ./cli.sh bench full; done
```

#### Conditions That Might Change This Decision
- If Drools thread pool is increased significantly (>1000 threads)
- If running multiple Drools replicas behind a load balancer
- If Drools processing time decreases substantially

---

## Template for New Entries

```markdown
### N. [Optimization Name]

**Date:** YYYY-MM-DD
**Branch:** `cemetery/branch-name`
**Status:** REJECTED - [Brief reason]

#### Change Description
[What was changed]

#### Rationale
[Why this seemed like a good idea]

#### Benchmark Results
| Metric | Before | After | Change |
|--------|--------|-------|--------|
| ... | ... | ... | ... |

#### Root Cause Analysis
[Why it didn't work]

#### Files Changed
- [file paths]

#### How to Re-test
[Commands to reproduce]

#### Conditions That Might Change This Decision
[When to reconsider]
```

---

## Branch Naming Convention

Cemetery branches follow the pattern:
```
cemetery/<component>-<brief-description>
```

Examples:
- `cemetery/flink-kafka-linger-5ms`
- `cemetery/drools-parallel-sessions`
- `cemetery/gateway-http2-enabled`
