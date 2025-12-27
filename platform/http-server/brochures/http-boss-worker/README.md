# HTTP - Boss/Worker

Classic Netty-style architecture with accept/worker thread separation

## Results

| Metric | Value |
|--------|-------|
| Throughput | 664973 ops/s |
| p50 Latency | 0.2 ms |
| p99 Latency | 4.3 ms |
| Total Operations | 6654381 |
| Successful | 6654381 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | http |
| HTTP Server | BOSS_WORKER |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:16:15-03:00
- Completed: 2025-12-26T21:16:31-03:00
- Duration: 16.331967s
