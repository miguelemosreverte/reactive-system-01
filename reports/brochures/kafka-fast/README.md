# Kafka - Fire-and-Forget

Maximum Kafka producer throughput with acks=0, high concurrency, and batching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 429501 ops/s |
| p50 Latency | 0.3 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 4361154 |
| Successful | 4361154 |
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
| Kafka Acks | 0 |

## Timestamp

- Started: 2025-12-29T14:49:22-03:00
- Completed: 2025-12-29T14:49:55-03:00
- Duration: 33.573375333s
