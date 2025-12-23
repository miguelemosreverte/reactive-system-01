package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterResult;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Kafka serializer for CounterResult that injects W3C trace context headers.
 * This enables distributed trace propagation from Flink back to Gateway.
 *
 * INTERNAL: Receives CounterResult with guaranteed non-empty string fields.
 * Uses record accessors and isEmpty() checks.
 */
public class TracingCounterResultSerializer implements KafkaRecordSerializationSchema<CounterResult> {
    private static final long serialVersionUID = 2L;
    private static final Logger LOG = LoggerFactory.getLogger(TracingCounterResultSerializer.class);

    private final String topic;
    private transient ObjectMapper objectMapper;

    public TracingCounterResultSerializer(String topic) {
        this.topic = topic;
    }

    @Override
    public void open(
            org.apache.flink.api.common.serialization.SerializationSchema.InitializationContext context,
            KafkaSinkContext sinkContext) throws Exception {
        objectMapper = new ObjectMapper();
    }

    @Override
    public ProducerRecord<byte[], byte[]> serialize(
            CounterResult result,
            KafkaSinkContext context,
            Long timestamp) {

        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }

        try {
            byte[] value = objectMapper.writeValueAsBytes(result);

            // Create headers with trace context
            RecordHeaders headers = new RecordHeaders();

            // Record fields are guaranteed non-empty strings - use isEmpty() check
            if (!result.traceparent().isEmpty()) {
                headers.add(new RecordHeader("traceparent",
                        result.traceparent().getBytes(StandardCharsets.UTF_8)));
            }

            if (!result.tracestate().isEmpty()) {
                headers.add(new RecordHeader("tracestate",
                        result.tracestate().getBytes(StandardCharsets.UTF_8)));
            }

            // Use sessionId as key for Kafka partitioning
            byte[] key = !result.sessionId().isEmpty()
                    ? result.sessionId().getBytes(StandardCharsets.UTF_8)
                    : null;

            return new ProducerRecord<>(topic, null, timestamp, key, value, headers);

        } catch (Exception e) {
            LOG.error("Failed to serialize result: {}", result, e);
            return new ProducerRecord<>(topic, new byte[0]);
        }
    }
}
