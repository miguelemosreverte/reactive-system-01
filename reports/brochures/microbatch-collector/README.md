# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 16504730 ops/s |
| p50 Latency | 6.5 ms |
| p99 Latency | 13.1 ms |
| Total Operations | 165624965 |
| Successful | 165624965 |
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

- Started: 2025-12-29T13:58:33-03:00
- Completed: 2025-12-29T13:58:58-03:00
- Duration: 24.833165959s
