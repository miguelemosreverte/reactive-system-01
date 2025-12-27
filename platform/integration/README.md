# Integration Module

Combinatorial benchmarks that test components together.

## Purpose

This module combines multiple platform components to measure real-world performance:
- **Gateway benchmarks**: HTTP server + Kafka producer
- **Full pipeline benchmarks**: HTTP + Kafka + Flink (end-to-end)

While individual modules test components in isolation, this module tests how they perform when integrated.

## Benchmark Types

### Gateway Benchmarks (HTTP + Kafka)

Test different HTTP server implementations combined with Kafka publishing:

| Brochure | HTTP Server | Kafka Mode |
|----------|-------------|------------|
| `gateway-rocket` | Rocket | Microbatch |
| `gateway-netty-microbatch` | Netty | Microbatch |
| `gateway-hyper` | Hyper | Microbatch |
| `gateway-turbo` | Turbo | Microbatch |
| `gateway-boss-worker` | Boss/Worker | Microbatch |
| `gateway-raw` | Raw NIO | Microbatch |
| `gateway-spring` | Spring WebFlux | Standard |

### Full Pipeline Benchmarks (HTTP + Kafka + Flink)

Test complete request flow through all components:

| Brochure | Description |
|----------|-------------|
| `full-netty-microbatch` | Optimized E2E with Netty + microbatch |
| `full-spring` | Baseline E2E with Spring WebFlux |

## Quick Start

### Run a Gateway Benchmark

```bash
# Requires Kafka running
./cli.sh start kafka

# Run benchmark
./reactive bench brochure run gateway-rocket

# Or run all gateway benchmarks
./reactive bench brochure run-all | grep gateway
```

### Run a Full Pipeline Benchmark

```bash
# Requires full infrastructure
./cli.sh start

# Run benchmark
./reactive bench brochure run full-netty-microbatch
```

## Brochures

Located in `brochures/`:

```
brochures/
├── gateway-rocket/
│   └── brochure.yaml
├── gateway-netty-microbatch/
│   └── brochure.yaml
├── gateway-hyper/
│   └── brochure.yaml
├── gateway-spring/
│   └── brochure.yaml
├── full-netty-microbatch/
│   └── brochure.yaml
└── full-spring/
    └── brochure.yaml
```

## Key Benchmarks

### GatewayComparisonBenchmark

Compares different HTTP + Kafka combinations:

```java
// Tests combinations like:
// - RocketHttpServer + MicrobatchCollector
// - NettyHttpServer + MicrobatchCollector
// - SpringBootHttpServer + StandardKafkaProducer
```

### EndToEndBenchmark

Measures complete request latency through all components:

```java
// Request → HTTP → Kafka → Flink → Drools → Kafka → Response
// Measures total latency and throughput
```

## Build

```bash
mvn compile                    # Compile
mvn test                       # Run tests
mvn test -Pbenchmark           # Run benchmarks
```

## Package Structure

```
com.reactive.integration/
└── benchmark/
    ├── GatewayComparisonBenchmark.java  # HTTP + Kafka
    └── EndToEndBenchmark.java           # Full pipeline
```

## Performance Results

Gateway benchmarks (HTTP + Kafka):

| Configuration | Throughput |
|---------------|------------|
| Rocket + Microbatch | 191K ops/s |
| Netty + Microbatch | 165K ops/s |
| Spring + Standard | 15K ops/s |

## Dependencies

- http-server (all HTTP implementations)
- kafka (producer + microbatching)
- JMH (benchmarking)
- Testcontainers (Kafka for testing)
