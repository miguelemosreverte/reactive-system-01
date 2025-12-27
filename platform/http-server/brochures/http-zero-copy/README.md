# HTTP - Zero Copy

Direct ByteBuffers with adaptive busy-polling

## Results

| Metric | Value |
|--------|-------|
| Throughput | 511406 ops/s |
| p50 Latency | 0.1 ms |
| p99 Latency | 9.1 ms |
| Total Operations | 5124292 |
| Successful | 5124292 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | http |
| HTTP Server | ZERO_COPY |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:18:27-03:00
- Completed: 2025-12-26T21:18:45-03:00
- Duration: 17.875521791s
