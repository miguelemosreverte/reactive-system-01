# HTTP - Turbo

Busy-polling when active, sleep when idle for optimal latency

## Results

| Metric | Value |
|--------|-------|
| Throughput | 608795 ops/s |
| p50 Latency | 0.1 ms |
| p99 Latency | 7.5 ms |
| Total Operations | 6100734 |
| Successful | 6100734 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | http |
| HTTP Server | TURBO |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 500 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-26T21:17:53-03:00
- Completed: 2025-12-26T21:18:11-03:00
- Duration: 17.870627917s
