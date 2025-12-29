# Kafka BULK - Send Rate (acks=0)

THEORETICAL MAXIMUM send rate with acks=0 (fire-and-forget).

⚠️  WARNING: This is NOT a production-safe configuration!
- Messages may be LOST (no acknowledgment)
- Does NOT include flush time
- Real sustained throughput is 10-20x lower

Use kafka-bulk-sustained for realistic production numbers.


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
| Kafka Acks | 0 |

## Timestamp

- Started: 2025-12-29T14:48:17-03:00
- Completed: 2025-12-29T14:48:37-03:00
- Duration: 20.365071083s
