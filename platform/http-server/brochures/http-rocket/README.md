# HTTP - Rocket Server

NIO + SO_REUSEPORT with kernel-level connection distribution

## Results

| Metric | Value |
|--------|-------|
| Throughput | 765496 ops/s |
| p50 Latency | 0.2 ms |
| p99 Latency | 3.7 ms |
| Total Operations | 7659548 |
| Successful | 7659548 |
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

- Started: 2025-12-26T21:17:21-03:00
- Completed: 2025-12-26T21:17:37-03:00
- Duration: 16.3650875s
