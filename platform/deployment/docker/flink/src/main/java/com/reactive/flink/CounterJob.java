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
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
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

    // Skip tracing spans (for maximum throughput benchmarks)
    private static final boolean SKIP_TRACING = Boolean.parseBoolean(
            System.getenv().getOrDefault("SKIP_TRACING", "false"));

    // Skip checkpointing (for maximum throughput benchmarks - disables exactly-once semantics)
    private static final boolean SKIP_CHECKPOINTING = Boolean.parseBoolean(
            System.getenv().getOrDefault("SKIP_CHECKPOINTING", "false"));

    // Benchmark mode: pure passthrough with NO state access (maximum theoretical throughput)
    private static final boolean BENCHMARK_MODE = Boolean.parseBoolean(
            System.getenv().getOrDefault("BENCHMARK_MODE", "false"));

    // Checkpoint interval in ms (default 60s for high throughput - based on Flink production best practices)
    // Research shows: longer intervals = higher throughput, shorter = less data loss on failure
    // 60s is recommended for high-throughput applications per Apache Flink documentation
    private static final long CHECKPOINT_INTERVAL_MS = Long.parseLong(
            System.getenv().getOrDefault("CHECKPOINT_INTERVAL_MS", "60000"));

    // Min pause between checkpoints (prevents checkpoint storms under backpressure)
    private static final long CHECKPOINT_MIN_PAUSE_MS = Long.parseLong(
            System.getenv().getOrDefault("CHECKPOINT_MIN_PAUSE_MS", "30000"));

    // Checkpoint timeout (allow more time for large state)
    private static final long CHECKPOINT_TIMEOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("CHECKPOINT_TIMEOUT_MS", "120000"));

    // Enable unaligned checkpoints (better for backpressure scenarios)
    private static final boolean UNALIGNED_CHECKPOINTS = Boolean.parseBoolean(
            System.getenv().getOrDefault("UNALIGNED_CHECKPOINTS", "true"));

    // Use RocksDB state backend for scalable state (required for incremental checkpoints)
    private static final boolean USE_ROCKSDB = Boolean.parseBoolean(
            System.getenv().getOrDefault("USE_ROCKSDB", "true"));

    // ============================================
    // Kafka Producer Tuning (configurable via brochure env vars)
    // ============================================
    private static final String KAFKA_PRODUCER_LINGER_MS =
            System.getenv().getOrDefault("KAFKA_PRODUCER_LINGER_MS", "5");
    private static final String KAFKA_PRODUCER_BATCH_SIZE =
            System.getenv().getOrDefault("KAFKA_PRODUCER_BATCH_SIZE", "131072");  // 128KB
    private static final String KAFKA_PRODUCER_ACKS =
            System.getenv().getOrDefault("KAFKA_PRODUCER_ACKS", "1");
    private static final String KAFKA_PRODUCER_COMPRESSION =
            System.getenv().getOrDefault("KAFKA_PRODUCER_COMPRESSION", "lz4");
    private static final String KAFKA_PRODUCER_BUFFER_MEMORY =
            System.getenv().getOrDefault("KAFKA_PRODUCER_BUFFER_MEMORY", "134217728");  // 128MB
    private static final String KAFKA_PRODUCER_MAX_IN_FLIGHT =
            System.getenv().getOrDefault("KAFKA_PRODUCER_MAX_IN_FLIGHT", "5");

    // ============================================
    // Kafka Consumer Tuning (configurable via brochure env vars)
    // ============================================
    private static final String KAFKA_CONSUMER_FETCH_MIN_BYTES =
            System.getenv().getOrDefault("KAFKA_CONSUMER_FETCH_MIN_BYTES", "16384");  // 16KB
    private static final String KAFKA_CONSUMER_FETCH_MAX_BYTES =
            System.getenv().getOrDefault("KAFKA_CONSUMER_FETCH_MAX_BYTES", "52428800");  // 50MB
    private static final String KAFKA_CONSUMER_MAX_PARTITION_FETCH =
            System.getenv().getOrDefault("KAFKA_CONSUMER_MAX_PARTITION_FETCH", "5242880");  // 5MB
    private static final String KAFKA_CONSUMER_MAX_POLL_RECORDS =
            System.getenv().getOrDefault("KAFKA_CONSUMER_MAX_POLL_RECORDS", "10000");

    public static void main(String[] args) throws Exception {
        LOG.info("Starting Counter Processing Job (CQRS mode)");
        LOG.info("Latency bounds: MIN={}ms, MAX={}ms", LATENCY_MIN_MS, LATENCY_MAX_MS);
        LOG.info("Parallelism: {}, Async capacity: {}", PARALLELISM, ASYNC_CAPACITY);
        LOG.info("Skip Drools: {}, Skip Tracing: {}, Skip Checkpointing: {}, Benchmark Mode: {}",
                SKIP_DROOLS, SKIP_TRACING, SKIP_CHECKPOINTING, BENCHMARK_MODE);

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

        // Configure state backend and checkpointing
        // Production-grade configuration based on Apache Flink best practices:
        // - RocksDB enables incremental checkpoints (only deltas stored)
        // - Unaligned checkpoints handle backpressure without blocking
        // - Longer intervals reduce overhead for high-throughput apps
        if (SKIP_CHECKPOINTING) {
            LOG.info("Checkpointing DISABLED (benchmark mode - at-most-once semantics)");
        } else {
            // Use RocksDB state backend with incremental checkpoints
            // RocksDB is recommended for production: scales beyond memory, supports incremental
            if (USE_ROCKSDB) {
                EmbeddedRocksDBStateBackend rocksDB = new EmbeddedRocksDBStateBackend(true); // enable incremental
                env.setStateBackend(rocksDB);
                LOG.info("RocksDB state backend enabled with INCREMENTAL checkpoints");
            }

            // Enable checkpointing with EXACTLY_ONCE semantics
            env.enableCheckpointing(CHECKPOINT_INTERVAL_MS, CheckpointingMode.EXACTLY_ONCE);

            // Get checkpoint config for fine-grained tuning
            CheckpointConfig checkpointConfig = env.getCheckpointConfig();

            // Min pause prevents checkpoint storms under load
            checkpointConfig.setMinPauseBetweenCheckpoints(CHECKPOINT_MIN_PAUSE_MS);

            // Timeout for checkpoint completion
            checkpointConfig.setCheckpointTimeout(CHECKPOINT_TIMEOUT_MS);

            // Only one checkpoint at a time for simplicity
            checkpointConfig.setMaxConcurrentCheckpoints(1);

            // Enable unaligned checkpoints for better backpressure handling
            // Unaligned checkpoints skip barriers through in-flight data
            if (UNALIGNED_CHECKPOINTS) {
                checkpointConfig.enableUnalignedCheckpoints();
                LOG.info("Unaligned checkpoints ENABLED (better backpressure handling)");
            }

            // Retain externalized checkpoints on cancellation (for recovery)
            checkpointConfig.setExternalizedCheckpointCleanup(
                    CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

            LOG.info("Checkpointing enabled: interval={}ms, minPause={}ms, timeout={}ms, mode=EXACTLY_ONCE",
                    CHECKPOINT_INTERVAL_MS, CHECKPOINT_MIN_PAUSE_MS, CHECKPOINT_TIMEOUT_MS);
        }

        // Kafka consumer properties - configurable via brochure env vars
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("fetch.max.wait.ms", KAFKA_FETCH_MAX_WAIT_MS);
        kafkaProps.setProperty("fetch.min.bytes", KAFKA_CONSUMER_FETCH_MIN_BYTES);
        kafkaProps.setProperty("fetch.max.bytes", KAFKA_CONSUMER_FETCH_MAX_BYTES);
        kafkaProps.setProperty("max.partition.fetch.bytes", KAFKA_CONSUMER_MAX_PARTITION_FETCH);
        kafkaProps.setProperty("max.poll.records", KAFKA_CONSUMER_MAX_POLL_RECORDS);
        kafkaProps.setProperty("max.poll.interval.ms", "300000");
        kafkaProps.setProperty("heartbeat.interval.ms", "3000");
        kafkaProps.setProperty("session.timeout.ms", "30000");
        // Auto commit is handled by Flink checkpoints
        kafkaProps.setProperty("enable.auto.commit", "false");
        LOG.info("Consumer config: fetch.min.bytes={}, fetch.max.bytes={}, max.poll.records={}",
                KAFKA_CONSUMER_FETCH_MIN_BYTES, KAFKA_CONSUMER_FETCH_MAX_BYTES, KAFKA_CONSUMER_MAX_POLL_RECORDS);

        // Configure Kafka source with unbounded streaming and trace context extraction
        // Using committedOffsets with EARLIEST fallback:
        // - Resumes from last committed offset if available (no duplicate processing)
        // - Falls back to earliest for new consumer groups (no missed events)
        KafkaSource<CounterEvent> source = KafkaSource.<CounterEvent>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-counter-group-v4")  // New group ID to reset offsets
                .setStartingOffsets(OffsetsInitializer.committedOffsets(org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST))
                .setDeserializer(new TracingKafkaDeserializer())  // Extracts trace context from headers
                .setProperties(kafkaProps)
                .build();

        // Kafka producer properties - configurable via brochure env vars
        Properties producerProps = new Properties();
        producerProps.setProperty("linger.ms", KAFKA_PRODUCER_LINGER_MS);
        producerProps.setProperty("batch.size", KAFKA_PRODUCER_BATCH_SIZE);
        producerProps.setProperty("acks", KAFKA_PRODUCER_ACKS);
        producerProps.setProperty("compression.type", KAFKA_PRODUCER_COMPRESSION);
        producerProps.setProperty("buffer.memory", KAFKA_PRODUCER_BUFFER_MEMORY);
        producerProps.setProperty("max.in.flight.requests.per.connection", KAFKA_PRODUCER_MAX_IN_FLIGHT);
        LOG.info("Producer config: linger.ms={}, batch.size={}, compression={}, buffer={}",
                KAFKA_PRODUCER_LINGER_MS, KAFKA_PRODUCER_BATCH_SIZE, KAFKA_PRODUCER_COMPRESSION, KAFKA_PRODUCER_BUFFER_MEMORY);

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
                .process(new CounterProcessor(LATENCY_MIN_MS, LATENCY_MAX_MS, SKIP_TRACING, BENCHMARK_MODE));

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
