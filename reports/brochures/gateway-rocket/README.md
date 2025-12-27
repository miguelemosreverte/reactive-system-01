# Gateway - Rocket + Microbatch

Rocket HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 213874 ops/s |
| p50 Latency | 2.0 ms |
| p99 Latency | 12.0 ms |
| Total Operations | 12832469 |
| Successful | 12832469 |
| Failed | 0 |
| Duration | 1m0s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | ROCKET |
| Microbatching | true |
| Batch Size | 128 |
| Batch Timeout | 5 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-27T00:28:38-03:00
- Completed: 2025-12-27T00:32:54-03:00
- Duration: 4m15.503521791s
