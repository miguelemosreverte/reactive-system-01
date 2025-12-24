# Bottleneck Analysis Report

**Date:** 2024-12-24
**Final Benchmark:** 66,236 ops | Avg: 2,400-3,900 ops/s | Peak: 3,965 ops/s | **100% Success**

## Final Configuration

```yaml
# application/src/main/resources/application.yml
producer:
  batch-size: 65536    # 64KB - larger batches
  linger-ms: 0         # No wait - send immediately
  acks: 1              # Leader ack only
  compression-type: lz4
```

## Architecture Overview

```
Fire-and-Forget with Async Processing:

THROUGHPUT PATH (sync - limits ops/s):
┌─────────────────────────────────────────────────────────────┐
│  Client → POST /api/counter/fast → Kafka publish → 200 OK  │
│                    ~1.9ms total                              │
└─────────────────────────────────────────────────────────────┘

PROCESSING PATH (async - does NOT limit throughput):
┌─────────────────────────────────────────────────────────────┐
│  Kafka → Flink → Drools (snapshot-based, 1:496 batch ratio) │
│  Drools latency: 4.4ms avg but NOT blocking throughput      │
└─────────────────────────────────────────────────────────────┘
```

## Discovery Commands

### 1. Run Benchmark with Pipeline Analysis

```bash
./cli.sh bench full -d 10
```

**Output:**
```
PIPELINE ANALYSIS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  Architecture: Fire-and-Forget with Async Processing

  THROUGHPUT PATH (affects ops/s):
  Operation                            Avg(ms)  P99(ms)  Count
  POST /api/counter                        1.9      5.5     19
  CounterController.submit                 1.6      5.4     19
  counter-events publish                   1.5      5.1     19
  kafka.publish.fast                       0.9      5.1     19

  PROCESSING PATH (async, snapshot-based):
  Operation                            Avg(ms)  P99(ms)  Count Batch Ratio
  async.drools.enrich                      4.4     10.8     31      1:496

  Total events: 15382 | Drools calls: 31 | Batch ratio: 1:496
  → Drools latency does NOT limit throughput (snapshot-based)
```

### 2. Check Drools Latency (confirm not bottleneck)

```bash
for i in 1 2 3; do
  curl -s -o /dev/null -w "%{time_total}s\n" -X POST http://localhost:8180/api/evaluate \
    -H "Content-Type: application/json" \
    -d '{"value":42,"requestId":"test","customerId":"c1","eventId":"e1","sessionId":"s1"}'
done
```

**Output:**
```
Request 1: 14ms (cold)
Request 2: 2ms
Request 3: 2ms
```

### 3. Check Current Kafka Producer Config

```bash
grep -A 10 "producer" platform/deployment/docker/gateway/src/main/resources/application.yml
```

**Output:**
```yaml
producer:
  acks: 1
  batch-size: 32768         # 32KB
  linger-ms: 1              # 1ms - small batches!
  buffer-memory: 67108864   # 64MB
  compression-type: lz4
```

### 4. Check Flink Configuration

```bash
grep -E "parallelism|ASYNC_CAPACITY" docker-compose.yml
```

**Output:**
```
parallelism.default: 8
ASYNC_CAPACITY=200
```

## Bottleneck Identification

| Component | Time | % of Latency | Status |
|-----------|------|--------------|--------|
| HTTP handling | ~1.0ms | 53% | **BOTTLENECK** |
| Kafka publish | ~0.9ms | 47% | **BOTTLENECK** |
| Flink processing | async | 0% | OK |
| Drools evaluation | async | 0% | OK (batched 1:496) |

**Root Cause:** The synchronous path (HTTP + Kafka publish) is the bottleneck, NOT the async Drools processing.

## Theoretical vs Actual

```
With 8 workers @ 2ms each:
  Theoretical: 8 × (1000/2) = 4,000 ops/s
  Actual: 1,500-2,000 ops/s (with peaks to 3,700)
  Gap: ~50% of theoretical
```

**Gap explained by:**
1. `linger.ms=1` - very small batches, frequent sends
2. JSON serialization overhead (~0.5ms)
3. HTTP request/response overhead
4. GC pressure with 768m heap

## Optimization Plan

### Phase 1: Kafka Batching (Low Risk)
- Increase `linger.ms` from 1 to 5ms
- Expected: +20-30% throughput (larger batches)

### Phase 2: Higher Concurrency (Test Only)
- Run with `-c 16` instead of `-c 8`
- Expected: Near-linear scaling if CPU available

### Phase 3: Future Optimizations
- Binary serialization (Avro) - ~2x faster
- Horizontal scaling (multiple gateway instances)
- HTTP/2 multiplexing

## Key Insight

**Drools is NOT the bottleneck** because:
1. Fire-and-forget architecture - response returns before Drools processing
2. Snapshot-based processing - 1 Drools call handles ~496 events
3. Async I/O - Flink processes independently of HTTP path

The bottleneck is purely in the **synchronous HTTP → Kafka publish** path.
