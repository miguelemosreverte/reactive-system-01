# Platform

The platform layer contains all infrastructure modules that power the reactive system.

## Module Hierarchy

```
platform/
├── base/           # Foundation (logging, tracing, serialization)
├── http-server/    # HTTP server implementations
├── kafka/          # Kafka producer + microbatching
├── flink/          # Stream processing
└── integration/    # Combinatorial benchmarks
```

## Dependency Graph

```
base
 ├── http-server
 ├── kafka (depends on http-server for gateway)
 ├── flink (depends on kafka)
 └── integration (depends on all)
```

## Build

```bash
# Build all platform modules
cd platform && mvn compile

# Build from root
mvn compile -pl platform
```

## Independent Development

Each module can be developed and tested independently:

```bash
cd platform/http-server && mvn test -Pbenchmark
cd platform/kafka && mvn test -Pbenchmark
cd platform/flink && mvn test
```

## Brochures

Each module contains its own benchmarks in `brochures/`:

- `http-server/brochures/` - HTTP server benchmarks
- `kafka/brochures/` - Kafka + microbatch benchmarks
- `flink/brochures/` - Stream processing benchmarks
- `integration/brochures/` - Combined component benchmarks

Run `./reactive bench brochure list` from the project root to discover all brochures.
