# Flink Module

Apache Flink stream processing jobs for the reactive system.

## Purpose

This module contains Flink jobs that:
- Consume events from Kafka
- Maintain stateful processing
- Call Drools for business rule evaluation
- Produce results back to Kafka

## Main Job

### CounterJob

The primary stream processing job that:
1. Consumes `counter-events` from Kafka
2. Maintains counter state per session
3. Calls Drools service for rule evaluation
4. Produces `counter-results` to Kafka

```java
// Job topology
KafkaSource<CounterEvent> source = KafkaSource.<CounterEvent>builder()
    .setBootstrapServers(kafkaServers)
    .setTopics("counter-events")
    .build();

DataStream<CounterResult> results = env
    .fromSource(source)
    .keyBy(CounterEvent::getSessionId)
    .process(new CounterProcessor())
    .addSink(kafkaSink);
```

## Package Structure

```
com.reactive.flink/
├── CounterJob.java           # Main entry point
├── model/
│   ├── CounterEvent.java     # Input event
│   ├── CounterResult.java    # Output result
│   └── CounterState.java     # Stateful counter
├── processor/
│   ├── CounterProcessor.java # Keyed processing
│   └── DroolsClient.java     # HTTP client for Drools
├── serialization/
│   ├── CounterEventSchema.java
│   └── CounterResultSchema.java
├── async/                    # Async I/O for Drools
└── diagnostic/               # Debugging utilities
```

## Brochures

Located in `brochures/`:

| Brochure | Description |
|----------|-------------|
| `flink-stream` | Stream processing throughput benchmark |

## Running

### Local Development

```bash
# Start Kafka and Drools first
./cli.sh start kafka drools

# Run the Flink job locally
mvn exec:java -Dexec.mainClass="com.reactive.flink.CounterJob" \
  -Dexec.args="--kafka localhost:9092 --drools http://localhost:8180"
```

### Docker Deployment

```bash
# Build the shaded JAR
mvn package -DskipTests

# Submit to Flink cluster
docker exec flink-jobmanager flink run /opt/flink/jobs/flink-1.0.0.jar
```

### With Docker Compose

```bash
./cli.sh start                 # Starts all services including Flink
open http://localhost:8081     # Flink Dashboard
```

## Build

```bash
mvn compile                    # Compile
mvn package                    # Build shaded JAR
mvn test                       # Run tests
```

The `package` phase creates a shaded JAR with all dependencies (except Flink runtime) for cluster deployment.

## Configuration

| Property | Description | Default |
|----------|-------------|---------|
| `kafka.bootstrap.servers` | Kafka connection | localhost:9092 |
| `drools.url` | Drools service URL | http://localhost:8180 |
| `checkpoint.interval` | Checkpoint interval ms | 60000 |
| `parallelism` | Job parallelism | 2 |

## Flink Version

This module uses Flink 1.18.x and requires Java 17 (Flink's current maximum supported version).

## Dependencies

- Flink Streaming Java
- Flink Kafka Connector
- Apache HttpClient 5 (for Drools calls)
- Jackson (serialization)
