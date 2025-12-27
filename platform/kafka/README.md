# Kafka Module

Kafka producer, consumer, and adaptive microbatching for high-throughput event streaming.

## Purpose

This module provides:
- High-performance Kafka producer with tunable settings
- Adaptive microbatching that automatically calibrates batch size and timing
- SQLite-based calibration persistence for consistent performance across restarts
- TracedKafka for distributed tracing integration

## Components

### KafkaPublisher

Standard Kafka producer wrapper with configurable settings:

```java
import com.reactive.platform.kafka.KafkaPublisher;

KafkaPublisher publisher = new KafkaPublisher(bootstrapServers, topic);
publisher.send(key, value);
```

### MicrobatchCollector

Adaptive batching that collects events and flushes based on:
- Batch size threshold
- Time window timeout
- Automatic calibration for optimal throughput

```java
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;

MicrobatchCollector collector = new MicrobatchCollector(
    publisher::sendBatch,
    batchSize,
    timeoutMs
);
collector.add(event);
```

### BatchCalibration

SQLite-persisted calibration that learns optimal batch parameters:

```java
import com.reactive.platform.gateway.microbatch.BatchCalibration;

BatchCalibration calibration = new BatchCalibration("/tmp/calibration.db");
int optimalBatchSize = calibration.getOptimalBatchSize();
```

## Brochures

Located in `brochures/`:

| Brochure | Description |
|----------|-------------|
| `kafka-fast` | Fire-and-forget (acks=0), maximum throughput |
| `kafka-tuned` | Optimized batch settings (acks=1) |
| `microbatch-collector` | Raw collector throughput without HTTP |

## Quick Start

### Run a Benchmark

```bash
# Using brochure system (requires Kafka running)
./reactive bench brochure run kafka-fast

# List available Kafka brochures
./reactive bench brochure list | grep -i kafka
```

### Configuration Options

| Setting | Description | Default |
|---------|-------------|---------|
| `acks` | Acknowledgment mode (0, 1, all) | 1 |
| `batch.size` | Batch size in bytes | 16384 |
| `linger.ms` | Time to wait for batch | 0 |
| `buffer.memory` | Total buffer memory | 33554432 |

## Package Structure

```
com.reactive.platform/
├── kafka/
│   ├── KafkaPublisher.java      # Producer wrapper
│   └── TracedKafka.java         # Tracing integration
└── gateway/microbatch/
    ├── MicrobatchCollector.java # Adaptive batching
    ├── BatchCalibration.java    # SQLite calibration
    └── MicrobatchingGateway.java # HTTP + Kafka combined
```

## Build

```bash
mvn compile                    # Compile
mvn test                       # Run tests (uses Testcontainers)
mvn test -Pbenchmark           # Run benchmarks
```

## Performance

Measured with Testcontainers Kafka:

| Mode | Throughput |
|------|------------|
| Fire-and-Forget (acks=0) | 1.2M msgs/s |
| Tuned Batching (acks=1) | 800K msgs/s |
| Default Settings | 400K msgs/s |

## Dependencies

- Kafka Clients
- SQLite JDBC (for calibration)
- Testcontainers (for testing)
- SLF4J (logging)
