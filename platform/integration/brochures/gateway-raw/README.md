# Gateway - Raw NIO + Microbatch

Raw NIO HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 13438 ops/s |
| p50 Latency | 36.5 ms |
| p99 Latency | 261.8 ms |
| Total Operations | 134801 |
| Successful | 134801 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | gateway |
| HTTP Server | RAW |
| Microbatching | true |
| Batch Size | 128 |
| Batch Timeout | 5 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:08:38-03:00
- Completed: 2025-12-26T21:10:58-03:00
- Duration: 2m20.0019605s
