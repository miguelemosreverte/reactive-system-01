# Benchmark Analysis - Iterations 47-49

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

### Constraints (Documented)

- **No batch APIs**: This is a customer-facing application. Each HTTP request = one event. Non-negotiable.
- **Avro is NOT a bottleneck**: The no-kafka endpoint INCLUDES Avro encoding and achieves 27k+ req/s

### Current Understanding

| Benchmark | Throughput | Latency |
|-----------|------------|---------|
| Kafka producer (8 threads, tight loop) | 359,496 msg/s | 20 µs |
| HTTP no-kafka (includes Avro) | 27,545 req/s | 6.1 ms |
| Full pipeline | ~16,000 req/s | 7.3 ms |

The Kafka send adds ~1.2 ms per request when called from HTTP handler, vs 20 µs in tight-loop benchmark.
This 60x difference needs investigation with proper instrumentation.

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

## Iteration 49: IdGenerator Optimization

### Problem Identified

Timed diagnostic revealed unexpected bottleneck in ID generation:

| Step | Time (BEFORE) | % of Total |
|------|---------------|------------|
| Parse | 7.2 µs | 5.2% |
| **IDGen** | **26.4 µs** | **19.1%** |
| Event | 0.9 µs | 0.6% |
| Avro | 11.0 µs | 7.9% |
| Kafka | 92.0 µs | 66.4% |
| Response | 1.1 µs | 0.8% |
| **Total** | **138.5 µs** | 100% |

Root cause: `String.format("%016x%016x", high, low)` is extremely slow.

### Fix Applied: Fast Hex Encoding

Replaced `String.format()` with lookup table approach in `IdGenerator.java`:

```java
// Hex lookup table for fast conversion
private static final char[] HEX = "0123456789abcdef".toCharArray();

public String generateRequestId() {
    // ... compute high and low ...

    // Fast hex encoding (avoids String.format overhead)
    char[] chars = new char[32];
    for (int i = 15; i >= 0; i--) {
        chars[i] = HEX[(int)(high & 0xF)];
        high >>>= 4;
    }
    for (int i = 31; i >= 16; i--) {
        chars[i] = HEX[(int)(low & 0xF)];
        low >>>= 4;
    }
    return new String(chars);
}
```

### Timing Results (AFTER)

| Step | Time (AFTER) | % of Total | Improvement |
|------|--------------|------------|-------------|
| Parse | 4.4 µs | 4.6% | -39% |
| **IDGen** | **1.3 µs** | **1.4%** | **-95%** |
| Event | 0.9 µs | 0.9% | - |
| Avro | 8.0 µs | 8.4% | -27% |
| Kafka | 79.8 µs | 83.7% | -13% |
| Response | 0.9 µs | 0.9% | - |
| **Total** | **95.3 µs** | 100% | **-31%** |

### Throughput Result

- **BEFORE**: 14,647 req/s
- **AFTER**: 18,000-20,000 req/s
- **Improvement**: +25-35%

## Historical Comparison

| Iteration | Throughput | Change |
|-----------|------------|--------|
| Baseline | ~420 req/s | - |
| iter-44 (OTel sampling) | ~8,000 req/s | +20x |
| iter-46 (GC tuning) | ~10,000 req/s | +24x |
| iter-47 (Netty HTTP) | 11,782 req/s | +28x |
| iter-48 (Kafka callback) | 14,647 req/s | +35x |
| **iter-49 (IdGenerator)** | **~19,000 req/s** | **+45x** |
| Theoretical (HTTP) | 38,239 req/s | +91x potential |

## Current Bottleneck Analysis

After iter-49, the breakdown shows Kafka send is now **84% of handler time**:

| Component | Time | % |
|-----------|------|---|
| All non-Kafka work | 15.5 µs | 16% |
| Kafka send | 79.8 µs | 84% |

The remaining gap to theoretical (19k vs 38k) is primarily Kafka producer overhead,
which is inherent when each HTTP request must produce exactly one message (no batching).
