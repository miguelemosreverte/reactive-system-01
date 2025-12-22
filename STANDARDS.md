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
- Never write `null` in our code
- Wrap third-party nulls immediately: `Optional.ofNullable(thirdParty.getValue())`
- Use empty strings `""` instead of null for String fields
- Use `Optional<T>` for truly optional values

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
