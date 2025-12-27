# Gateway - Hyper + Microbatch

Hyper HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 208619 ops/s |
| p50 Latency | 2.0 ms |
| p99 Latency | 12.7 ms |
| Total Operations | 12517112 |
| Successful | 12517112 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:15:47-03:00
- Completed: 2025-12-27T00:20:04-03:00
- Duration: 4m16.616774208s
