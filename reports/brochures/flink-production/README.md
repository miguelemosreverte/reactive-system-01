# Flink - Production Grade (High Throughput + Checkpointing)

Production-grade Flink benchmark with RocksDB incremental checkpoints, exactly-once semantics, and optimized network buffers

## Results

| Metric | Value |
|--------|-------|
| Throughput | 2497 ops/s |
| p50 Latency | 21187.0 ms |
| p99 Latency | 26662.0 ms |
| Total Operations | 94931 |
| Successful | 19731 |
| Failed | 75200 |
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

- Started: 2025-12-29T14:41:49-03:00
- Completed: 2025-12-29T14:42:37-03:00
- Duration: 47.896001125s
