# Kafka - Fire-and-Forget

Maximum Kafka producer throughput with acks=0, high concurrency, and batching

## Results

| Metric | Value |
|--------|-------|
| Throughput | 328816 ops/s |
| p50 Latency | 0.4 ms |
| p99 Latency | 0.1 ms |
| Total Operations | 19731282 |
| Successful | 19731282 |
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

- Started: 2025-12-27T16:02:31-03:00
- Completed: 2025-12-27T16:03:56-03:00
- Duration: 1m25.389832542s
