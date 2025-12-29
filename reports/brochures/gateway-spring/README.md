# Gateway - Spring WebFlux

Gateway using Spring WebFlux with standard Kafka producer

## Results

| Metric | Value |
|--------|-------|
| Throughput | 7474 ops/s |
| p50 Latency | 10.0 ms |
| p99 Latency | 74.0 ms |
| Total Operations | 50000 |
| Successful | 50000 |
| Failed | 0 |
| Duration | 10s |

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

- Started: 2025-12-29T14:46:55-03:00
- Completed: 2025-12-29T14:47:03-03:00
- Duration: 7.553932458s
