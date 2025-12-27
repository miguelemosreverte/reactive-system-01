# Full Pipeline - Netty + Microbatch

Optimized E2E pipeline with Rocket HTTP server and adaptive microbatching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 15463 ops/s |
| p50 Latency | 12.7 ms |
| p99 Latency | 165.3 ms |
| Total Operations | 154987 |
| Successful | 154986 |
| Failed | 1 |
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

- Started: 2025-12-26T20:58:33-03:00
- Completed: 2025-12-26T21:00:56-03:00
- Duration: 2m23.183268292s
