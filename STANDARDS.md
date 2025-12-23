# Coding Standards

Code should look like Scala - functional, concise, type-safe.

## Core Principles

### 1. No Abstraction Leaks
- Functions do ONE thing
- If a function handles tracing, it doesn't handle business logic
- Implementation details stay hidden behind clean interfaces

### 2. No Third-Party Type Propagation
- Third-party types (OpenTelemetry, Jackson, etc.) exist in ONE file only
- The rest of the codebase uses our own types
- Example: `TracingImpl.java` contains all OpenTelemetry code, `Tracing.java` exposes clean API

### 3. No Null

**Rule: We never instantiate null. Ever.**

#### Boundaries vs Internal Code

**Critical distinction:**

1. **Third-party boundary** = where external code can return null
   - JSON deserialization (Jackson)
   - Kafka `record.value()`, `headers.lastHeader()`
   - Flink state `valueState.value()` (null = uninitialized)
   - `Map.get(key)` (always a boundary!)
   - HTTP response parsing

2. **Internal platform code** = WE own it, WE control it
   - Once normalized at boundary, values are GUARANTEED non-null
   - No defensive coding against ourselves
   - Trust the type system

**The rule: Normalize ONCE at the boundary, trust everywhere else.**

#### The `Opt.or()` Utility

Use at boundaries to capture nulls from third-party code:

```java
import static com.reactive.platform.Opt.or;

// At boundary (third-party can return null)
String value = or(externalApi.getValue(), "");
int count = or(flinkState.value(), 0);
Header h = or(record.headers().lastHeader("key"), defaultHeader);

// Type-parametric - works with any type
List<Item> items = or(response.getItems(), List.of());
```

#### Records = The Contract Between Modules

Records with compact constructors are the correct way for inter-module communication:

```java
public record CounterEvent(String sessionId, String requestId, String action) {
    public CounterEvent {
        // Normalize ONCE at construction (the boundary)
        sessionId = or(sessionId, "");
        requestId = or(requestId, "");
        action = or(action, "increment");
    }
}

// EVERYWHERE ELSE: Trust the record, no or() needed
void process(CounterEvent event) {
    String id = event.requestId();  // Guaranteed non-null - DO NOT use or()
    log.info("Processing {}", id);  // Safe
}
```

#### What IS and IS NOT a Boundary

```java
// IS a boundary - use or()
Jackson.readValue(json, MyRecord.class)  // JSON can have nulls
record.value()                            // Kafka message can be null
state.value()                             // Flink state can be null
map.get(key)                              // Map access can return null
headers.lastHeader(key)                   // Header may not exist

// IS NOT a boundary - DO NOT use or()
event.requestId()      // Our record - already normalized
input.sessionId()      // Passed from our code - trust it
result.customerId()    // We constructed this - trust it
```

#### Anti-Pattern: Defensive Coding Against Ourselves

```java
// BAD: Treating our own values as untrusted
String requestId = or(event.requestId(), "");  // WRONG - we own this!
String customerId = or(input.customerId(), ""); // WRONG - we set this!

// GOOD: Trust internal values, normalize only at construction
public record Event(String requestId) {
    public Event {
        requestId = or(requestId, "");  // Normalize once here
    }
}
// Then everywhere: event.requestId() is guaranteed non-null
```

#### Immutable Update Methods (with-prefixed)

When records need field updates (e.g., adding runtime data after deserialization),
use `with`-prefixed methods that return new instances:

```java
public record CounterEvent(
    String sessionId,
    String requestId,
    long deserializedAt,
    String traceparent
) {
    public CounterEvent {
        sessionId = or(sessionId, "");
        requestId = or(requestId, "");
        traceparent = or(traceparent, "");
    }

    // Wither methods for runtime field updates
    public CounterEvent withDeserializedAt(long t) {
        return new CounterEvent(sessionId, requestId, t, traceparent);
    }

    public CounterEvent withTraceContext(String traceparent) {
        return new CounterEvent(sessionId, requestId, deserializedAt, traceparent);
    }
}

// Usage in deserializer:
CounterEvent event = objectMapper.readValue(json, CounterEvent.class)
    .withDeserializedAt(System.currentTimeMillis())
    .withTraceContext(traceparent);
```

For timing records with many fields, provide a combined wither:

```java
public record EventTiming(long start, long end) {
    public EventTiming withDroolsTiming(long start, long end) {
        return new EventTiming(start, end);
    }
}
```

#### Avoid Maps for Inter-Module DTOs

Maps are always boundaries (`map.get()` can return null). Use records instead:

```java
// BAD: Map access is always a boundary
Map<String, String> data = getMessage();
String id = or(data.get("requestId"), "");  // Defensive code needed

// GOOD: Record is a contract
record Message(String requestId) {
    public Message { requestId = or(requestId, ""); }
}
Message msg = getMessage();
String id = msg.requestId();  // Guaranteed non-null
```

#### Null-Free Type Mappings

| Instead of null... | Use... |
|-------------------|--------|
| `String` | `""` (empty string) |
| `List<T>` | `List.of()` (empty list) |
| `Map<K,V>` | `Map.of()` (empty map) |
| `Optional<T>` | `Optional.empty()` |
| Void/Unit returns | `Optional.empty()` |

#### Why Not Custom Unit Types?

For void/side-effect operations, use `Optional.empty()` instead of custom Unit types:
- No custom primitives to define or import
- Standard Java, no cognitive overhead
- Following Vavr's philosophy: custom Unit is "an ugly workaround"

```java
// Good: Use Optional.empty() for void operations
public static void traced(String op, Runnable work) {
    impl.traced(op, () -> { work.run(); return Optional.empty(); });
}

// Bad: Custom Unit type requires definition and imports
public static Unit traced(String op, Runnable work) { ... }
```

### 4. Errors as Types
- Use `Result<T>` or `Either<Error, T>` patterns
- No exceptions for control flow
- Exceptions only for truly exceptional cases (bugs, system failures)

### 5. DRY (Don't Repeat Yourself)
- Extract common patterns into shared utilities
- One source of truth for each concept

### 6. Functional Patterns Over Classes
- Pure static functions preferred
- Immutable data
- No side effects in business logic
- Side effects pushed to edges (I/O boundaries)

### 7. Records Over POJOs
- Use Java records for data classes
- Immutable by default
- No getters/setters ceremony

## Orthogonal Concerns

These are handled by infrastructure, not business logic:
- **Tracing**: Use `Tracing.traced()`, `Tracing.attr()`
- **Logging**: Infrastructure concern (to be refactored)
- **Metrics**: Collected automatically by OTel agent
- **Serialization**: Codec classes at boundaries

## Validation

Compilation is the primary validation:
```bash
# Platform (Java)
cd platform && mvn compile -q

# Application (Java)
cd application && mvn compile -q

# CLI (Go)
cd platform/cli && go build .
```

---

## Compliance Tracking

Files marked with `[x]` have been validated against all coding standards.
Files marked with `[ ]` need review. Sub-items list specific issues to fix.

### platform/src (Core Library)

```
platform/src/main/java/com/reactive/platform/
├── benchmark/
│   [x] BaseBenchmark.java
│   [x] Benchmark.java
│   [x] BenchmarkCli.java (null check L93 is boundary - Map.get)
│   [x] BenchmarkReportGenerator.java
│   [x] BenchmarkResult.java
│   [ ] BenchmarkTypes.java
│       - Span, LogEntry records: add compact constructors for JSON deserialization
│   [ ] BottleneckAnalyzer.java (boundary checks for external trace data - acceptable)
│   [ ] ObservabilityFetcher.java (boundary checks for HTTP/JSON - acceptable)
│   [x] package-info.java
├── id/
│   [x] IdGenerator.java
├── kafka/
│   [x] KafkaPublisher.java (null checks L109,149,193 are Kafka callback boundary - acceptable)
├── observability/
│   [x] TraceValidator.java (boundary checks for external trace data - acceptable)
├── observe/
│   [x] InvestigationContext.java
│   [x] Log.java
│   [x] LogImpl.java (OTel types properly confined)
│   [x] Traced.java
│   [x] TracedAspect.java
├── replay/
│   [x] EventStore.java
│   [x] FSMAdapter.java
│   [ ] KafkaEventStore.java (boundary - Kafka deserialization)
│   [x] ReplayResult.java
│   [x] ReplayService.java
│   [x] StoredEvent.java
└── serialization/
    [x] Codec.java
    [x] JsonCodec.java
    [x] Result.java (uses Objects.requireNonNull correctly)
```

### application/src (Counter Application)

```
application/src/main/java/com/reactive/counter/
├── [x] CounterApplication.java
├── api/
│   [x] ActionRequest.java (compact constructor normalizes nulls)
│   [x] CounterController.java (uses Log.attr instead of OTel Span)
│   [x] DiagnosticController.java (boundary - external JSON/HTTP)
├── bff/
│   [x] BffController.java (migrated to ObservabilityFetcher)
├── domain/
│   [x] CounterEvent.java (Timing uses primitive long, not Long)
│   [x] CounterFSM.java (boundary - fromString handles null)
│   [x] CounterState.java (boundary - fromString handles null)
├── replay/
│   [ ] CounterFSMAdapter.java
│   [ ] ReplayController.java
├── service/
│   [ ] ResultConsumerService.java (uses OTel directly - boundary for Kafka tracing)
└── websocket/
    [ ] CounterWebSocketHandler.java
    [ ] WebSocketConfig.java
```

### platform/deployment/docker/gateway

Note: Gateway uses Lombok @Data for DTOs (mutable pattern). OTel is used directly
in KafkaService since @KafkaListener requires manual span creation.

```
gateway/src/main/java/com/reactive/gateway/
├── [x] GatewayApplication.java
├── config/
│   [x] CorsConfig.java
│   [x] KafkaConfig.java
│   [x] WebSocketConfig.java
├── controller/
│   [x] CounterController.java (boundary null checks on input)
│   [x] HealthController.java
│   [x] TraceController.java
├── model/
│   [x] CounterCommand.java (Lombok @Data - mutable DTO)
│   [x] CounterResult.java (Lombok @Data - mutable DTO)
├── service/
│   [x] IdGenerator.java
│   [x] KafkaService.java (OTel for Kafka - not auto-instrumented)
└── websocket/
    [x] WebSocketHandler.java
```

### platform/deployment/docker/flink

Note: Flink 1.18+ supports immutable records. All models are now Java records with
compact constructors for boundary normalization and wither methods for immutable updates.

```
flink/src/main/java/com/reactive/flink/
├── [x] CounterJob.java
├── async/
│   [x] AsyncDroolsEnricher.java (uses record accessors)
├── model/
│   [x] CounterEvent.java (record with compact constructor)
│   [x] CounterResult.java (record with compact constructor)
│   [x] EventTiming.java (record with wither methods)
│   [x] PreDroolsResult.java (record with compact constructor)
├── processor/
│   [x] AdaptiveLatencyController.java
│   [x] CounterProcessor.java (uses record accessors, or() for Flink state)
└── serialization/
    [x] CounterEventDeserializer.java (boundary - uses wither methods)
    [x] CounterResultSerializer.java
    [x] TracingCounterResultSerializer.java (uses record accessors)
    [x] TracingKafkaDeserializer.java (boundary - uses wither methods)
```

### platform/deployment/docker/drools

Note: Drools is a standalone service. OTel auto-instruments HTTP but
additional span attributes are added manually. Boundary null checks on input.

```
drools/src/main/java/com/reactive/drools/
├── [x] DroolsApplication.java
├── config/
│   [x] DroolsConfig.java
├── controller/
│   [x] HealthController.java
│   [x] RuleController.java
├── model/
│   [x] Counter.java
│   [x] EvaluationRequest.java
│   [x] EvaluationResponse.java
└── service/
    [x] RuleService.java (adds attrs to auto-instrumented span)
```

### platform/cli (Go)

```
platform/cli/
├── [ ] main.go
├── cmd/
│   [ ] bench.go
│   [ ] bench_doctor.go
│   [ ] bench_history.go
│   [ ] diagnose.go
│   [ ] diagnostics.go
│   [ ] e2e.go
│   [ ] hooks.go
│   [ ] lifecycle.go
│   [ ] logs.go
│   [ ] root.go
│   [ ] send.go
│   [ ] status.go
│   [ ] trace.go
└── internal/
    ├── repl/
    │   [ ] repl.go
    └── usage/
        [ ] tracker.go
```

### platform/ui (TypeScript/React)

```
platform/ui/src/
├── [ ] App.tsx
├── [ ] main.tsx
├── [ ] vite-env.d.ts
├── components/
│   [ ] ConnectionStatus.tsx
│   [ ] Counter.tsx
│   [ ] Documentation.tsx
│   [ ] EventFlow.tsx
│   [ ] SystemStatus.tsx
│   [ ] TraceViewer.tsx
│   ├── common/
│   │   [ ] ServiceTag.tsx
│   │   [ ] StatusBadge.tsx
│   │   [ ] ThemeToggle.tsx
│   │   [ ] index.ts
│   └── layout/
│       [ ] AppHeader.tsx
│       [ ] AppLayout.tsx
│       [ ] AppSider.tsx
│       [ ] index.ts
├── context/
│   [ ] ThemeContext.tsx
├── hooks/
│   [ ] useWebSocket.ts
├── pages/
│   └── benchmark/
│       [ ] BenchmarkIndex.tsx
│       [ ] BenchmarkReport.tsx
│       [ ] LogsModal.tsx
│       [ ] TraceModal.tsx
│       [ ] index-entry.tsx
│       [ ] index.ts
│       [ ] report-entry.tsx
│       [ ] types.ts
├── theme/
│   [ ] tokens.ts
└── utils/
    [ ] urls.ts
```

### Test Files

```
application/src/test/java/com/reactive/counter/benchmark/
├── [ ] FlinkBenchmark.java
├── [ ] FullBenchmark.java
└── [ ] KafkaBenchmark.java

platform/deployment/docker/gateway/src/test/java/.../benchmark/
├── [ ] GatewayBenchmark.java
└── [ ] HttpBenchmark.java

platform/deployment/docker/drools/src/test/java/.../benchmark/
└── [ ] DroolsBenchmark.java
```
