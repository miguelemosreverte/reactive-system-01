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

## Iteration 2: TBD
**Status**: PENDING

---

## Iteration 3: TBD
**Status**: PENDING

---

## Iteration 4: TBD
**Status**: PENDING

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
