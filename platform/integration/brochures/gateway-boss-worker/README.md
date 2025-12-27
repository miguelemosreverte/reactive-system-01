# Gateway - Boss/Worker + Microbatch

Boss/Worker HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 9801 ops/s |
| p50 Latency | 50.0 ms |
| p99 Latency | 470.6 ms |
| Total Operations | 98233 |
| Successful | 98233 |
| Failed | 0 |
| Duration | 10s |

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

- Started: 2025-12-26T21:01:30-03:00
- Completed: 2025-12-26T21:03:55-03:00
- Duration: 2m24.5042s
