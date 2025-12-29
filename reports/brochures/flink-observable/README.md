# Flink - Observable (Production with Tracing)

Production-grade Flink benchmark WITH full observability enabled. Traces flow to Jaeger, metrics to Prometheus, logs to Loki. Use this to demonstrate real-world throughput with complete observability stack.

## Results

| Metric | Value |
|--------|-------|
| Throughput | 2192 ops/s |
| p50 Latency | 20593.0 ms |
| p99 Latency | 29969.0 ms |
| Total Operations | 95008 |
| Successful | 19908 |
| Failed | 75100 |
| Duration | 10s |

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

- Started: 2025-12-29T14:40:34-03:00
- Completed: 2025-12-29T14:41:48-03:00
- Duration: 1m14.028707791s
