package com.reactive.platform.serialization;

import java.util.Optional;
import java.util.function.Function;

/**
 * Functional Codec interface - Strategy pattern with type parameters.
 *
 * Similar to Scala's type class pattern:
 * trait Codec[A] {
 *   def encode(a: A): Either[Throwable, Array[Byte]]
 *   def decode(bytes: Array[Byte]): Either[Throwable, A]
 * }
 *
 * @param <A> The type to serialize/deserialize
 */
public interface Codec<A> {

    /**
     * Encode value to bytes.
     * Returns Optional.empty() on failure.
     */
    Result<byte[]> encode(A value);

    /**
     * Decode bytes to value.
     * Returns Optional.empty() on failure.
     */
    Result<A> decode(byte[] bytes);

    /**
     * Content type (e.g., "application/avro", "application/json")
     */
    String contentType();

    /**
     * Codec name for logging/metrics
     */
    String name();

    // ========================================================================
    // Functional combinators
    // ========================================================================

    /**
     * Map the encoded type to another type.
     * Codec[A].map(A => B, B => A) => Codec[B]
     */
    default <B> Codec<B> xmap(Function<A, B> f, Function<B, A> g) {
        Codec<A> self = this;
        return new Codec<>() {
            @Override
            public Result<byte[]> encode(B value) {
                return self.encode(g.apply(value));
            }

            @Override
            public Result<B> decode(byte[] bytes) {
                return self.decode(bytes).map(f);
            }

            @Override
            public String contentType() {
                return self.contentType();
            }

            @Override
            public String name() {
                return self.name() + ".xmap";
            }
        };
    }

    /**
     * Combine two codecs: try first, fallback to second on decode failure.
     */
    default Codec<A> orElse(Codec<A> fallback) {
        Codec<A> self = this;
        return new Codec<>() {
            @Override
            public Result<byte[]> encode(A value) {
                return self.encode(value);
            }

            @Override
            public Result<A> decode(byte[] bytes) {
                Result<A> result = self.decode(bytes);
                return result.isSuccess() ? result : fallback.decode(bytes);
            }

            @Override
            public String contentType() {
                return self.contentType();
            }

            @Override
            public String name() {
                return self.name() + ".orElse(" + fallback.name() + ")";
            }
        };
    }

    // ========================================================================
    // Factory methods
    // ========================================================================

    /**
     * Create a codec from encode/decode functions.
     */
    static <A> Codec<A> of(
            Function<A, Result<byte[]>> encode,
            Function<byte[], Result<A>> decode,
            String contentType,
            String name
    ) {
        return new Codec<>() {
            @Override
            public Result<byte[]> encode(A value) {
                return encode.apply(value);
            }

            @Override
            public Result<A> decode(byte[] bytes) {
                return decode.apply(bytes);
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}
