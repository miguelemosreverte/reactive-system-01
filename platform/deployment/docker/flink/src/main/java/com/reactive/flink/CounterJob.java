package com.reactive.flink;

import com.reactive.flink.async.AsyncDroolsEnricher;
import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.EventTiming;
import com.reactive.flink.model.PreDroolsResult;
import com.reactive.flink.processor.CounterProcessor;
import com.reactive.flink.serialization.RecordSerializer;
import com.reactive.flink.serialization.TracingKafkaDeserializer;
import com.reactive.flink.serialization.TracingCounterResultSerializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * CQRS-based Counter Processing Job.
 *
 * Architecture:
 * - WRITE SIDE: Every event updates state immediately and emits CounterResult
 *   to counter-results topic (fast feedback, alert="PENDING")
 *
 * - READ SIDE: Timer-based snapshot evaluation triggers Drools calls at
 *   bounded intervals. Results go to counter-alerts topic.
 *
 * This separation allows:
 * - Unlimited write throughput (just state updates)
 * - Bounded read throughput (Drools calls capped by latency bounds)
 * - Eventual consistency: Alerts arrive within MAX_LATENCY of state change
 */
public class CounterJob {
    private static final Logger LOG = LoggerFactory.getLogger(CounterJob.class);

    private static final String KAFKA_BROKERS = System.getenv().getOrDefault("KAFKA_BROKERS", "kafka:29092");
    private static final String DROOLS_URL = System.getenv().getOrDefault("DROOLS_URL", "http://drools:8080");
    private static final String INPUT_TOPIC = System.getenv().getOrDefault("INPUT_TOPIC", "counter-events");
    private static final String OUTPUT_TOPIC = System.getenv().getOrDefault("OUTPUT_TOPIC", "counter-results");
    private static final String ALERTS_TOPIC = System.getenv().getOrDefault("ALERTS_TOPIC", "counter-alerts");

    // System-wide latency bounds (CQRS)
    // MIN: Don't evaluate snapshots more frequently than this (aggregate events)
    // MAX: Always evaluate within this bound (guaranteed SLA)
    private static final long LATENCY_MIN_MS = Long.parseLong(
            System.getenv().getOrDefault("LATENCY_MIN_MS", "1"));
    private static final long LATENCY_MAX_MS = Long.parseLong(
            System.getenv().getOrDefault("LATENCY_MAX_MS", "100"));

    // Buffer timeout: How long to wait before flushing incomplete buffers
    private static final long BUFFER_TIMEOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("FLINK_BUFFER_TIMEOUT_MS", "1"));

    // Kafka fetch wait: how long consumer waits for data before returning (lower = faster)
    private static final String KAFKA_FETCH_MAX_WAIT_MS =
            System.getenv().getOrDefault("KAFKA_FETCH_MAX_WAIT_MS", "5");

    // Parallelism configuration
    private static final int PARALLELISM = Integer.parseInt(
            System.getenv().getOrDefault("FLINK_PARALLELISM", "8"));

    // Async I/O configuration for Drools calls
    // Capacity of 250 balances concurrency vs Drools overload (1000 causes regression)
    private static final int ASYNC_CAPACITY = Integer.parseInt(
            System.getenv().getOrDefault("ASYNC_CAPACITY", "250"));

    // Timeout for async Drools calls (ms)
    private static final long ASYNC_TIMEOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("ASYNC_TIMEOUT_MS", "5000"));

    // Skip Drools processing (for Layer 2 benchmarks: Kafka + Flink only)
    private static final boolean SKIP_DROOLS = Boolean.parseBoolean(
            System.getenv().getOrDefault("SKIP_DROOLS", "false"));

    public static void main(String[] args) throws Exception {
        LOG.info("Starting Counter Processing Job (CQRS mode)");
        LOG.info("Latency bounds: MIN={}ms, MAX={}ms", LATENCY_MIN_MS, LATENCY_MAX_MS);
        LOG.info("Parallelism: {}, Async capacity: {}", PARALLELISM, ASYNC_CAPACITY);
        LOG.info("Skip Drools: {} (Layer 2 benchmark mode)", SKIP_DROOLS);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Register Kryo serializers for Java records (Flink's default Kryo can't serialize records)
        env.getConfig().addDefaultKryoSerializer(CounterEvent.class, new RecordSerializer(CounterEvent.class));
        env.getConfig().addDefaultKryoSerializer(CounterResult.class, new RecordSerializer(CounterResult.class));
        env.getConfig().addDefaultKryoSerializer(PreDroolsResult.class, new RecordSerializer(PreDroolsResult.class));
        env.getConfig().addDefaultKryoSerializer(EventTiming.class, new RecordSerializer(EventTiming.class));
        LOG.info("Registered Kryo serializers for Java record types");

        // Set parallelism
        env.setParallelism(PARALLELISM);

        // Set buffer timeout
        env.setBufferTimeout(BUFFER_TIMEOUT_MS);

        // Enable checkpointing for continuous consumption
        // Without checkpointing, Kafka source may stop after initial batch
        env.enableCheckpointing(5000); // Checkpoint every 5 seconds
        LOG.info("Checkpointing enabled: 5000ms interval");

        // Kafka consumer properties for continuous consumption
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("fetch.max.wait.ms", KAFKA_FETCH_MAX_WAIT_MS);
        kafkaProps.setProperty("fetch.min.bytes", "1");
        // Ensure consumer polls continuously - higher for throughput
        kafkaProps.setProperty("max.poll.records", "1000");
        kafkaProps.setProperty("max.poll.interval.ms", "300000");
        kafkaProps.setProperty("heartbeat.interval.ms", "3000");
        kafkaProps.setProperty("session.timeout.ms", "30000");
        // Auto commit is handled by Flink checkpoints
        kafkaProps.setProperty("enable.auto.commit", "false");

        // Configure Kafka source with unbounded streaming and trace context extraction
        KafkaSource<CounterEvent> source = KafkaSource.<CounterEvent>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-counter-group-v3")  // New group ID for trace-aware version
                .setStartingOffsets(OffsetsInitializer.latest())
                .setDeserializer(new TracingKafkaDeserializer())  // Extracts trace context from headers
                .setProperties(kafkaProps)
                .build();

        // Kafka producer properties - balanced for latency + throughput
        Properties producerProps = new Properties();
        producerProps.setProperty("linger.ms", "1");            // 1ms delay for low latency
        producerProps.setProperty("batch.size", "32768");       // 32KB batch size
        producerProps.setProperty("acks", "1");                 // Leader acknowledgment only
        producerProps.setProperty("compression.type", "lz4");   // Fast compression
        producerProps.setProperty("buffer.memory", "67108864"); // 64MB buffer
        LOG.info("Producer config: linger.ms=1, batch.size=32KB, compression=lz4");

        // Sink for immediate results (counter-results topic) - with trace context propagation
        KafkaSink<CounterResult> resultsSink = KafkaSink.<CounterResult>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setKafkaProducerConfig(producerProps)
                .setRecordSerializer(new TracingCounterResultSerializer(OUTPUT_TOPIC))
                .build();

        // Sink for alerts (counter-alerts topic) - with trace context propagation
        KafkaSink<CounterResult> alertsSink = KafkaSink.<CounterResult>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setKafkaProducerConfig(producerProps)
                .setRecordSerializer(new TracingCounterResultSerializer(ALERTS_TOPIC))
                .build();

        // Build the streaming pipeline
        DataStream<CounterEvent> events = env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "Kafka Counter Events"
        );

        // ============================================
        // WRITE SIDE: Immediate state updates + results
        // ============================================
        // CounterProcessor outputs:
        // - Main output: CounterResult with alert="PENDING" (immediate feedback)
        // - Side output: PreDroolsResult for snapshot evaluation (bounded)
        SingleOutputStreamOperator<CounterResult> immediateResults = events
                .keyBy(CounterEvent::sessionId)
                .process(new CounterProcessor(LATENCY_MIN_MS, LATENCY_MAX_MS));

        // Send immediate results to counter-results topic
        immediateResults.sinkTo(resultsSink);

        // ============================================
        // READ SIDE: Bounded snapshot evaluation
        // ============================================
        // Get the side output (snapshots for Drools evaluation)
        DataStream<PreDroolsResult> snapshots = immediateResults
                .getSideOutput(CounterProcessor.SNAPSHOT_OUTPUT);

        // Conditionally process through Drools or bypass (for Layer 2 benchmarks)
        if (SKIP_DROOLS) {
            LOG.info("Drools processing SKIPPED (Layer 2 benchmark mode)");
            // In Layer 2 mode, convert PreDroolsResult directly to CounterResult
            // without calling Drools - simulates processing without rule evaluation
            DataStream<CounterResult> bypassedAlerts = snapshots
                    .map(pre -> new CounterResult(
                            pre.sessionId(),
                            pre.counterValue(),
                            "BYPASS",
                            "Drools bypassed (Layer 2 benchmark)",
                            pre.requestId(),
                            pre.customerId(),
                            pre.eventId(),
                            pre.timing()
                    ));
            bypassedAlerts.sinkTo(alertsSink);
        } else {
            // Async Drools enrichment on bounded snapshot stream
            DataStream<CounterResult> alerts = AsyncDataStream.unorderedWait(
                    snapshots,
                    new AsyncDroolsEnricher(DROOLS_URL),
                    ASYNC_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                    ASYNC_CAPACITY
            );
            // Send alerts to counter-alerts topic
            alerts.sinkTo(alertsSink);
        }

        // Execute the job
        env.execute("Counter Processing Job (CQRS with Bounded Snapshot Evaluation)");
    }
}
