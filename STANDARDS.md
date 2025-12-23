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

When third-party code returns null (JSON parsing, Kafka callbacks, external APIs), we intercept it immediately at the boundary and convert to a proper value.

#### Null-Free Type Mappings

| Instead of null... | Use... |
|-------------------|--------|
| `String` | `""` (empty string) |
| `List<T>` | `List.of()` (empty list) |
| `Map<K,V>` | `Map.of()` (empty map) |
| `Optional<T>` | `Optional.empty()` |
| Void/Unit returns | `Optional.empty()` |

#### Boundary Handling Pattern

```java
// At system boundaries (JSON, HTTP, Kafka), intercept nulls immediately:
String value = externalApi.getValue();
String safeValue = value != null ? value : "";  // Never propagate null

// For Optional wrapping:
Optional<User> user = Optional.ofNullable(repository.find(id));
```

#### Record Compact Constructors

Records that may be deserialized (JSON) must have compact constructors to normalize nulls:

```java
public record Config(String name, List<String> items) {
    public Config {
        name = name != null ? name : "";
        items = items != null ? items : List.of();
    }
}
```

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
├── [ ] CounterApplication.java
├── api/
│   [ ] ActionRequest.java
│   [ ] CounterController.java
│   [ ] DiagnosticController.java
├── bff/
│   [ ] BffController.java
├── domain/
│   [ ] CounterEvent.java
│   [ ] CounterFSM.java
│   [ ] CounterState.java
├── replay/
│   [ ] CounterFSMAdapter.java
│   [ ] ReplayController.java
├── service/
│   [ ] ResultConsumerService.java
└── websocket/
    [ ] CounterWebSocketHandler.java
    [ ] WebSocketConfig.java
```

### platform/deployment/docker/gateway

```
gateway/src/main/java/com/reactive/gateway/
├── [ ] GatewayApplication.java
├── config/
│   [ ] CorsConfig.java
│   [ ] KafkaConfig.java
│   [ ] WebSocketConfig.java
├── controller/
│   [ ] CounterController.java
│   [ ] HealthController.java
│   [ ] TraceController.java
├── model/
│   [ ] CounterCommand.java
│   [ ] CounterResult.java
├── service/
│   [ ] IdGenerator.java
│   [ ] KafkaService.java
└── websocket/
    [ ] WebSocketHandler.java
```

### platform/deployment/docker/flink

```
flink/src/main/java/com/reactive/flink/
├── [ ] CounterJob.java
├── async/
│   [ ] AsyncDroolsEnricher.java
├── model/
│   [ ] CounterEvent.java
│   [ ] CounterResult.java
│   [ ] EventTiming.java
│   [ ] PreDroolsResult.java
├── processor/
│   [ ] AdaptiveLatencyController.java
│   [ ] CounterProcessor.java
└── serialization/
    [ ] CounterEventDeserializer.java
    [ ] CounterResultSerializer.java
    [ ] TracingCounterResultSerializer.java
    [ ] TracingKafkaDeserializer.java
```

### platform/deployment/docker/drools

```
drools/src/main/java/com/reactive/drools/
├── [ ] DroolsApplication.java
├── config/
│   [ ] DroolsConfig.java
├── controller/
│   [ ] HealthController.java
│   [ ] RuleController.java
├── model/
│   [ ] Counter.java
│   [ ] EvaluationRequest.java
│   [ ] EvaluationResponse.java
└── service/
    [ ] RuleService.java
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
