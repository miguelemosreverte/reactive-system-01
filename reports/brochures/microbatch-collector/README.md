# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 11055578 ops/s |
| p50 Latency | 5.2 ms |
| p99 Latency | 10.4 ms |
| Total Operations | 110600000 |
| Successful | 110600000 |
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

- Started: 2025-12-29T16:06:42-03:00
- Completed: 2025-12-29T16:07:08-03:00
- Duration: 25.720965125s
