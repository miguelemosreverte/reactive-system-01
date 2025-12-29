# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 13018443 ops/s |
| p50 Latency | 10.5 ms |
| p99 Latency | 21.0 ms |
| Total Operations | 137943419 |
| Successful | 137943419 |
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

- Started: 2025-12-29T14:50:54-03:00
- Completed: 2025-12-29T14:51:25-03:00
- Duration: 31.055728417s
