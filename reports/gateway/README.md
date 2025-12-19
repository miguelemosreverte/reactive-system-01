# Benchmark Report: Gateway

**HTTP + Kafka publish (no wait)**

| Metric | Value |
|--------|-------|
| Peak Throughput | **1696 events/sec** |
| Average Throughput | 1363 events/sec |
| Total Operations | 21120 |
| Successful | 21120 |
| Failed | 0 |
| Success Rate | 100% |
| Duration | 17s |

## Latency

| Percentile | Value |
|------------|-------|
| Min | 2ms |
| P50 (Median) | 4ms |
| P95 | 9ms |
| P99 | 42ms |
| Max | 79ms |

## Component Timing

| Component | Time |
|-----------|------|
| Gateway | 0ms |
| Kafka | 0ms |
| Flink | 0ms |
| Drools | 0ms |

## Resources

| Metric | Value |
|--------|-------|
| Peak CPU | 16% |
| Peak Memory | 91% |

---
*Generated: 2025-12-19 19:09:03 | Commit: 354d89d*
