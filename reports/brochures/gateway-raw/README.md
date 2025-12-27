# Gateway - Raw NIO + Microbatch

Raw NIO HTTP Server + Kafka microbatching gateway

## Results

| Metric | Value |
|--------|-------|
| Throughput | 203010 ops/s |
| p50 Latency | 2.1 ms |
| p99 Latency | 14.3 ms |
| Total Operations | 12180584 |
| Successful | 12180584 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:24:22-03:00
- Completed: 2025-12-27T00:28:38-03:00
- Duration: 4m16.061382s
