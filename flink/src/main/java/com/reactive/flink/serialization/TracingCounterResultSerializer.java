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
 */
public class TracingCounterResultSerializer implements KafkaRecordSerializationSchema<CounterResult> {
    private static final long serialVersionUID = 1L;
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

            if (result.getTraceparent() != null) {
                headers.add(new RecordHeader("traceparent",
                        result.getTraceparent().getBytes(StandardCharsets.UTF_8)));
            }

            if (result.getTracestate() != null) {
                headers.add(new RecordHeader("tracestate",
                        result.getTracestate().getBytes(StandardCharsets.UTF_8)));
            }

            // Use sessionId as key for Kafka partitioning
            byte[] key = result.getSessionId() != null
                    ? result.getSessionId().getBytes(StandardCharsets.UTF_8)
                    : null;

            return new ProducerRecord<>(topic, null, timestamp, key, value, headers);

        } catch (Exception e) {
            LOG.error("Failed to serialize result: {}", result, e);
            return new ProducerRecord<>(topic, new byte[0]);
        }
    }
}
