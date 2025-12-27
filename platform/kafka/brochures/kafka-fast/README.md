# Kafka - Fire-and-Forget

Maximum Kafka producer throughput with acks=0 (no acknowledgment)

## Results

| Metric | Value |
|--------|-------|
| Throughput | 4614 ops/s |
| p50 Latency | 3.5 ms |
| p99 Latency | 0.1 ms |
| Total Operations | 280736 |
| Successful | 280736 |
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
| Kafka Acks | 0 |

## Timestamp

- Started: 2025-12-26T21:18:45-03:00
- Completed: 2025-12-26T21:20:54-03:00
- Duration: 2m8.271200458s
