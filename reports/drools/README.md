# Benchmark Report: Drools Benchmark

**Direct Drools rule evaluation via HTTP. NOTE: In CQRS architecture, Drools processes global snapshots periodically (decoupled from main event flow). This benchmark measures raw capacity, not a bottleneck.**

| Metric | Value |
|--------|-------|
| Peak Throughput | **5481 events/sec** |
| Average Throughput | 4228 events/sec |
| Total Operations | 47197 |
| Successful | 47197 |
| Failed | 0 |
| Success Rate | 100% |
| Duration | 10s |

## Latency

| Percentile | Value |
|------------|-------|
| Min | 0ms |
| P50 (Median) | 0ms |
| P95 | 2ms |
| P99 | 56ms |
| Max | 306ms |

## Resources

| Metric | Value |
|--------|-------|
| Peak CPU | 63% |
| Peak Memory | 85% |

---
*Generated: 2025-12-21 00:47:21 | Go Benchmark Tool*
