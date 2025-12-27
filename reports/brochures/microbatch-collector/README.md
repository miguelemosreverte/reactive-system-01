# Microbatch Collector (No HTTP)

Raw event collection throughput without HTTP overhead - shows theoretical maximum batching capacity

## Results

| Metric | Value |
|--------|-------|
| Throughput | 2612 ops/s |
| p50 Latency | 355.1 ms |
| p99 Latency | 710.2 ms |
| Total Operations | 218031679 |
| Successful | 1256000 |
| Failed | 216775679 |
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

- Started: 2025-12-27T00:52:34-03:00
- Completed: 2025-12-27T01:02:45-03:00
- Duration: 10m10.565782125s
