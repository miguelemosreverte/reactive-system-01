# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 7140464 ops/s |
| p50 Latency | 0.0 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 71526026 |
| Successful | 71526026 |
| Failed | 0 |
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

- Started: 2025-12-26T21:22:58-03:00
- Completed: 2025-12-26T21:23:13-03:00
- Duration: 14.301101959s
