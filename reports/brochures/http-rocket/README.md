# HTTP - Rocket Server

NIO + SO_REUSEPORT with kernel-level connection distribution

## Results

| Metric | Value |
|--------|-------|
| Throughput | 96296 ops/s |
| p50 Latency | 1.1 ms |
| p99 Latency | 4.1 ms |
| Total Operations | 5778241 |
| Successful | 5778241 |
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

- Started: 2025-12-29T16:10:45-03:00
- Completed: 2025-12-29T16:12:07-03:00
- Duration: 1m22.171341042s
