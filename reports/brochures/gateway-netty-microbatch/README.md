# Gateway - Netty + Microbatch

Optimized gateway with Rocket/Netty HTTP and adaptive microbatching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 310383 ops/s |
| p50 Latency | 0.6 ms |
| p99 Latency | 4.6 ms |
| Total Operations | 3103832 |
| Successful | 3103832 |
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

- Started: 2025-12-29T14:45:48-03:00
- Completed: 2025-12-29T14:46:22-03:00
- Duration: 34.10142075s
