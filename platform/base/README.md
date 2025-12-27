# Base Module

Foundational utilities shared by all platform modules.

## Purpose

This module provides core infrastructure that every other module depends on:
- Logging and tracing
- Serialization codecs
- Configuration management
- Common utilities

## Package Structure

```
com.reactive.platform/
├── observe/           # Logging and tracing infrastructure
│   ├── Log.java       # Structured logging with context
│   ├── Traced.java    # @Traced annotation for method tracing
│   └── TracedAspect.java
├── serialization/     # Codec abstractions
│   ├── Codec.java     # Encode/decode interface
│   ├── JsonCodec.java # Jackson-based JSON codec
│   └── Result.java    # Success/failure wrapper
├── config/            # Configuration utilities
├── id/                # ID generation (Snowflake, etc.)
└── Opt.java           # Optional utilities
```

## Usage

Add as a dependency in your module's `pom.xml`:

```xml
<dependency>
    <groupId>com.reactive</groupId>
    <artifactId>base</artifactId>
</dependency>
```

## Key Components

### Logging

```java
import com.reactive.platform.observe.Log;

Log.info("Processing request", "requestId", reqId, "userId", userId);
Log.error("Failed to process", e, "requestId", reqId);
```

### Tracing

```java
import com.reactive.platform.observe.Traced;

@Traced
public void processEvent(Event event) {
    // Method execution is automatically traced
}
```

### Serialization

```java
import com.reactive.platform.serialization.JsonCodec;

JsonCodec<MyEvent> codec = new JsonCodec<>(MyEvent.class);
byte[] bytes = codec.encode(event);
Result<MyEvent> result = codec.decode(bytes);
```

## Build

```bash
mvn compile           # Compile
mvn test              # Run tests
mvn install           # Install to local Maven repo
```

## Dependencies

- Jackson (JSON serialization)
- SLF4J (logging API)
- OpenTelemetry (distributed tracing)
- Typesafe Config (configuration)
