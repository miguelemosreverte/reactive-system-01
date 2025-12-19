package com.reactive.flink;

import com.reactive.flink.async.AsyncDroolsEnricher;
import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.PreDroolsResult;
import com.reactive.flink.processor.CounterProcessor;
import com.reactive.flink.serialization.CounterEventDeserializer;
import com.reactive.flink.serialization.CounterResultSerializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class CounterJob {

    private static final String KAFKA_BROKERS = System.getenv().getOrDefault("KAFKA_BROKERS", "kafka:29092");
    private static final String DROOLS_URL = System.getenv().getOrDefault("DROOLS_URL", "http://drools:8080");
    private static final String INPUT_TOPIC = System.getenv().getOrDefault("INPUT_TOPIC", "counter-events");
    private static final String OUTPUT_TOPIC = System.getenv().getOrDefault("OUTPUT_TOPIC", "counter-results");

    // Buffer timeout: How long to wait before flushing incomplete buffers
    // Works with Flink's Buffer Debloating feature for adaptive behavior:
    // - Low throughput (single clicks): Buffer debloating reduces buffer size,
    //   and this timeout ensures quick flush → minimal latency
    // - High throughput (benchmarks): Buffer debloating increases buffer size
    //   for efficiency, batching naturally fills buffers → optimal throughput
    // The SLA bound is controlled by taskmanager.network.memory.buffer-debloat.target
    private static final long BUFFER_TIMEOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("FLINK_BUFFER_TIMEOUT_MS", "1"));

    // Kafka fetch wait: how long consumer waits for data before returning
    // Lower = faster for single events, higher = more efficient batching
    private static final String KAFKA_FETCH_MAX_WAIT_MS =
            System.getenv().getOrDefault("KAFKA_FETCH_MAX_WAIT_MS", "10");

    // Parallelism configuration for high throughput
    private static final int PARALLELISM = Integer.parseInt(
            System.getenv().getOrDefault("FLINK_PARALLELISM", "8"));

    // Async I/O configuration for concurrent Drools calls
    // This is the KEY to high throughput - allows many concurrent HTTP calls
    // Reduced from 10000 to avoid overwhelming resources in Docker environment
    private static final int ASYNC_CAPACITY = Integer.parseInt(
            System.getenv().getOrDefault("ASYNC_CAPACITY", "1000"));

    // Timeout for async Drools calls (ms)
    private static final long ASYNC_TIMEOUT_MS = Long.parseLong(
            System.getenv().getOrDefault("ASYNC_TIMEOUT_MS", "5000"));

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Set parallelism for high throughput (match Kafka partitions)
        env.setParallelism(PARALLELISM);

        // Set buffer timeout - works with Buffer Debloating for adaptive behavior
        // Buffer Debloating automatically adjusts buffer SIZES based on throughput
        // This timeout controls how long to wait before flushing INCOMPLETE buffers
        // Together: single events flush immediately, high throughput batches efficiently
        env.setBufferTimeout(BUFFER_TIMEOUT_MS);

        // Kafka consumer properties for low-latency polling
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("fetch.max.wait.ms", KAFKA_FETCH_MAX_WAIT_MS);  // Don't wait long for batches
        kafkaProps.setProperty("fetch.min.bytes", "1");  // Return as soon as any data is available

        // Configure Kafka source with low-latency settings
        KafkaSource<CounterEvent> source = KafkaSource.<CounterEvent>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-counter-group")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new CounterEventDeserializer())
                .setProperties(kafkaProps)
                .build();

        // Kafka producer properties for low-latency delivery
        Properties producerProps = new Properties();
        producerProps.setProperty("linger.ms", "0");  // Send immediately, don't wait for batching
        producerProps.setProperty("acks", "1");  // Faster acks (leader only)

        // Configure Kafka sink with low-latency settings
        KafkaSink<CounterResult> sink = KafkaSink.<CounterResult>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setKafkaProducerConfig(producerProps)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(OUTPUT_TOPIC)
                        .setValueSerializationSchema(new CounterResultSerializer())
                        .build())
                .build();

        // Build the streaming pipeline
        DataStream<CounterEvent> events = env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "Kafka Counter Events"
        );

        // Step 1: Process counter state updates (fast, no I/O)
        DataStream<PreDroolsResult> preDroolsResults = events
                .keyBy(CounterEvent::getSessionId)
                .process(new CounterProcessor(DROOLS_URL));

        // Step 2: Async Drools enrichment with UNORDERED processing for maximum throughput
        // UNORDERED allows results to be emitted as soon as they complete,
        // without waiting for earlier requests - critical for high throughput
        // Capacity = max concurrent async operations (pending HTTP calls)
        DataStream<CounterResult> results = AsyncDataStream.unorderedWait(
                preDroolsResults,
                new AsyncDroolsEnricher(DROOLS_URL),
                ASYNC_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
                ASYNC_CAPACITY
        );

        // Send results to Kafka
        results.sinkTo(sink);

        // Execute the job
        env.execute("Counter Processing Job (Async Drools)");
    }
}
