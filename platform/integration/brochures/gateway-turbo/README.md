# Gateway - Turbo + Microbatch

Turbo HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 11180 ops/s |
| p50 Latency | 43.7 ms |
| p99 Latency | 224.0 ms |
| Total Operations | 112441 |
| Successful | 112441 |
| Failed | 0 |
| Duration | 10s |

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

- Started: 2025-12-26T21:13:51-03:00
- Completed: 2025-12-26T21:16:15-03:00
- Duration: 2m23.877967709s
