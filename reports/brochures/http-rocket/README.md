# HTTP - Rocket Server

NIO + SO_REUSEPORT with kernel-level connection distribution

## Results

| Metric | Value |
|--------|-------|
| Throughput | 356634 ops/s |
| p50 Latency | 0.6 ms |
| p99 Latency | 7.1 ms |
| Total Operations | 21403745 |
| Successful | 21403745 |
| Failed | 0 |
| Duration | 1m0s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | http |
| HTTP Server | ROCKET |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-29T13:55:37-03:00
- Completed: 2025-12-29T13:57:05-03:00
- Duration: 1m27.710468334s
