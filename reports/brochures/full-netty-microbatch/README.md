# Full Pipeline - Netty + Microbatch

Optimized E2E pipeline with Rocket HTTP server and adaptive microbatching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 205624 ops/s |
| p50 Latency | 0.9 ms |
| p99 Latency | 5.2 ms |
| Total Operations | 12337433 |
| Successful | 12337433 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:06:43-03:00
- Completed: 2025-12-27T00:10:59-03:00
- Duration: 4m16.056354375s
