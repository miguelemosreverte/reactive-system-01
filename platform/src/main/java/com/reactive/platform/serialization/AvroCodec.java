package com.reactive.platform.serialization;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.*;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayOutputStream;
import java.util.function.Function;

/**
 * Avro Codec factory - creates type-safe codecs for Avro records.
 *
 * Usage:
 *   Codec<CounterEvent> codec = AvroCodec.forSpecific(CounterEvent.class);
 *   Result<byte[]> bytes = codec.encode(event);
 *   Result<CounterEvent> decoded = codec.decode(bytes.getOrThrow());
 */
public final class AvroCodec {

    private AvroCodec() {} // Utility class

    /**
     * Create a codec for Avro SpecificRecord (generated classes).
     */
    public static <A extends SpecificRecord> Codec<A> forSpecific(Class<A> clazz) {
        return Codec.of(
                value -> encodeSpecific(value),
                bytes -> decodeSpecific(bytes, clazz),
                "application/avro",
                "avro:" + clazz.getSimpleName()
        );
    }

    /**
     * Create a codec for GenericRecord with explicit schema.
     */
    public static Codec<GenericRecord> forGeneric(Schema schema) {
        return Codec.of(
                value -> encodeGeneric(value, schema),
                bytes -> decodeGeneric(bytes, schema),
                "application/avro",
                "avro:generic"
        );
    }

    /**
     * Create a codec that transforms a domain type to/from Avro.
     *
     * @param avroCodec The underlying Avro codec
     * @param toAvro    Function to convert domain type to Avro
     * @param fromAvro  Function to convert Avro to domain type
     */
    public static <A, R extends SpecificRecord> Codec<A> mapping(
            Codec<R> avroCodec,
            Function<A, R> toAvro,
            Function<R, A> fromAvro
    ) {
        return avroCodec.xmap(fromAvro, toAvro);
    }

    // ========================================================================
    // Internal encoding/decoding
    // ========================================================================

    private static <A extends SpecificRecord> Result<byte[]> encodeSpecific(A value) {
        return Result.of(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DatumWriter<A> writer = new SpecificDatumWriter<>(value.getSchema());
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(value, encoder);
            encoder.flush();
            return out.toByteArray();
        });
    }

    private static <A extends SpecificRecord> Result<A> decodeSpecific(byte[] bytes, Class<A> clazz) {
        return Result.of(() -> {
            DatumReader<A> reader = new SpecificDatumReader<>(clazz);
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            return reader.read(null, decoder);
        });
    }

    private static Result<byte[]> encodeGeneric(GenericRecord value, Schema schema) {
        return Result.of(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DatumWriter<GenericRecord> writer = new GenericDatumWriter<>(schema);
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(value, encoder);
            encoder.flush();
            return out.toByteArray();
        });
    }

    private static Result<GenericRecord> decodeGeneric(byte[] bytes, Schema schema) {
        return Result.of(() -> {
            DatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, null);
            return reader.read(null, decoder);
        });
    }
}
