# Functional Programming Refactoring - Iteration 17

**Goal**: More concise, functional code patterns
**Files**: KafkaEventStore, LogImpl, SimpleResponse, GatewayFactory

---

## Task 1: KafkaEventStore - Header extraction to stream

**File**: `platform/src/main/java/com/reactive/platform/replay/KafkaEventStore.java`

**Before**:
```java
private Map<String, String> extractHeaders(ConsumerRecord<String, byte[]> record) {
    Map<String, String> headers = new HashMap<>();
    for (Header h : record.headers()) {
        headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
    }
    return headers;
}
```

**After**:
```java
private Map<String, String> extractHeaders(ConsumerRecord<String, byte[]> record) {
    return StreamSupport.stream(record.headers().spliterator(), false)
        .collect(Collectors.toMap(
            Header::key,
            h -> new String(h.value(), StandardCharsets.UTF_8),
            (a, b) -> b  // handle duplicates
        ));
}
```

---

## Task 2: LogImpl - Extract SpanKind conversion helper

**File**: `platform/src/main/java/com/reactive/platform/observe/LogImpl.java`

Extract repeated switch expression to reusable method.

---

## Task 3: SimpleResponse - Use Map.of() for headers

**File**: `platform/src/main/java/com/reactive/platform/http/SimpleResponse.java`

**Before**:
```java
Map<String, String> headers = new HashMap<>();
headers.put("Content-Type", "application/json");
headers.put("Content-Length", String.valueOf(contentLength));
return Collections.unmodifiableMap(headers);
```

**After**:
```java
return Map.of(
    "Content-Type", "application/json",
    "Content-Length", String.valueOf(contentLength)
);
```

---

## Task 4: GatewayFactory - Optional for env lookup

**File**: `platform/src/main/java/com/reactive/platform/gateway/GatewayFactory.java`

Use Optional.ofNullable for environment variable handling.

---

## Task 5: Apply stream patterns in other files

Look for remaining imperative loops that can be converted.

---

## Success Criteria

- [ ] All code compiles
- [ ] No functional changes
- [ ] More concise code
