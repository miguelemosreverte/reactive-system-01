# Avro Implementation Plan

## Current State

All Kafka messages use JSON serialization (~150 bytes per event).

## Goal

Use Avro for internal Kafka messages (~50 bytes per event, 3x smaller).

```
User ──JSON──> API ──AVRO──> Kafka ──AVRO──> Flink ──AVRO──> Kafka
                │                                              │
                └──────────── Internal (binary) ───────────────┘

APIs, WebSocket, Debug ──> Always JSON (human-readable)
```

## Options

### Option 1: Shared Platform Module (Recommended)

Platform generates Avro classes, other modules depend on it.

```
platform/src/main/avro/
├── CounterEvent.avsc      # Source of truth
└── CounterResult.avsc

flink/pom.xml              # depends on platform
gateway/pom.xml            # depends on platform
application/pom.xml        # already depends on platform
```

**Pros:** Single source of truth, no schema drift
**Cons:** Build dependency between modules

### Option 2: Duplicate Schemas

Each module has its own copy of .avsc files.

**Pros:** Independent builds
**Cons:** Schema drift risk, maintenance burden

### Option 3: Schema Registry

Use Confluent Schema Registry for centralized schema management.

**Pros:** Runtime schema evolution, versioning
**Cons:** Additional infrastructure, more complexity

## Implementation Steps (When Ready)

1. **Flink First** (biggest impact)
   - Add Avro dependency to flink/pom.xml
   - Copy .avsc files or depend on platform
   - Replace CounterEventDeserializer with Avro
   - Replace CounterResultSerializer with Avro

2. **Gateway/Application**
   - Update KafkaPublisher to use AvroCodec
   - Keep REST APIs as JSON (user-facing)

3. **Replay Service**
   - KafkaEventStore reads Avro, converts to Map for replay
   - Replay output stays JSON (debug-friendly)

## Size Comparison

| Format | CounterEvent | CounterResult |
|--------|--------------|---------------|
| JSON   | ~150 bytes   | ~200 bytes    |
| Avro   | ~50 bytes    | ~70 bytes     |

## When to Implement

- When throughput exceeds 10K events/sec
- When Kafka storage becomes a concern
- When network bandwidth is constrained

## Not Doing Now

Current JSON performance is sufficient for development and moderate load.
The codec infrastructure (AvroCodec, Codecs) is ready when needed.
