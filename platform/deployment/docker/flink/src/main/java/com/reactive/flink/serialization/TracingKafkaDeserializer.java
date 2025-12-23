package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import com.reactive.platform.kafka.TracedKafka;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Kafka deserializer with trace context extraction.
 *
 * BOUNDARY: This is where third-party data enters our system.
 * Uses TracedKafka.consume to hide all tracing ceremony.
 */
public class TracingKafkaDeserializer implements KafkaRecordDeserializationSchema<CounterEvent> {
    private static final long serialVersionUID = 5L;
    private static final Logger LOG = LoggerFactory.getLogger(TracingKafkaDeserializer.class);

    private transient ObjectMapper objectMapper;

    @Override
    public void open(org.apache.flink.api.common.serialization.DeserializationSchema.InitializationContext context) {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<CounterEvent> out) throws IOException {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        byte[] value = record.value();
        if (value == null) {
            return;
        }

        try {
            String traceparent = TracedKafka.header(record.headers(), "traceparent");
            String tracestate = TracedKafka.header(record.headers(), "tracestate");
            long now = System.currentTimeMillis();

            // All Kafka tracing ceremony hidden in TracedKafka.consume
            CounterEvent event = TracedKafka.consume(
                    TracedKafka.Context.of(record.topic(), record.partition(), record.offset(), traceparent, tracestate),
                    () -> objectMapper.readValue(value, CounterEvent.class)
                            .withDeserializedAt(now)
                            .withTraceContext(traceparent, tracestate)
            );

            if (event != null) {
                out.collect(event);
            }

        } catch (Exception e) {
            LOG.error("Failed to deserialize Kafka record: partition={}, offset={}",
                    record.partition(), record.offset(), e);
        }
    }

    @Override
    public TypeInformation<CounterEvent> getProducedType() {
        return TypeInformation.of(CounterEvent.class);
    }
}
