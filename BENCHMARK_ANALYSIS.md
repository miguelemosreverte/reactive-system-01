# Benchmark Analysis - Iterations 47-48

## Component Isolation Benchmarks (Theoretical Maximums)

| Component | Throughput | Latency (avg) | Notes |
|-----------|------------|---------------|-------|
| **Kafka Producer** | **244,421 msg/s** | 0.030 ms | Fire-and-forget (acks=0) |
| UltraFastHttpServer | 38,239 req/s | 2.6 ms | Single-thread, zero-alloc |
| RawHttpServer | 36,539 req/s | 2.7 ms | Multi-thread, pure Java |
| FastHttpServer | 35,035 req/s | 2.8 ms | NIO + Virtual Threads |
| NettyHttpServer | 32,833 req/s | 3.0 ms | Netty event loop |
| Spring WebFlux | 8,794 req/s | ~8 ms | Full framework overhead |

## Full Pipeline Benchmarks

| Configuration | Throughput | Gap to Theoretical |
|--------------|------------|-------------------|
| Spring + Kafka | 7,805 req/s | 79% below HTTP max (38k) |
| **Netty + Kafka** | **11,782 req/s** | 69% below HTTP max |

## The Bottleneck Question

**HTTP isolation**: 38,239 req/s
**Kafka isolation**: 244,421 msg/s
**Full pipeline**: 11,782 req/s

Why is the full pipeline only achieving **31% of HTTP capacity** when Kafka is 6x faster?

### Potential Bottlenecks (in order of handler execution)

1. **JSON Parsing** - Simple string operations, unlikely major issue
2. **ID Generation** - `IdGenerator.generateRequestId/EventId()`
3. **CounterEvent.create()** - Object creation + timestamp
4. **Avro Encoding** - `AvroCounterEventCodec` serialization
5. **Kafka Producer** - Buffer, batch, network I/O
6. **Response Building** - StringBuilder + String allocation

### Next Steps

1. Profile the handler to identify which step takes the most time
2. Consider removing Avro encoding (use raw bytes or simple JSON)
3. Test with pre-allocated responses (avoid String building per request)
4. Investigate if Kafka producer is blocking despite fire-and-forget

## Iteration 48: Bottleneck Isolation & Kafka Optimization

### Diagnostic Benchmarks (isolating each component)

| Endpoint | Throughput | What it measures |
|----------|------------|------------------|
| `/api/counter/no-kafka` | **29,305 req/s** | HTTP + parse + ID + Avro (NO Kafka) |
| `/api/counter/raw` | 20,706 req/s | Raw Kafka (linger.ms=5, no callback) |
| `/api/counter` (before) | 12,295 req/s | Full pipeline |

### Bottlenecks Identified

1. **Raw Kafka overhead: 29%** (29,305 → 20,706) - inherent Kafka cost
2. **KafkaPublisher callback: 19%** (20,706 → 12,295) - **FIXED!**

### Fix Applied: Remove Kafka callback

Changed `publishFireAndForgetNoTrace()` from:
```java
producer.send(record, (metadata, exception) -> { ... });
```
To:
```java
producer.send(record);  // No callback - truly fire-and-forget
```

### Result

- **BEFORE**: 12,295 req/s
- **AFTER**: 14,647 req/s
- **Improvement**: +19%

## Historical Comparison

| Iteration | Throughput | Change |
|-----------|------------|--------|
| Baseline | ~420 req/s | - |
| iter-44 (OTel sampling) | ~8,000 req/s | +20x |
| iter-46 (GC tuning) | ~10,000 req/s | +24x |
| iter-47 (Netty HTTP) | 11,782 req/s | +28x |
| **iter-48 (Kafka callback)** | **14,647 req/s** | **+35x** |
| Theoretical (HTTP) | 38,239 req/s | +91x potential |
