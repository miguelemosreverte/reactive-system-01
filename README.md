# Reactive System

A high-performance stream processing platform with benchmarking infrastructure as a first-class concern.

## What This Project Is

This is a **performance engineering laboratory** built around a real-time counter application. The counter itself is simple - the value lies in the systematic approach to measuring and optimizing each layer:

- **HTTP Server Layer** - 9 implementations from Spring WebFlux baseline to custom NIO servers achieving 700K+ req/s
- **Gateway Layer** - Adaptive microbatching that combines HTTP ingestion with Kafka publishing at 150K+ ops/s
- **Stream Processing** - Flink jobs consuming from Kafka, applying Drools business rules
- **End-to-End Pipelines** - Full request flow from HTTP to Kafka to Flink with distributed tracing

Each component is independently benchmarkable, allowing targeted optimization without guesswork.

## Project Structure

```
reactive-system-01/
├── platform/                    # Infrastructure (reusable)
│   ├── base/                    # Shared utilities (logging, tracing, serialization)
│   ├── http-server/             # 9 HTTP server implementations
│   ├── kafka/                   # Kafka producer, consumer, microbatching
│   ├── flink/                   # Stream processing jobs
│   ├── gateway/                 # HTTP + Kafka gateway service
│   └── integration/             # Combinatorial benchmarks
├── application/                 # Business logic (counter-specific)
│   ├── counter/                 # Counter application (Spring Boot)
│   ├── drools/                  # Business rules engine
│   └── ui/                      # React frontend
├── cli/                         # Go CLI for benchmarking
└── reports/                     # GitHub Pages benchmark results
```

This is a **Maven multi-module monorepo**. Each platform module can be developed and benchmarked independently:

```bash
cd platform/http-server && mvn test -Pbenchmark  # Benchmark HTTP servers
cd platform/kafka && mvn test -Pbenchmark        # Benchmark Kafka
mvn install                                       # Build everything from root
```

## Quick Start

### Run the Full System

```bash
./cli.sh start          # Start all Docker services
./cli.sh doctor         # Verify all services are healthy
open http://localhost:3000  # Open the counter UI
```

### Run Benchmarks

```bash
./reactive bench brochure list              # List all available benchmarks
./reactive bench brochure run http-rocket   # Run a specific benchmark
./reactive bench brochure run-all           # Run the full benchmark marathon
```

### Smoke Test

```bash
./scripts/smoke-test.sh      # Quick validation (5s per test)
./scripts/smoke-test.sh 3    # Faster validation (3s per test)
```

## The Brochure System

Benchmarks are defined as **brochures** - YAML files that specify what to run, how to run it, and how to report results. Each module contains its own brochures:

| Module | Brochures | Focus |
|--------|-----------|-------|
| platform/http-server | 9 | HTTP server implementations |
| platform/kafka | 3 | Kafka producer + microbatch collector |
| platform/flink | 1 | Stream processing throughput |
| platform/integration | 9 | Gateway combinations + full E2E pipelines |

Run `./reactive bench brochure list` to see all 22 benchmarks with descriptions.

## Current Performance

Measured on Apple M-series, same-container benchmarking:

| Component | Implementation | Throughput |
|-----------|---------------|------------|
| HTTP Server | Rocket (NIO + SO_REUSEPORT) | 777K req/s |
| HTTP Server | Spring WebFlux (baseline) | 180K req/s |
| Gateway | Netty + Adaptive Microbatch | 191K ops/s |
| Gateway | Spring WebFlux (baseline) | 15K ops/s |
| Kafka Producer | Fire-and-Forget | 1.2M msgs/s |

## Architecture

```
┌─────────────┐    HTTP/WS    ┌─────────────────┐
│   React UI  │◄─────────────►│     Gateway     │
│  (Counter)  │               │  (HTTP+Kafka)   │
└─────────────┘               └────────┬────────┘
                                       │
                   ┌───────────────────┼───────────────────┐
                   │                   │                   │
                   ▼                   ▼                   ▼
             ┌──────────┐       ┌──────────┐       ┌──────────┐
             │  Kafka   │◄─────►│  Flink   │◄─────►│  Drools  │
             │          │       │          │       │          │
             └──────────┘       └──────────┘       └──────────┘
```

### Data Flow

1. User action in React UI
2. Gateway receives HTTP request
3. Adaptive microbatcher collects events
4. Batch published to Kafka `counter-events`
5. Flink consumes, maintains state, calls Drools
6. Drools evaluates business rules
7. Result published to `counter-results`
8. Gateway pushes update to UI

## CLI Reference

### Docker Operations (cli.sh)

```bash
./cli.sh start [service]      # Start all or specific service
./cli.sh stop [service]       # Stop all or specific service
./cli.sh logs [service]       # View logs
./cli.sh doctor               # Health check all services
./cli.sh status               # Show running services
```

### Benchmarking (reactive)

```bash
./reactive bench brochure list              # List benchmarks
./reactive bench brochure run <name>        # Run single benchmark
./reactive bench brochure run-all           # Run all benchmarks
./reactive bench brochure run-all --quick   # Quick validation mode
```

## Services

| Service | Port | Description |
|---------|------|-------------|
| UI | 3000 | React frontend |
| Gateway | 8080 | HTTP + WebSocket API |
| Flink | 8081 | Stream processing dashboard |
| Drools | 8180 | Business rules engine |
| Kafka | 9092 | Message broker |
| Grafana | 3001 | Observability dashboards |

## Development

### Module Independence

Each module has:
- Own `pom.xml` with dependencies
- Own `src/` with implementation
- Own `brochures/` with benchmark definitions
- Own `Dockerfile` for deployment

Shared utilities live in `base/` and are inherited by all modules.

### Adding a New Benchmark

1. Create `<module>/brochures/<name>/brochure.yaml`
2. Define benchmark class, duration, parameters
3. Run `./reactive bench brochure list` to verify discovery
4. Run `./reactive bench brochure run <name>` to execute

### Running Tests

```bash
mvn test                           # Run all tests
mvn test -pl http-server           # Test specific module
./scripts/smoke-test.sh            # Quick platform validation
```

## Technology Stack

| Technology | Purpose |
|------------|---------|
| Java 21 | Platform runtime |
| Maven | Multi-module build |
| Netty/NIO | High-performance HTTP |
| Apache Kafka | Message streaming |
| Apache Flink | Stream processing |
| Drools | Business rules |
| React + Vite | Frontend UI |
| Docker Compose | Local orchestration |
| Go | CLI tooling |

## License

MIT
