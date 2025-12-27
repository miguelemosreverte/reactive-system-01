# Kafka - Tuned Batching

Kafka producer with optimized batch settings (acks=1, large batches)

## Results

| Metric | Value |
|--------|-------|
| Throughput | 4623 ops/s |
| p50 Latency | 3.5 ms |
| p99 Latency | 0.2 ms |
| Total Operations | 280857 |
| Successful | 280857 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:50:24-03:00
- Completed: 2025-12-27T00:52:34-03:00
- Duration: 2m9.5115355s
