package com.reactive.counter.serialization;

import com.reactive.counter.domain.CounterEvent;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.Result;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;

import java.io.ByteArrayOutputStream;

/**
 * High-performance Avro codec for CounterEvent.
 *
 * Converts between domain CounterEvent (Java record) and Avro binary format.
 * ~5-10x faster than JSON serialization.
 */
public final class AvroCounterEventCodec {

    private AvroCounterEventCodec() {}

    // Avro-generated class
    private static final Class<com.reactive.platform.schema.CounterEvent> AVRO_CLASS =
            com.reactive.platform.schema.CounterEvent.class;

    // Thread-safe writer/reader
    private static final SpecificDatumWriter<com.reactive.platform.schema.CounterEvent> WRITER =
            new SpecificDatumWriter<>(AVRO_CLASS);
    private static final SpecificDatumReader<com.reactive.platform.schema.CounterEvent> READER =
            new SpecificDatumReader<>(AVRO_CLASS);

    // Thread-local buffer pool for reduced allocations
    private static final ThreadLocal<ByteArrayOutputStream> BUFFER_POOL =
            ThreadLocal.withInitial(() -> new ByteArrayOutputStream(256));

    // Thread-local encoder cache
    private static final ThreadLocal<BinaryEncoder> ENCODER_CACHE = new ThreadLocal<>();

    /**
     * Create a high-performance Avro codec for domain CounterEvent.
     */
    public static Codec<CounterEvent> create() {
        return Codec.of(
                AvroCounterEventCodec::encode,
                AvroCounterEventCodec::decode,
                "application/avro-binary",
                "avro:CounterEvent"
        );
    }

    private static Result<byte[]> encode(CounterEvent event) {
        return Result.of(() -> {
            // Convert domain event to Avro event
            com.reactive.platform.schema.CounterEvent avroEvent = toAvro(event);

            // Encode to bytes using pooled buffer
            ByteArrayOutputStream out = BUFFER_POOL.get();
            out.reset();

            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, ENCODER_CACHE.get());
            ENCODER_CACHE.set(encoder);

            WRITER.write(avroEvent, encoder);
            encoder.flush();

            return out.toByteArray();
        });
    }

    private static Result<CounterEvent> decode(byte[] bytes) {
        return Result.of(() -> {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            com.reactive.platform.schema.CounterEvent avroEvent = READER.read(null, decoder);
            return fromAvro(avroEvent);
        });
    }

    /**
     * Convert domain CounterEvent to Avro CounterEvent.
     */
    private static com.reactive.platform.schema.CounterEvent toAvro(CounterEvent event) {
        com.reactive.platform.schema.CounterEvent avro = new com.reactive.platform.schema.CounterEvent();
        avro.setRequestId(event.requestId());
        avro.setCustomerId(event.customerId());
        avro.setEventId(event.eventId());
        avro.setSessionId(event.sessionId());
        avro.setAction(event.action());
        avro.setValue(event.value());
        avro.setTimestamp(event.timestamp());

        // Set timing if present
        if (event.timing() != null) {
            com.reactive.platform.schema.EventTiming timing = new com.reactive.platform.schema.EventTiming();
            timing.setGatewayReceivedAt(event.timing().gatewayReceivedAt());
            timing.setGatewayPublishedAt(event.timing().gatewayPublishedAt());
            avro.setTiming(timing);
        }

        return avro;
    }

    /**
     * Convert Avro CounterEvent to domain CounterEvent.
     */
    private static CounterEvent fromAvro(com.reactive.platform.schema.CounterEvent avro) {
        CounterEvent.Timing timing = null;
        if (avro.getTiming() != null) {
            Long publishedAt = avro.getTiming().getGatewayPublishedAt();
            timing = new CounterEvent.Timing(
                    avro.getTiming().getGatewayReceivedAt(),
                    publishedAt != null ? publishedAt : 0L
            );
        }

        return new CounterEvent(
                avro.getRequestId(),
                avro.getCustomerId(),
                avro.getEventId(),
                avro.getSessionId(),
                avro.getAction(),
                avro.getValue(),
                avro.getTimestamp(),
                timing
        );
    }
}
