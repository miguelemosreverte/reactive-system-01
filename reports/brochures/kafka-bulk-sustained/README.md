# Kafka BULK - Sustained Rate (Docker)

REALISTIC throughput including Kafka I/O time.

This measures what Kafka can actually ABSORB, not just what
the producer can push. On Docker Kafka: 5-10M msg/s.

⚠️  Still uses acks=0 - for durable writes, see kafka-transactional.


## Results

| Metric | Value |
|--------|-------|
| Throughput | 426727 ops/s |
| p50 Latency | 0.0 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 4268979 |
| Successful | 4268979 |
| Failed | 0 |
| Duration | 10s |

## Configuration

| Setting | Value |
|---------|-------|
| Component | kafka |
| HTTP Server |  |
| Microbatching | false |
| Batch Size | 0 |
| Batch Timeout | 0 ms |
| Concurrency | 8 |
| Kafka Acks | 0 |

## Timestamp

- Started: 2025-12-29T14:35:01-03:00
- Completed: 2025-12-29T14:35:31-03:00
- Duration: 29.576628125s
