# HTTP - Zero Copy

Direct ByteBuffers with adaptive busy-polling

## Results

| Metric | Value |
|--------|-------|
| Throughput | 664103 ops/s |
| p50 Latency | 0.1 ms |
| p99 Latency | 6.6 ms |
| Total Operations | 39851504 |
| Successful | 39851504 |
| Failed | 0 |
| Duration | 1m0s |

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

- Started: 2025-12-27T00:47:06-03:00
- Completed: 2025-12-27T00:48:16-03:00
- Duration: 1m10.053636958s
