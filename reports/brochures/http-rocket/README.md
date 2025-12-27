# HTTP - Rocket Server

NIO + SO_REUSEPORT with kernel-level connection distribution

## Results

| Metric | Value |
|--------|-------|
| Throughput | 633903 ops/s |
| p50 Latency | 0.2 ms |
| p99 Latency | 4.5 ms |
| Total Operations | 38042396 |
| Successful | 38042396 |
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

- Started: 2025-12-27T00:42:25-03:00
- Completed: 2025-12-27T00:43:37-03:00
- Duration: 1m11.817773625s
