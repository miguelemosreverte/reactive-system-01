package com.reactive.platform.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * JSON Codec factory - creates type-safe codecs for JSON serialization.
 *
 * Usage:
 *   Codec<CounterEvent> codec = JsonCodec.forClass(CounterEvent.class);
 *   Result<byte[]> bytes = codec.encode(event);
 *   Result<CounterEvent> decoded = codec.decode(bytes.getOrThrow());
 *
 * With custom settings:
 *   Codec<Event> codec = JsonCodec.forClass(Event.class, c -> c.lenient().prettyPrint());
 */
public final class JsonCodec {

    private static final ObjectMapper DEFAULT_MAPPER = createDefaultMapper();

    private JsonCodec() {} // Utility class

    /**
     * Create a codec for a specific class using the default settings.
     */
    public static <A> Codec<A> forClass(Class<A> clazz) {
        return createCodec(clazz, DEFAULT_MAPPER);
    }

    /**
     * Create a codec for a specific class with custom configuration.
     * Uses a configuration builder to avoid exposing Jackson types.
     */
    public static <A> Codec<A> forClass(Class<A> clazz, Consumer<JsonConfig> configure) {
        JsonConfig config = new JsonConfig();
        configure.accept(config);
        return createCodec(clazz, config.build());
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
     * Configuration builder for JSON codecs.
     * Hides Jackson's ObjectMapper from the public API.
     */
    public static final class JsonConfig {
        private boolean failOnUnknownProperties = false;
        private boolean writeDatesAsTimestamps = true;
        private boolean prettyPrint = false;
        private boolean lenient = true;

        /** Fail when encountering unknown JSON properties. */
        public JsonConfig strict() {
            this.failOnUnknownProperties = true;
            this.lenient = false;
            return this;
        }

        /** Ignore unknown JSON properties (default). */
        public JsonConfig lenient() {
            this.failOnUnknownProperties = false;
            this.lenient = true;
            return this;
        }

        /** Pretty-print JSON output. */
        public JsonConfig prettyPrint() {
            this.prettyPrint = true;
            return this;
        }

        /** Write dates as ISO-8601 strings instead of timestamps. */
        public JsonConfig datesAsStrings() {
            this.writeDatesAsTimestamps = false;
            return this;
        }

        private ObjectMapper build() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties);
            mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, writeDatesAsTimestamps);
            if (prettyPrint) {
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
            }
            return mapper;
        }
    }

    // ========================================================================
    // Internal encoding/decoding
    // ========================================================================

    private static <A> Codec<A> createCodec(Class<A> clazz, ObjectMapper mapper) {
        return Codec.of(
                value -> encode(value, mapper),
                bytes -> decode(bytes, clazz, mapper),
                "application/json",
                "json:" + clazz.getSimpleName()
        );
    }

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
