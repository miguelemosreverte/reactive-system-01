# Try-Catch to Result Refactoring

**Goal**: Replace try-catch blocks with functional `Result.of()` pattern (like Scala's Try)

## Patterns to convert

### Pattern 1: Value or default
```java
// Before:
try { return riskyOperation(); }
catch (Exception e) { return defaultValue; }

// After:
return Result.of(() -> riskyOperation()).getOrElse(defaultValue);
```

### Pattern 2: Optional result
```java
// Before (tryGet helper):
try { return Optional.of(supplier.get()); }
catch (Exception e) { return Optional.empty(); }

// After:
return Result.of(supplier::get).toOptional();
```

### Pattern 3: Chain operations
```java
// Before:
try {
    var a = step1();
    var b = step2(a);
    return step3(b);
} catch (Exception e) { return fallback; }

// After:
return Result.of(() -> step1())
    .map(a -> step2(a))
    .map(b -> step3(b))
    .getOrElse(fallback);
```

## Files to refactor

1. ConfigGenerator - tryGet() helper
2. PlatformConfig - allocatedMemoryMb inline try-catch
3. IdGenerator - getNodeId() fallback pattern
4. KafkaEventStore - isHealthy() boolean return
5. FastHttpServer - handler with error response
6. StandaloneClient - channel close with ignore

## Files to skip (need special handling)

- LogImpl/TracedKafka: Need span.recordException before rethrow
- MicrobatchCollector: Need error logging
- HTTP servers: Need specific error responses
- Benchmarks: Performance-critical loops

## Success Criteria

- [ ] Simpler, more declarative error handling
- [ ] Consistent use of Result type
- [ ] All code compiles
