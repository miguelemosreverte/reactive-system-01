# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 8545911 ops/s |
| p50 Latency | 18.2 ms |
| p99 Latency | 36.3 ms |
| Total Operations | 90894308 |
| Successful | 90894308 |
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

- Started: 2025-12-29T14:34:23-03:00
- Completed: 2025-12-29T14:35:00-03:00
- Duration: 36.686926542s
