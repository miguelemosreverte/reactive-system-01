# Benchmark Report: Drools

**Rule evaluation via direct HTTP**

| Metric | Value |
|--------|-------|
| Peak Throughput | **4176 events/sec** |
| Average Throughput | 3519 events/sec |
| Total Operations | 55080 |
| Successful | 55080 |
| Failed | 0 |
| Success Rate | 100% |
| Duration | 17s |

## Latency

| Percentile | Value |
|------------|-------|
| Min | 0ms |
| P50 (Median) | 1ms |
| P95 | 3ms |
| P99 | 16ms |
| Max | 77ms |

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
