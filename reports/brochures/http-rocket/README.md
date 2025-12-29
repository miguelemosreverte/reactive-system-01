# HTTP - Rocket Server

NIO + SO_REUSEPORT with kernel-level connection distribution

## Results

| Metric | Value |
|--------|-------|
| Throughput | 269250 ops/s |
| p50 Latency | 0.7 ms |
| p99 Latency | 10.6 ms |
| Total Operations | 2700850 |
| Successful | 2700850 |
| Failed | 0 |
| Duration | 10s |

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

- Started: 2025-12-29T14:47:03-03:00
- Completed: 2025-12-29T14:47:41-03:00
- Duration: 37.441615125s
