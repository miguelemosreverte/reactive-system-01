package com.reactive.flink;

import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.processor.CounterProcessor;
import com.reactive.flink.serialization.CounterEventDeserializer;
import com.reactive.flink.serialization.CounterResultSerializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Properties;

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

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

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

        // Process events through Drools
        DataStream<CounterResult> results = events
                .keyBy(CounterEvent::getSessionId)
                .process(new CounterProcessor(DROOLS_URL));

        // Send results to Kafka
        results.sinkTo(sink);

        // Execute the job
        env.execute("Counter Processing Job");
    }
}
