# Gateway - Rocket + Microbatch

Rocket HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 293645 ops/s |
| p50 Latency | 1.5 ms |
| p99 Latency | 8.1 ms |
| Total Operations | 2936447 |
| Successful | 2936447 |
| Failed | 0 |
| Duration | 10s |

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

- Started: 2025-12-29T14:46:23-03:00
- Completed: 2025-12-29T14:46:54-03:00
- Duration: 31.784160709s
