package com.reactive.platform.serialization;
import com.reactive.platform.base.Result;

import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayOutputStream;

/**
 * Avro Codec factory - creates type-safe codecs for Avro binary serialization.
 *
 * ~10x faster than JSON for serialization/deserialization.
 *
 * Usage:
 *   Codec<CounterEvent> codec = AvroCodec.forClass(CounterEvent.class);
 *   Result<byte[]> bytes = codec.encode(event);
 *   Result<CounterEvent> decoded = codec.decode(bytes.getOrThrow());
 */
public final class AvroCodec {

    private AvroCodec() {} // Utility class

    /**
     * Create a codec for an Avro-generated SpecificRecord class.
     * Uses binary encoding for maximum performance.
     */
    public static <A extends SpecificRecord> Codec<A> forClass(Class<A> clazz) {
        // Create reusable writer and reader (thread-safe for encoding/decoding)
        SpecificDatumWriter<A> writer = new SpecificDatumWriter<>(clazz);
        SpecificDatumReader<A> reader = new SpecificDatumReader<>(clazz);

        return Codec.of(
                value -> encode(value, writer),
                bytes -> decode(bytes, reader),
                "application/avro-binary",
                "avro:" + clazz.getSimpleName()
        );
    }

    /**
     * Create a codec with a custom encoder factory for even better performance.
     * Uses a thread-local ByteArrayOutputStream to reduce allocations.
     */
    public static <A extends SpecificRecord> Codec<A> forClassOptimized(Class<A> clazz) {
        SpecificDatumWriter<A> writer = new SpecificDatumWriter<>(clazz);
        SpecificDatumReader<A> reader = new SpecificDatumReader<>(clazz);

        // Thread-local buffer for reduced allocations
        ThreadLocal<ByteArrayOutputStream> bufferPool = ThreadLocal.withInitial(
                () -> new ByteArrayOutputStream(512));

        return Codec.of(
                value -> encodeOptimized(value, writer, bufferPool),
                bytes -> decode(bytes, reader),
                "application/avro-binary",
                "avro-optimized:" + clazz.getSimpleName()
        );
    }

    // ========================================================================
    // Internal encoding/decoding
    // ========================================================================

    private static <A extends SpecificRecord> Result<byte[]> encode(
            A value, SpecificDatumWriter<A> writer) {
        return Result.of(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(value, encoder);
            encoder.flush();
            return out.toByteArray();
        });
    }

    private static <A extends SpecificRecord> Result<byte[]> encodeOptimized(
            A value, SpecificDatumWriter<A> writer,
            ThreadLocal<ByteArrayOutputStream> bufferPool) {
        return Result.of(() -> {
            ByteArrayOutputStream out = bufferPool.get();
            out.reset();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(value, encoder);
            encoder.flush();
            return out.toByteArray();
        });
    }

    private static <A extends SpecificRecord> Result<A> decode(
            byte[] bytes, SpecificDatumReader<A> reader) {
        return Result.of(() -> {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            return reader.read(null, decoder);
        });
    }
}
