# HTTP - Rocket Server

NIO + SO_REUSEPORT with kernel-level connection distribution

## Results

| Metric | Value |
|--------|-------|
| Throughput | 321377 ops/s |
| p50 Latency | 0.7 ms |
| p99 Latency | 8.0 ms |
| Total Operations | 19286151 |
| Successful | 19286151 |
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

- Started: 2025-12-29T14:31:25-03:00
- Completed: 2025-12-29T14:32:52-03:00
- Duration: 1m26.723924291s
