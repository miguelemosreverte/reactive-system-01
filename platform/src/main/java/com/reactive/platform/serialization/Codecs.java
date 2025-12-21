package com.reactive.platform.serialization;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Codec registry - provides codecs based on configuration.
 *
 * Supports switching between Avro (production) and JSON (development).
 *
 * Usage:
 *   Codecs codecs = Codecs.create(Format.AVRO);
 *   Codec<CounterEvent> eventCodec = codecs.get(CounterEvent.class);
 */
public final class Codecs {

    /**
     * Serialization format.
     */
    public enum Format {
        JSON,
        AVRO
    }

    private final Format format;
    private final Map<Class<?>, Codec<?>> cache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<Codec<?>>> avroFactories = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<Codec<?>>> jsonFactories = new ConcurrentHashMap<>();

    private Codecs(Format format) {
        this.format = format;
    }

    /**
     * Create a Codecs instance with the specified format.
     */
    public static Codecs create(Format format) {
        return new Codecs(format);
    }

    /**
     * Create a Codecs instance based on environment variable.
     * SERIALIZATION_FORMAT=avro|json (default: json in dev, avro in prod)
     */
    public static Codecs fromEnvironment() {
        String env = System.getenv("SERIALIZATION_FORMAT");
        if (env != null) {
            return create(env.equalsIgnoreCase("avro") ? Format.AVRO : Format.JSON);
        }

        // Default: JSON in development, AVRO in production
        String nodeEnv = System.getenv("NODE_ENV");
        if ("production".equalsIgnoreCase(nodeEnv)) {
            return create(Format.AVRO);
        }
        return create(Format.JSON);
    }

    /**
     * Get the current format.
     */
    public Format format() {
        return format;
    }

    /**
     * Register an Avro codec factory for a class.
     */
    @SuppressWarnings("unchecked")
    public <A> Codecs registerAvro(Class<A> clazz, Supplier<Codec<A>> factory) {
        avroFactories.put(clazz, (Supplier<Codec<?>>) (Supplier<?>) factory);
        return this;
    }

    /**
     * Register a JSON codec factory for a class.
     */
    @SuppressWarnings("unchecked")
    public <A> Codecs registerJson(Class<A> clazz, Supplier<Codec<A>> factory) {
        jsonFactories.put(clazz, (Supplier<Codec<?>>) (Supplier<?>) factory);
        return this;
    }

    /**
     * Register both Avro and JSON codecs for a class.
     */
    public <A> Codecs register(Class<A> clazz, Supplier<Codec<A>> avroFactory, Supplier<Codec<A>> jsonFactory) {
        return registerAvro(clazz, avroFactory).registerJson(clazz, jsonFactory);
    }

    /**
     * Get codec for a class.
     * Uses registered factory if available, otherwise creates JSON codec.
     */
    @SuppressWarnings("unchecked")
    public <A> Codec<A> get(Class<A> clazz) {
        return (Codec<A>) cache.computeIfAbsent(clazz, c -> createCodec(clazz));
    }

    /**
     * Get JSON codec regardless of configured format.
     */
    public <A> Codec<A> json(Class<A> clazz) {
        Supplier<Codec<?>> factory = jsonFactories.get(clazz);
        if (factory != null) {
            return (Codec<A>) factory.get();
        }
        return JsonCodec.forClass(clazz);
    }

    /**
     * Create a dual codec that encodes as configured format but can decode both.
     * Useful for migration scenarios.
     */
    public <A> Codec<A> dual(Class<A> clazz) {
        Codec<A> primary = get(clazz);
        Codec<A> fallback = format == Format.AVRO ? json(clazz) : get(clazz);
        return primary.orElse(fallback);
    }

    // ========================================================================
    // Internal
    // ========================================================================

    @SuppressWarnings("unchecked")
    private <A> Codec<A> createCodec(Class<A> clazz) {
        Map<Class<?>, Supplier<Codec<?>>> factories =
                format == Format.AVRO ? avroFactories : jsonFactories;

        Supplier<Codec<?>> factory = factories.get(clazz);
        if (factory != null) {
            return (Codec<A>) factory.get();
        }

        // Default to JSON
        return JsonCodec.forClass(clazz);
    }
}
