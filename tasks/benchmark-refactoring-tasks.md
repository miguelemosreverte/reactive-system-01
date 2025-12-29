# Benchmark Refactoring Tasks

## Overview
- **28 Java files** in benchmark directory
- **17 files** with duplicate `MESSAGE_SIZE` constant
- **503 total** `System.out.println` calls
- **7 files** with duplicate Result/PhaseResult records

---

## PHASE 1: Extract Shared Utilities

### Task 1.1: Create `BenchmarkConstants.java`
**Priority:** HIGH | **Impact:** 17 files

Extract duplicated constants into a single shared class.

**Constants to extract:**

| Constant | Value | Found In |
|----------|-------|----------|
| `MESSAGE_SIZE` | 64 | 17 files (see below) |
| `SMOKE_DURATION` | 3 | `KafkaBaselineBenchmark.java:63` |
| `QUICK_DURATION` | 15 | `KafkaBaselineBenchmark.java:64` |
| `THOROUGH_DURATION` | 60 | `KafkaBaselineBenchmark.java:65` |
| `BATCH_SIZE_16K` | 16384 | 9 locations |
| `BATCH_SIZE_256K` | 262144 | `KafkaBaselineBenchmark.java:609` |
| `FETCH_MAX_BYTES` | 52428800 | `KafkaBaselineBenchmark.java:486` |

**Files with duplicate MESSAGE_SIZE = 64:**
```
MaxThroughputBrochure.java:35
MinimalBenchmark.java:17
MaxThroughputTest.java:32
DirectBatcherBenchmark.java:23
StripedBatcherBenchmark.java:19
MaxSendBenchmark.java:18
SimpleBatcherBenchmark.java:19
TightLoopBenchmark.java:21
KafkaBaselineBenchmark.java:56
AdaptiveRampBenchmark.java:46
StripedBatcherBrochure.java:31
FastBatcherBenchmark.java:22
FinalComparisonBenchmark.java:21
PureBatcherBenchmark.java:19
BillionMsgBrochure.java:31
ThreadLocalBatcherBenchmark.java:19
ExponentialBatcherBenchmark.java:19
```

---

### Task 1.2: Create `ProducerFactory.java`
**Priority:** CRITICAL | **Impact:** Eliminates 37 duplicate methods

**Current duplicates:**

| File | Method | Lines |
|------|--------|-------|
| `KafkaBaselineBenchmark.java` | `producerProps(String)` | 525-554 |
| `MaxThroughputBrochure.java` | `createProducer(String)` | 385-396 |
| `CalibrationBenchmark.java` | `createProducerProps(String)` | 321-331 |
| `StripedBatcherBrochure.java` | inline Properties creation | ~85-95 |

**Proposed interface:**
```java
public final class ProducerFactory {
    public static Properties baseProps(String bootstrap);
    public static Properties highThroughputProps(String bootstrap);
    public static Properties lowLatencyProps(String bootstrap);
    public static KafkaProducer<String, byte[]> create(String bootstrap, Properties props);
}
```

---

### Task 1.3: Create `FormattingUtils.java`
**Priority:** MEDIUM | **Impact:** 3+ files

**Duplicate `formatInterval()` implementations:**
```
CalibrationBenchmark.java:334-338
AdaptiveRangeTest.java:295-299
BatchSizeExplorer.java:239-243
```

**All identical:**
```java
static String formatInterval(int micros) {
    if (micros >= 1_000_000) return String.format("%.1fs", micros / 1_000_000.0);
    if (micros >= 1_000) return String.format("%.1fms", micros / 1_000.0);
    return micros + "µs";
}
```

**Additional formatting to consolidate:**
- Table printing (box-drawing characters)
- Throughput formatting (msg/s, M msg/s, B msg/s)
- Duration formatting

---

### Task 1.4: Create `BenchmarkResult.java` interface
**Priority:** HIGH | **Impact:** 7 files

**Current duplicate records:**

| File | Record | Lines |
|------|--------|-------|
| `KafkaBaselineBenchmark.java` | `Result` | 815-816 |
| `CalibrationBenchmark.java` | `BucketResult` | 310-319 |
| `BillionMsgBrochure.java` | `PhaseResult` | 284 |
| `AdaptiveRampBenchmark.java` | `LevelResult` | 364 |
| `MaxThroughputBrochure.java` | `PhaseResult`, `VerificationResult` | 399-402 |
| `MaxThroughputTest.java` | `PhaseResult` | 380 |
| `StripedBatcherBrochure.java` | `PhaseResult` | 369 |

**Proposed unified interface:**
```java
public interface BenchmarkResult {
    String name();
    long messagesProcessed();
    double throughputMsgPerSec();
    double avgLatencyNanos();
    boolean verified();

    default String toJson() { ... }
}
```

---

## PHASE 2: Consolidate Verification Logic

### Task 2.1: Merge verification methods
**Priority:** HIGH | **Impact:** 2 methods -> 1

**Current duplicates in `KafkaBaselineBenchmark.java`:**

| Method | Lines | Purpose |
|--------|-------|---------|
| `verifyLastMessage()` | 735-802 (68 lines) | Verify single message sequence |
| `verifyLastBatch()` | 557-603 (47 lines) | Verify batch sequence |

**Common code to extract:**
- Consumer creation (both create identical consumer)
- Partition seeking logic (both seek to end)
- Record extraction (both get last record from each partition)

**Proposed refactor:**
```java
public static VerificationResult verifyKafka(
    String bootstrap,
    String topic,
    VerificationMode mode  // SINGLE_MESSAGE or BATCH
) { ... }
```

---

## PHASE 3: Break Up God Classes

### Task 3.1: Split `benchmarkNaiveProducer()`
**File:** `KafkaBaselineBenchmark.java:239-336` (97 lines)

**Current responsibilities:**
1. Create producer (lines 245-250)
2. Create message (lines 252-255)
3. Warmup loop (lines 257-265)
4. Benchmark loop (lines 267-290)
5. Calculate metrics (lines 292-305)
6. Verify results (lines 307-320)
7. Print results (lines 322-335)

**Proposed split:**
```java
private Result benchmarkNaiveProducer(...) {
    var producer = ProducerFactory.create(bootstrap, ProducerFactory.naiveProps());
    var message = MessageFactory.create(MESSAGE_SIZE);

    warmup(producer, topic, message, WARMUP_COUNT);
    var metrics = runBenchmark(producer, topic, message, duration);
    var verification = KafkaVerifier.verify(bootstrap, topic, metrics.lastSequence());

    return new Result("NAIVE", metrics, verification);
}
```

---

### Task 3.2: Split `benchmarkBulkProducer()`
**File:** `KafkaBaselineBenchmark.java:348-461` (113 lines)

Same pattern as Task 3.1 - extract common phases.

---

### Task 3.3: Split `runBrochureBenchmark()`
**File:** `MaxThroughputBrochure.java:74-207` (133 lines)

**Current responsibilities:**
1. Parse configuration
2. Setup producer
3. Run multiple phases
4. Collect results
5. Verify Kafka
6. Write report
7. Print summary

---

## PHASE 4: Remove Dead Code

### Task 4.1: Identify obsolete benchmark files
**Priority:** LOW | **Impact:** Code cleanup

**Files using deprecated batchers:**
```
SimpleBatcherBenchmark.java      -> uses @Deprecated SimpleBatcher
ExponentialBatcherBenchmark.java -> uses @Deprecated ExponentialBatcher
ThreadLocalBatcherBenchmark.java -> uses @Deprecated ThreadLocalBatcher
DirectBatcherBenchmark.java      -> uses @Deprecated DirectBatcher
```

**Potentially unused test files:**
```
QuickAdaptiveTest.java    -> Quick test variant (check git history)
QuickBatchTest.java       -> Quick batch test (check git history)
RingBufferMicroBenchmark.java -> Micro-benchmark (check usage)
AdaptiveRangeTest.java    -> Range test (check git history)
```

**Action:** Review git history, delete or mark as @Deprecated

---

### Task 4.2: Remove unused imports
**Priority:** LOW

Run IDE "Optimize Imports" on all benchmark files.

---

## PHASE 5: Standardize Output

### Task 5.1: Replace System.out with logging
**Priority:** MEDIUM | **Impact:** 503 println calls

**Top offenders:**
```
KafkaBaselineBenchmark.java  - 56 calls
MaxThroughputBrochure.java   - 25 calls
StripedBatcherBrochure.java  - 18 calls
BatchSizeExplorer.java       - 20 calls
CalibrationBenchmark.java    - 12 calls
```

**Proposed approach:**
1. Add SLF4J logger to each class
2. Replace `System.out.println` with `log.info()`
3. Replace `System.err.println` with `log.error()`
4. Use structured logging for metrics

---

### Task 5.2: Standardize JSON output
**Priority:** MEDIUM | **Impact:** Consistent reports

**Current inconsistencies:**

| File | JSON Fields |
|------|-------------|
| `MaxThroughputBrochure` | brochure, name, startTime, endTime, durationMs, peakThroughput |
| `StripedBatcherBrochure` | Different field set |
| `CalibrationBenchmark` | Different field set |
| `KafkaBaselineBenchmark` | results array with mixed fields |

**Proposed:** Create `BenchmarkReportSchema.java` with standard fields.

---

## PHASE 6: CLI Cleanup

### Task 6.1: Extract cli.sh magic numbers
**File:** `cli.sh`

**Magic numbers to extract:**
```bash
COLIMA_MIN_CPU=6        # Line ~45
COLIMA_MIN_MEM=12       # Line ~46
$((sys_cpu * 2 / 3))    # Line ~78 - "2/3 for Colima"
$((sys_mem * 2 / 3))    # Line ~79
"leave at least 2"      # Line ~82 - host CPU reserve
"4GB for host"          # Line ~83 - host memory reserve
```

**Proposed:** Move to configuration block at top of file with documentation.

---

## Summary Statistics

| Category | Count | Priority |
|----------|-------|----------|
| Duplicate constants | 17 files | HIGH |
| Duplicate producer setup | 37 methods | CRITICAL |
| Duplicate Result records | 7 files | HIGH |
| God methods (50+ lines) | 6 methods | MEDIUM |
| System.out.println | 503 calls | MEDIUM |
| Dead code candidates | 8 files | LOW |
| Magic numbers | 20+ locations | HIGH |

---

## Execution Order

1. **Create shared utilities** (Phase 1) - Foundation for other changes
2. **Consolidate verification** (Phase 2) - Quick win, reduces duplication
3. **Remove dead code** (Phase 4) - Clean slate before refactoring
4. **Break up god classes** (Phase 3) - Improve maintainability
5. **Standardize output** (Phase 5) - Polish
6. **CLI cleanup** (Phase 6) - Final touches

---

*Generated: 2025-12-28*
*Awaiting rebase to upstream main before execution*

---

## TODO Comments Added

The following TODO comments have been added to the codebase for easy tracking:

| TODO | File | Line | Description |
|------|------|------|-------------|
| 1.1 | `KafkaBaselineBenchmark.java` | 56, 63 | MESSAGE_SIZE & duration constants |
| 1.1 | `MaxThroughputBrochure.java` | 35 | MESSAGE_SIZE constant |
| 1.2 | `KafkaBaselineBenchmark.java` | 529 | producerProps() method |
| 1.2 | `CalibrationBenchmark.java` | 322 | createProducerProps() method |
| 1.2 | `MaxThroughputBrochure.java` | 386 | createProducer() method |
| 1.3 | `CalibrationBenchmark.java` | 336 | formatInterval() method |
| 1.4 | `KafkaBaselineBenchmark.java` | 822 | Result record |
| 1.4 | `CalibrationBenchmark.java` | 310 | BucketResult record |
| 1.4 | `MaxThroughputBrochure.java` | 401 | PhaseResult record |
| 2.1 | `KafkaBaselineBenchmark.java` | 559, 737 | verifyLastBatch/verifyLastMessage |
| 3.1 | `KafkaBaselineBenchmark.java` | 236 | benchmarkNaiveProducer() |
| 3.2 | `KafkaBaselineBenchmark.java` | 351 | benchmarkBulkProducer() |
| 4.1 | `SimpleBatcherBenchmark.java` | 14 | Dead code review |
| 4.1 | `ExponentialBatcherBenchmark.java` | 14 | Dead code review |
| 4.1 | `ThreadLocalBatcherBenchmark.java` | 14 | Dead code review |
| 4.1 | `DirectBatcherBenchmark.java` | 14 | Dead code review |
| 6.1 | `cli.sh` | 14, 53 | Magic numbers extraction |

**Search command:** `grep -rn "TODO [0-9]" platform/kafka/src/main/java/com/reactive/platform/kafka/benchmark/ cli.sh`
