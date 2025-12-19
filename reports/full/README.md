# Benchmark Report: Full End-to-End

**HTTP → Kafka → Flink → Drools**

| Metric | Value |
|--------|-------|
| Peak Throughput | **1606 events/sec** |
| Average Throughput | 1275 events/sec |
| Total Operations | 20100 |
| Successful | 20100 |
| Failed | 0 |
| Success Rate | 100% |
| Duration | 17s |

## Latency

| Percentile | Value |
|------------|-------|
| Min | 37ms |
| P50 (Median) | 90ms |
| P95 | 211ms |
| P99 | 242ms |
| Max | 296ms |

## Component Timing

| Component | Time |
|-----------|------|
| Gateway | 0ms |
| Kafka | 2ms |
| Flink | 0ms |
| Drools | 0ms |

## Resources

| Metric | Value |
|--------|-------|
| Peak CPU | 16% |
| Peak Memory | 91% |

---
*Generated: 2025-12-19 19:09:04 | Commit: 354d89d*
