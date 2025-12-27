# Gateway - Boss/Worker + Microbatch

Boss/Worker HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 213543 ops/s |
| p50 Latency | 2.0 ms |
| p99 Latency | 12.6 ms |
| Total Operations | 12812575 |
| Successful | 12812575 |
| Failed | 0 |
| Duration | 1m0s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | BOSS_WORKER |
| Microbatching | true |
| Batch Size | 128 |
| Batch Timeout | 5 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-27T00:11:32-03:00
- Completed: 2025-12-27T00:15:47-03:00
- Duration: 4m14.878490709s
