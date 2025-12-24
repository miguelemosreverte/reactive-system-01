package com.reactive.flink.serialization;

import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.EventTiming;
import com.reactive.platform.kafka.TracedKafka;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

import static com.reactive.platform.Opt.or;

/**
 * Kafka deserializer with Avro decoding and trace context extraction.
 *
 * BOUNDARY: This is where third-party data enters our system.
 * Uses Avro for high-performance deserialization (~5-10x faster than JSON).
 * Uses TracedKafka.consume to hide all tracing ceremony.
 */
public class TracingKafkaDeserializer implements KafkaRecordDeserializationSchema<CounterEvent> {
    private static final long serialVersionUID = 6L;
    private static final Logger LOG = LoggerFactory.getLogger(TracingKafkaDeserializer.class);

    // Avro reader for the generated schema class
    private transient SpecificDatumReader<com.reactive.platform.schema.CounterEvent> avroReader;

    @Override
    public void open(org.apache.flink.api.common.serialization.DeserializationSchema.InitializationContext context) {
        avroReader = new SpecificDatumReader<>(com.reactive.platform.schema.CounterEvent.class);
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<CounterEvent> out) throws IOException {
        if (avroReader == null) {
            avroReader = new SpecificDatumReader<>(com.reactive.platform.schema.CounterEvent.class);
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
                    () -> {
                        try {
                            // Decode Avro binary to generated class
                            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(value, null);
                            com.reactive.platform.schema.CounterEvent avroEvent = avroReader.read(null, decoder);

                            // Convert to Flink domain model
                            return fromAvro(avroEvent, now, traceparent, tracestate);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );

            if (event != null) {
                out.collect(event);
            }

        } catch (Exception e) {
            LOG.error("Failed to deserialize Kafka record: partition={}, offset={}",
                    record.partition(), record.offset(), e);
        }
    }

    /**
     * Convert Avro-generated CounterEvent to Flink domain CounterEvent.
     */
    private CounterEvent fromAvro(com.reactive.platform.schema.CounterEvent avro,
                                   long deserializedAt, String traceparent, String tracestate) {
        EventTiming timing = Optional.ofNullable(avro.getTiming())
                .map(t -> new EventTiming(
                        t.getGatewayReceivedAt(),
                        or(t.getGatewayPublishedAt(), 0L),
                        0L, 0L, 0L, 0L))
                .orElse(EventTiming.empty());

        return new CounterEvent(
                str(avro.getRequestId()),
                str(avro.getCustomerId()),
                str(avro.getEventId()),
                str(avro.getSessionId()),
                str(avro.getAction()),
                avro.getValue(),
                avro.getTimestamp(),
                timing,
                deserializedAt,
                or(traceparent, str(avro.getTraceparent())),
                or(tracestate, str(avro.getTracestate()))
        );
    }

    /** Convert CharSequence to String safely. */
    private String str(CharSequence cs) {
        return or(cs, "").toString();
    }

    @Override
    public TypeInformation<CounterEvent> getProducedType() {
        return TypeInformation.of(CounterEvent.class);
    }
}
