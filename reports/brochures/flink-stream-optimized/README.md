# Flink - Stream Processing (High Throughput)

Flink benchmark with optimized network buffers (4x exclusive, 32 floating, 64KB segments)

## Results

| Metric | Value |
|--------|-------|
| Throughput | 2782 ops/s |
| p50 Latency | 19015.0 ms |
| p99 Latency | 28023.0 ms |
| Total Operations | 108892 |
| Successful | 34811 |
| Failed | 74081 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | flink |
| HTTP Server | NONE |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 200 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-29T14:44:07-03:00
- Completed: 2025-12-29T14:44:55-03:00
- Duration: 48.163250917s
