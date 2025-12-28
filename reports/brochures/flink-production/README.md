# Flink - Production Grade (High Throughput + Checkpointing)

Production-grade Flink benchmark with RocksDB incremental checkpoints, exactly-once semantics, and optimized network buffers

## Results

| Metric | Value |
|--------|-------|
| Throughput | 6500 ops/s |
| p50 Latency | 2924.0 ms |
| p99 Latency | 20738.0 ms |
| Total Operations | 202055 |
| Successful | 147174 |
| Failed | 54881 |
| Duration | 1m0s |

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

- Started: 2025-12-28T13:45:39-03:00
- Completed: 2025-12-28T13:46:33-03:00
- Duration: 54.44069475s
