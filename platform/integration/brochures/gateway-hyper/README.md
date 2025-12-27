# Gateway - Hyper + Microbatch

Hyper HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 14387 ops/s |
| p50 Latency | 34.1 ms |
| p99 Latency | 302.0 ms |
| Total Operations | 144804 |
| Successful | 144804 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | HYPER |
| Microbatching | true |
| Batch Size | 128 |
| Batch Timeout | 5 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:03:55-03:00
- Completed: 2025-12-26T21:06:15-03:00
- Duration: 2m20.524049084s
