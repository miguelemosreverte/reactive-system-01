# Benchmark Report: full

**Full end-to-end (HTTP → Kafka → Flink → Drools)**

| Metric | Value |
|--------|-------|
| Output Peak | **18688 events/sec** |
| Output Average | 16180 events/sec |
| Input Peak | 18800 events/sec |
| Events Sent | 192600 |
| Successful | 192598 |
| Success Rate | 100% |
| Duration | 14s |

## Latency

| Percentile | Value |
|------------|-------|
| P50 (Median) | 19ms |
| P95 | 67ms |
| P99 | 127ms |
| Max | 176ms |

## Component Timing (CQRS Architecture)

| Component | Time | Notes |
|-----------|------|-------|
| Gateway | 0ms | HTTP processing (0ms = benchmark bypassed HTTP) |
| Kafka | 4ms | Message transit (main latency component) |
| Flink | <1ms | Stream processing (sub-millisecond) |
| Drools | N/A | Timer-based evaluation (not per-event) |

> **Note:** This benchmark measures Gateway → Kafka → Flink latency.
> Drools evaluation happens on a timer (every 50-500ms) and is not included in per-event timing.

---
*Generated: 2025-12-19 16:02:59 | Commit: 354d89d*
