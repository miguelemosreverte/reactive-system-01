# Optimization Iterations

**Goal**: 20 iterations of optimization, no regressions to throughput.

## Baseline (Before Optimization)
- **Throughput**: ~10,000-12,000 ops/s (saturated at 64 workers)
- **Bottleneck**: Node.js Gateway (single-threaded event loop)
- **Gateway CPU**: 8.83%, Memory: 51%
- **System CPU at saturation**: 89%

---

## Iteration 1: Use acks=0 for Fire-and-Forget
**Status**: COMPLETED
**Change**: Added `.fireAndForget()` to KafkaPublisher (acks=0, maxInFlight=20)
**File**: `application/src/main/java/com/reactive/counter/api/CounterController.java`

### Before:
- Throughput: 124,440 ops (15s, 32 workers)
- Kafka producer: acks=1 (wait for leader acknowledgment)

### After:
- Throughput: 142,201 ops (15s, 32 workers)
- Kafka producer: acks=0 (no wait)

### Result:
**+14.3% improvement** (17,761 more operations)

---

## Iteration 2: Kafka Producer Batching
**Status**: COMPLETED
**Change**: Increased linger.ms to 5ms and batch.size to 64KB for fire-and-forget
**File**: `platform/src/main/java/com/reactive/platform/kafka/KafkaPublisher.java`

### Before:
- Throughput: 142,201 ops (iteration 1)
- Producer: linger.ms=0, batch.size=16KB

### After:
- Throughput: 153,246 ops (15s, 32 workers)
- Producer: linger.ms=5, batch.size=64KB

### Result:
**+7.8% improvement** over iteration 1, **+23.2% over baseline**

---

## Iteration 3: LZ4 Compression
**Status**: COMPLETED
**Change**: Added LZ4 compression to Kafka producer
**File**: `platform/src/main/java/com/reactive/platform/kafka/KafkaPublisher.java`

### Before:
- Throughput: 153,246 ops (iteration 2)
- Compression: none

### After:
- Throughput: 154,357 ops (15s, 32 workers)
- Compression: LZ4

### Result:
**+0.7% improvement** (marginal in local Docker, more impactful in network-constrained production)

---

## Iteration 4: Reduce Logging Overhead
**Status**: COMPLETED
**Change**: Changed log level from DEBUG to INFO, hot-path logs from INFO to DEBUG
**Files**: `application/src/main/resources/application.yml`, `CounterController.java`

### Before:
- Throughput: 154,357 ops (iteration 3)
- Logging: DEBUG level, INFO on every request

### After:
- Throughput: 174,600 ops (15s, 32 workers)
- Logging: INFO level, DEBUG on requests

### Result:
**+13.1% improvement** over iteration 3, **+40.3% over baseline**

---

## Iteration 5: TBD
**Status**: PENDING

---

## Iteration 6: TBD
**Status**: PENDING

---

## Iteration 7: TBD
**Status**: PENDING

---

## Iteration 8: TBD
**Status**: PENDING

---

## Iteration 9: TBD
**Status**: PENDING

---

## Iteration 10: TBD
**Status**: PENDING

---

## Iteration 11: TBD
**Status**: PENDING

---

## Iteration 12: TBD
**Status**: PENDING

---

## Iteration 13: TBD
**Status**: PENDING

---

## Iteration 14: TBD
**Status**: PENDING

---

## Iteration 15: TBD
**Status**: PENDING

---

## Iteration 16: TBD
**Status**: PENDING

---

## Iteration 17: TBD
**Status**: PENDING

---

## Iteration 18: TBD
**Status**: PENDING

---

## Iteration 19: TBD
**Status**: PENDING

---

## Iteration 20: TBD
**Status**: PENDING
