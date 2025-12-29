package com.reactive.platform.serialization;
import com.reactive.platform.base.Result;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;

/**
 * JSON Codec factory - creates type-safe codecs for JSON serialization.
 *
 * Usage:
 *   Codec<CounterEvent> codec = JsonCodec.forClass(CounterEvent.class);
 *   Result<byte[]> bytes = codec.encode(event);
 *   Result<CounterEvent> decoded = codec.decode(bytes.getOrThrow());
 */
public final class JsonCodec {

    private static final ObjectMapper DEFAULT_MAPPER = createDefaultMapper();

    private JsonCodec() {} // Utility class

    /**
     * Create a codec for a specific class using the default ObjectMapper.
     */
    public static <A> Codec<A> forClass(Class<A> clazz) {
        return forClass(clazz, DEFAULT_MAPPER);
    }

    /**
     * Create a codec for a specific class with a custom ObjectMapper.
     */
    public static <A> Codec<A> forClass(Class<A> clazz, ObjectMapper mapper) {
        return Codec.of(
                value -> encode(value, mapper),
                bytes -> decode(bytes, clazz, mapper),
                "application/json",
                "json:" + clazz.getSimpleName()
        );
    }

    /**
     * Create a String codec (pass-through for JSON strings).
     */
    public static Codec<String> forString() {
        return Codec.of(
                value -> Result.success(value.getBytes(StandardCharsets.UTF_8)),
                bytes -> Result.success(new String(bytes, StandardCharsets.UTF_8)),
                "application/json",
                "json:String"
        );
    }

    /**
     * Get the shared ObjectMapper instance.
     */
    public static ObjectMapper mapper() {
        return DEFAULT_MAPPER;
    }

    // ========================================================================
    // Internal encoding/decoding
    // ========================================================================

    private static <A> Result<byte[]> encode(A value, ObjectMapper mapper) {
        return Result.of(() -> mapper.writeValueAsBytes(value));
    }

    private static <A> Result<A> decode(byte[] bytes, Class<A> clazz, ObjectMapper mapper) {
        return Result.of(() -> mapper.readValue(bytes, clazz));
    }

    private static ObjectMapper createDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        return mapper;
    }
}
