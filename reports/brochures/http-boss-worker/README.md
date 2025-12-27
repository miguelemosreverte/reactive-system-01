# HTTP - Boss/Worker

Classic Netty-style architecture with accept/worker thread separation

## Results

| Metric | Value |
|--------|-------|
| Throughput | 740624 ops/s |
| p50 Latency | 0.2 ms |
| p99 Latency | 3.8 ms |
| Total Operations | 44441897 |
| Successful | 44441897 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:37:44-03:00
- Completed: 2025-12-27T00:38:54-03:00
- Duration: 1m9.85687425s
