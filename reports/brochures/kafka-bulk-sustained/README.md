# Kafka BULK - Sustained Rate (Docker)

REALISTIC throughput including Kafka I/O time.

This measures what Kafka can actually ABSORB, not just what
the producer can push. On Docker Kafka: 5-10M msg/s.

⚠️  Still uses acks=0 - for durable writes, see kafka-transactional.


## Results

| Metric | Value |
|--------|-------|
| Throughput | 447529 ops/s |
| p50 Latency | 0.0 ms |
| p99 Latency | 0.0 ms |
| Total Operations | 4479317 |
| Successful | 4479317 |
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

- Started: 2025-12-29T14:48:38-03:00
- Completed: 2025-12-29T14:49:21-03:00
- Duration: 43.11324s
