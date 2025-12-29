# Functional Programming Refactoring Tasks

**Goal**: Make the codebase more concise, readable, and functional.
**Iteration**: 16

---

## Task 1: Convert Imperative Loops to Streams in BottleneckAnalyzer

**File**: `platform/src/main/java/com/reactive/platform/benchmark/BottleneckAnalyzer.java`

### 1.1 Lines 262-278: Map building loop → stream collect

**Before**:
```java
Map<String, Long> durationByService = new HashMap<>();
for (Span span : trace.spans()) {
    String service = getServiceName(trace, span);
    long duration = span.duration();
    durationByService.merge(service, duration, Long::max);
}
```

**After**:
```java
Map<String, Long> durationByService = trace.spans().stream()
    .collect(Collectors.toMap(
        span -> getServiceName(trace, span),
        Span::duration,
        Long::max
    ));
```

### 1.2 Lines 275-278: Percentage map transformation

**Before**:
```java
Map<String, Double> percentByService = new HashMap<>();
for (var entry : durationByService.entrySet()) {
    percentByService.put(entry.getKey(), (entry.getValue() * 100.0) / totalDuration);
}
```

**After**:
```java
Map<String, Double> percentByService = durationByService.entrySet().stream()
    .collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> (e.getValue() * 100.0) / totalDuration
    ));
```

### 1.3 Lines 324-330: Average computation

**Before**:
```java
Map<String, Double> avgPercentByService = new HashMap<>();
for (var entry : percentsByService.entrySet()) {
    double avg = entry.getValue().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0);
    avgPercentByService.put(entry.getKey(), avg);
}
```

**After**:
```java
Map<String, Double> avgPercentByService = percentsByService.entrySet().stream()
    .collect(Collectors.toMap(
        Map.Entry::getKey,
        e -> e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0)
    ));
```

---

## Task 2: Replace Null Checks with Optional in ObservabilityFetcher

**File**: `platform/src/main/java/com/reactive/platform/benchmark/ObservabilityFetcher.java`

### 2.1 Lines 261-270: extractTraceIdFromLogs

**Before**:
```java
for (LogEntry entry : logs) {
    Object traceId = entry.fields().get("traceId");
    if (traceId != null && !traceId.toString().isEmpty() && !traceId.toString().equals("null")) {
        return Optional.of(traceId.toString());
    }
}
return Optional.empty();
```

**After**:
```java
return logs.stream()
    .map(entry -> entry.fields().get("traceId"))
    .filter(Objects::nonNull)
    .map(Object::toString)
    .filter(s -> !s.isEmpty() && !"null".equals(s))
    .findFirst();
```

### 2.2 Lines 61-63: Empty string check

**Before**:
```java
if (otelTraceId == null || otelTraceId.isEmpty()) {
    return Optional.empty();
}
```

**After**:
```java
return Optional.ofNullable(otelTraceId)
    .filter(id -> !id.isEmpty())
    .flatMap(this::fetchTraceInternal);
```

---

## Task 3: Extract Repeated Formatting Patterns in ConfigGenerator

**File**: `platform/src/main/java/com/reactive/platform/config/ConfigGenerator.java`

### 3.1 Lines 251-261: Extract section formatting helper

**Before**:
```java
if (!errors.isEmpty()) {
    sb.append("ERRORS:\n");
    for (String error : errors) {
        sb.append("  ✗ ").append(error).append("\n");
    }
    sb.append("\n");
}
if (!warnings.isEmpty()) {
    sb.append("WARNINGS:\n");
    for (String warning : warnings) {
        sb.append("  ⚠ ").append(warning).append("\n");
    }
}
```

**After**:
```java
appendSection(sb, "ERRORS", errors, "✗");
appendSection(sb, "WARNINGS", warnings, "⚠");

// Helper method:
private void appendSection(StringBuilder sb, String title, List<String> items, String icon) {
    if (items.isEmpty()) return;
    sb.append(title).append(":\n");
    items.forEach(item -> sb.append("  ").append(icon).append(" ").append(item).append("\n"));
    sb.append("\n");
}
```

---

## Task 4: Simplify Exception Handling with Optional

**File**: `platform/src/main/java/com/reactive/platform/config/ConfigGenerator.java`

### 4.1 Lines 96-104: appendServiceMemory

**Before**:
```java
try {
    PlatformConfig.ServiceConfig svc = config.service(service);
    sb.append(prefix).append("_MEMORY=").append(svc.containerMb()).append("M\n");
    try {
        sb.append(prefix).append("_HEAP=").append(svc.heapMb()).append("m\n");
    } catch (Exception ignored) {}
} catch (Exception ignored) {}
```

**After**:
```java
tryGet(() -> config.service(service)).ifPresent(svc -> {
    sb.append(prefix).append("_MEMORY=").append(svc.containerMb()).append("M\n");
    if (svc.heapMb() > 0) {
        sb.append(prefix).append("_HEAP=").append(svc.heapMb()).append("m\n");
    }
});

// Helper:
private static <T> Optional<T> tryGet(Supplier<T> supplier) {
    try { return Optional.ofNullable(supplier.get()); }
    catch (Exception e) { return Optional.empty(); }
}
```

---

## Task 5: Use Optional.ofNullable().orElse() Pattern

**Files**: Multiple

### 5.1 FastGateway.java:241

**Before**:
```java
String value = System.getenv(key);
return value != null ? value : defaultValue;
```

**After**:
```java
return Optional.ofNullable(System.getenv(key)).orElse(defaultValue);
```

### 5.2 BaseBenchmark.java:103

**Before**:
```java
this.errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
```

**After**:
```java
this.errorMessage = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
```

---

## Task 6: Convert Service Iteration to Streams

**File**: `platform/src/main/java/com/reactive/platform/config/ConfigGenerator.java`

### 6.1 Lines 65-67 and 203-215: Service iteration

**Before**:
```java
for (PlatformConfig.Service service : PlatformConfig.Service.values()) {
    appendServiceMemory(sb, service);
}
```

**After**:
```java
Arrays.stream(PlatformConfig.Service.values())
    .forEach(service -> appendServiceMemory(sb, service));
```

---

## Execution Order

1. Task 1 (BottleneckAnalyzer streams) - Highest impact
2. Task 2 (ObservabilityFetcher Optional) - Clean null handling
3. Task 3 (ConfigGenerator helpers) - DRY principle
4. Task 4 (Exception handling) - Cleaner try/catch
5. Task 5 (Optional patterns) - Quick wins
6. Task 6 (Service streams) - Minor cleanup

---

## Success Criteria

- [ ] All code compiles without errors
- [ ] Benchmark still runs successfully
- [ ] No functional changes (same behavior)
- [ ] Reduced line count
- [ ] More readable, functional style
