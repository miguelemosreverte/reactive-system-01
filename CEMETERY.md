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
