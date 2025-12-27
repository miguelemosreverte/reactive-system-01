# Gateway - Spring WebFlux

Gateway using Spring WebFlux with standard Kafka producer

## Results

| Metric | Value |
|--------|-------|
| Throughput | 827 ops/s |
| p50 Latency | 4.0 ms |
| p99 Latency | 83.0 ms |
| Total Operations | 300000 |
| Successful | 300000 |
| Failed | 0 |
| Duration | 1m0s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | SPRING_BOOT |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 100 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-27T10:35:41-03:00
- Completed: 2025-12-27T10:41:45-03:00
- Duration: 6m3.6561255s
