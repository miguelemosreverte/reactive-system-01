# Kafka - Tuned Batching

Kafka producer with optimized batch settings (acks=1, high concurrency)

## Results

| Metric | Value |
|--------|-------|
| Throughput | 433223 ops/s |
| p50 Latency | 0.3 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 4336129 |
| Successful | 4336129 |
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
| Concurrency | 128 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-29T14:50:18-03:00
- Completed: 2025-12-29T14:50:54-03:00
- Duration: 36.107456334s
