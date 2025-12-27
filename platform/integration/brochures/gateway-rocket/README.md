# Gateway - Rocket + Microbatch

Rocket HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 13203 ops/s |
| p50 Latency | 37.4 ms |
| p99 Latency | 374.0 ms |
| Total Operations | 132399 |
| Successful | 132398 |
| Failed | 1 |
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

- Started: 2025-12-26T21:10:58-03:00
- Completed: 2025-12-26T21:13:17-03:00
- Duration: 2m19.783280875s
