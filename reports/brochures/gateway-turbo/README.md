# Gateway - Turbo + Microbatch

Turbo HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 208249 ops/s |
| p50 Latency | 2.0 ms |
| p99 Latency | 13.3 ms |
| Total Operations | 12494948 |
| Successful | 12494948 |
| Failed | 0 |
| Duration | 1m0s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | TURBO |
| Microbatching | true |
| Batch Size | 128 |
| Batch Timeout | 5 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-27T00:33:28-03:00
- Completed: 2025-12-27T00:37:44-03:00
- Duration: 4m16.55907575s
