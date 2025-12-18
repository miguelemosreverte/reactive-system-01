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

public class CounterJob {

    private static final String KAFKA_BROKERS = System.getenv().getOrDefault("KAFKA_BROKERS", "kafka:29092");
    private static final String DROOLS_URL = System.getenv().getOrDefault("DROOLS_URL", "http://drools:8080");
    private static final String INPUT_TOPIC = System.getenv().getOrDefault("INPUT_TOPIC", "counter-events");
    private static final String OUTPUT_TOPIC = System.getenv().getOrDefault("OUTPUT_TOPIC", "counter-results");

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Configure Kafka source
        KafkaSource<CounterEvent> source = KafkaSource.<CounterEvent>builder()
                .setBootstrapServers(KAFKA_BROKERS)
                .setTopics(INPUT_TOPIC)
                .setGroupId("flink-counter-group")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new CounterEventDeserializer())
                .build();

        // Configure Kafka sink
        KafkaSink<CounterResult> sink = KafkaSink.<CounterResult>builder()
                .setBootstrapServers(KAFKA_BROKERS)
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
