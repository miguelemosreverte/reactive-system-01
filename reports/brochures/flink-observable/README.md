# Flink - Observable (Production with Tracing)

Production-grade Flink benchmark WITH full observability enabled. Traces flow to Jaeger, metrics to Prometheus, logs to Loki. Use this to demonstrate real-world throughput with complete observability stack.

## Results

| Metric | Value |
|--------|-------|
| Throughput | 7001 ops/s |
| p50 Latency | 3301.0 ms |
| p99 Latency | 17935.0 ms |
| Total Operations | 215538 |
| Successful | 185812 |
| Failed | 29726 |
| Duration | 30s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | flink |
| HTTP Server | NONE |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 100 |
| Kafka Acks | 1 |

## Timestamp

- Started: 2025-12-28T14:41:54-03:00
- Completed: 2025-12-28T14:42:48-03:00
- Duration: 54.314270458s
