# Kafka - Fire-and-Forget

Maximum Kafka producer throughput with acks=0, high concurrency, and batching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 4637 ops/s |
| p50 Latency | 27.6 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 281157 |
| Successful | 281157 |
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
| Concurrency | 128 |
| Kafka Acks | 0 |

## Timestamp

- Started: 2025-12-27T10:20:17-03:00
- Completed: 2025-12-27T10:22:27-03:00
- Duration: 2m10.245003208s
