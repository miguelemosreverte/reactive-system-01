# Kafka Transactional (acks=all)

PRODUCTION-SAFE throughput with guaranteed durability.

✅ Messages are guaranteed to be persisted
✅ Exactly-once semantics (no duplicates)
✅ Suitable for critical business data

Expected: 100K-500K msg/s on Docker (20-50x slower than acks=0)


## Results

| Metric | Value |
|--------|-------|
| Throughput | 0 ops/s |
| p50 Latency | 0.0 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 0 |
| Successful | 0 |
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
| Concurrency | 0 |
| Kafka Acks | all |

## Timestamp

- Started: 2025-12-29T14:49:56-03:00
- Completed: 2025-12-29T14:50:17-03:00
- Duration: 20.601851209s
