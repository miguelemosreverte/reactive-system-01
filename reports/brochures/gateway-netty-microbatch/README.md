# Gateway - Netty + Microbatch

Optimized gateway with Rocket/Netty HTTP and adaptive microbatching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 197165 ops/s |
| p50 Latency | 0.9 ms |
| p99 Latency | 6.4 ms |
| Total Operations | 11829915 |
| Successful | 11829915 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:20:04-03:00
- Completed: 2025-12-27T00:24:22-03:00
- Duration: 4m18.226109375s
