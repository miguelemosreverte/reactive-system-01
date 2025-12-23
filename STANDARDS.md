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
