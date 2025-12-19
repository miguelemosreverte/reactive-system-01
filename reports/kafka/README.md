# Benchmark Report: Kafka

**Kafka produce/consume round-trip**

| Metric | Value |
|--------|-------|
| Peak Throughput | **22400 events/sec** |
| Average Throughput | 17808 events/sec |
| Total Operations | 284900 |
| Successful | 284900 |
| Failed | 0 |
| Success Rate | 100% |
| Duration | 17s |

## Latency

| Percentile | Value |
|------------|-------|
| Min | 0ms |
| P50 (Median) | 6ms |
| P95 | 20ms |
| P99 | 33ms |
| Max | 69ms |

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
| Peak Memory | 92% |

---
*Generated: 2025-12-19 19:09:02 | Commit: 354d89d*
