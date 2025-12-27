# Gateway - Netty + Microbatch

Optimized gateway with Rocket/Netty HTTP and adaptive microbatching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 15135 ops/s |
| p50 Latency | 13.0 ms |
| p99 Latency | 167.1 ms |
| Total Operations | 151457 |
| Successful | 151457 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | ROCKET |
| Microbatching | true |
| Batch Size | 100 |
| Batch Timeout | 10 ms |
| Concurrency | 200 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:06:15-03:00
- Completed: 2025-12-26T21:08:38-03:00
- Duration: 2m22.208411916s
