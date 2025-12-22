# Flink Component Documentation

## Overview

The Flink service is the stateful stream processor at the heart of the reactive system. It consumes counter events from Kafka, maintains per-session state, evaluates business rules via Drools, and publishes results.

## Technology Stack

| Technology | Purpose |
|------------|---------|
| Apache Flink 1.18 | Stream processing framework |
| Java 17 | Runtime environment |
| Maven | Build and dependency management |
| Kafka Connector | Flink-Kafka integration |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Flink Job: Counter Processing               │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    CounterJob.java                       │   │
│  │  - Main entry point                                      │   │
│  │  - Configures Kafka sources and sinks                    │   │
│  │  - Sets up keyed stream processing                       │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                 CounterProcessor.java                    │   │
│  │  - KeyedProcessFunction                                  │   │
│  │  - Maintains ValueState<Integer> per session             │   │
│  │  - Processes increment/decrement/set operations          │   │
│  │  - Calls Drools REST API                                 │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│              ┌───────────────┴───────────────┐                  │
│              ▼                               ▼                  │
│  ┌─────────────────────┐       ┌─────────────────────────────┐ │
│  │  CounterEvent.java  │       │    CounterResult.java       │ │
│  │  - Input model      │       │    - Output model           │ │
│  └─────────────────────┘       └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
         │                                      │
         │ Kafka Source                         │ Kafka Sink
         ▼                                      ▼
┌─────────────────┐                  ┌─────────────────────┐
│ counter-events  │                  │  counter-results    │
└─────────────────┘                  └─────────────────────┘
```

## Key Files

### `src/main/java/com/reactive/flink/CounterJob.java`
Main Flink job definition:

```java
// 1. Create execution environment
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// 2. Configure Kafka source
KafkaSource<CounterEvent> source = KafkaSource.<CounterEvent>builder()
    .setBootstrapServers("kafka:29092")
    .setTopics("counter-events")
    .setGroupId("flink-counter-processor")
    .setValueOnlyDeserializer(new CounterEventDeserializer())
    .build();

// 3. Configure Kafka sink
KafkaSink<CounterResult> sink = KafkaSink.<CounterResult>builder()
    .setBootstrapServers("kafka:29092")
    .setRecordSerializer(new CounterResultSerializer("counter-results"))
    .build();

// 4. Build processing pipeline
env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka Source")
    .keyBy(CounterEvent::getSessionId)  // Key by session
    .process(new CounterProcessor())     // Stateful processing
    .sinkTo(sink);                       // Output to Kafka

// 5. Execute
env.execute("Counter Processing Job");
```

### `src/main/java/com/reactive/flink/processor/CounterProcessor.java`
Stateful processing logic:

```java
public class CounterProcessor
    extends KeyedProcessFunction<String, CounterEvent, CounterResult> {

    // Per-key state (one Integer per sessionId)
    private ValueState<Integer> counterState;

    @Override
    public void open(Configuration parameters) {
        // Initialize state descriptor
        counterState = getRuntimeContext().getState(
            new ValueStateDescriptor<>("counter", Integer.class)
        );
    }

    @Override
    public void processElement(CounterEvent event, Context ctx,
                               Collector<CounterResult> out) {
        // 1. Get current value (default 0)
        Integer currentValue = counterState.value();
        if (currentValue == null) currentValue = 0;

        // 2. Apply operation
        switch (event.getAction()) {
            case "increment":
                currentValue += event.getValue();
                break;
            case "decrement":
                currentValue -= event.getValue();
                break;
            case "set":
                currentValue = event.getValue();
                break;
        }

        // 3. Update state
        counterState.update(currentValue);

        // 4. Call Drools for rule evaluation
        DroolsResponse drools = callDrools(currentValue);

        // 5. Emit result
        out.collect(new CounterResult(
            event.getSessionId(),
            currentValue,
            drools.getAlert(),
            drools.getMessage(),
            System.currentTimeMillis()
        ));
    }
}
```

### `src/main/java/com/reactive/flink/model/CounterEvent.java`
Input event model:

```java
public class CounterEvent {
    private String sessionId;   // Links to UI session
    private String action;      // increment, decrement, set
    private int value;          // Amount for operation
    private long timestamp;     // When event occurred
}
```

### `src/main/java/com/reactive/flink/model/CounterResult.java`
Output result model:

```java
public class CounterResult {
    private String sessionId;   // Links back to UI session
    private int currentValue;   // New counter value
    private String alert;       // NORMAL, WARNING, CRITICAL, etc.
    private String message;     // Human-readable description
    private long timestamp;     // Processing timestamp
}
```

## Flink Concepts

### Keyed Streams
Events are partitioned by key (sessionId):
- All events for same session go to same operator instance
- State is isolated per key
- Enables parallel processing of different sessions

```java
.keyBy(CounterEvent::getSessionId)
```

### ValueState
Flink's managed state for single values per key:

```java
ValueState<Integer> counterState;
counterState.value();   // Read
counterState.update(n); // Write
counterState.clear();   // Delete
```

**Properties:**
- Automatically checkpointed
- Survives job restarts
- Isolated per key

### Exactly-Once Processing
Flink guarantees each event is processed exactly once:
1. Kafka offsets committed with checkpoints
2. State rolled back on failure
3. Reprocessing from last checkpoint

## Drools Integration

The processor calls Drools via REST:

```java
private DroolsResponse callDrools(int value) {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://drools:8080/api/evaluate"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"value\": " + value + "}"
        ))
        .build();

    HttpResponse<String> response = client.send(request,
        HttpResponse.BodyHandlers.ofString());

    return parseResponse(response.body());
}
```

**Why REST instead of embedded?**
- Drools rules can be updated without redeploying Flink
- Separate scaling of rule evaluation
- Cleaner service boundaries

## Build Process

### Maven Build
```bash
mvn clean package -DskipTests
# Output: target/flink-counter-job-1.0.0.jar
```

### Dependencies (pom.xml)
```xml
<dependencies>
    <!-- Flink Core -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-streaming-java</artifactId>
        <version>1.18.0</version>
    </dependency>

    <!-- Kafka Connector -->
    <dependency>
        <groupId>org.apache.flink</groupId>
        <artifactId>flink-connector-kafka</artifactId>
        <version>3.0.0-1.18</version>
    </dependency>

    <!-- JSON Processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

## Docker Deployment

### Multi-Stage Build
1. **deps**: Download Maven dependencies (cached)
2. **build**: Compile and package JAR
3. **runtime**: Flink base image with JAR

### Flink Cluster Components

**JobManager:**
- Coordinates job execution
- Manages checkpoints
- Web UI at port 8081

**TaskManager:**
- Executes actual processing
- Manages state locally
- Multiple slots for parallelism

**Job Submitter:**
- Submits JAR to cluster
- Waits for cluster ready
- Exits after submission

## Job Submission

The job submitter container:
1. Waits for JobManager REST API
2. Waits for TaskManager registration
3. Submits JAR with `flink run`
4. Keeps running for logs

```bash
/opt/flink/bin/flink run -m flink-jobmanager:8081 -d /opt/flink/jobs/counter-job.jar
```

**Flags:**
- `-m`: JobManager address
- `-d`: Detached mode (don't wait for completion)

## Monitoring

### Flink Dashboard (localhost:8081)
- Job overview and status
- Task metrics
- Checkpoint history
- Exception logs

### Key Metrics
- **Records In/Out**: Throughput
- **Checkpoint Duration**: State snapshot time
- **Backpressure**: Processing bottlenecks

## Scaling

### Parallelism
```java
env.setParallelism(4);  // 4 parallel instances
```

More parallelism = more throughput, but:
- Need enough Kafka partitions
- Need enough TaskManager slots

### State Backend
For production, use RocksDB:
```java
env.setStateBackend(new EmbeddedRocksDBStateBackend());
```

Benefits:
- Handles large state
- Incremental checkpoints
- Spills to disk

## Common Issues

### Job Fails to Submit
**Symptom**: "Connection refused" to JobManager
**Cause**: JobManager not ready
**Solution**: Wait for health check; use `-m` flag

### State Lost on Restart
**Symptom**: Counter resets to 0
**Cause**: Checkpointing not configured
**Solution**: Enable checkpointing in production

### High Latency
**Symptom**: Slow updates
**Cause**: Network latency to Drools
**Solution**: Consider embedded rules or caching

### Out of Memory
**Symptom**: TaskManager crashes
**Cause**: Too much state or too little heap
**Solution**: Increase memory or use RocksDB backend
