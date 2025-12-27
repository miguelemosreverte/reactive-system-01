# Kafka - Tuned Batching

Kafka producer with optimized batch settings (acks=1, large batches)

## Results

| Metric | Value |
|--------|-------|
| Throughput | 4626 ops/s |
| p50 Latency | 3.5 ms |
| p99 Latency | 0.4 ms |
| Total Operations | 280850 |
| Successful | 280850 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | kafka |
| HTTP Server |  |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 16 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:20:54-03:00
- Completed: 2025-12-26T21:22:58-03:00
- Duration: 2m4.839378708s
