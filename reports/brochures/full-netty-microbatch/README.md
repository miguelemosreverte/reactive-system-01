# Full Pipeline - Netty + Microbatch

Optimized E2E pipeline with Rocket HTTP server and adaptive microbatching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 256505 ops/s |
| p50 Latency | 0.7 ms |
| p99 Latency | 5.0 ms |
| Total Operations | 2565052 |
| Successful | 2565052 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | full |
| HTTP Server | ROCKET |
| Microbatching | true |
| Batch Size | 100 |
| Batch Timeout | 10 ms |
| Concurrency | 200 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-29T14:44:55-03:00
- Completed: 2025-12-29T14:45:31-03:00
- Duration: 35.322362083s
