# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 311579 ops/s |
| p50 Latency | 1.6 ms |
| p99 Latency | 3.2 ms |
| Total Operations | 185048487 |
| Successful | 4166746 |
| Failed | 180881741 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | collector |
| HTTP Server | NONE |
| Microbatching | true |
| Batch Size | 128 |
| Batch Timeout | 2000 ms |
| Concurrency | 200 |
| Kafka Acks |  |

## Timestamp

- Started: 2025-12-27T16:21:21-03:00
- Completed: 2025-12-27T16:21:35-03:00
- Duration: 13.341549333s
