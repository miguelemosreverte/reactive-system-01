package com.reactive.platform.publish;

import com.reactive.platform.base.Result;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Abstract publisher interface - NO third-party types exposed.
 *
 * This interface hides the underlying messaging system (Kafka, RabbitMQ, etc.)
 * from application code. All methods use only platform types.
 *
 * Usage:
 * <pre>
 *   Publisher&lt;MyEvent&gt; publisher = Publisher.kafka(config -&gt; config
 *       .bootstrapServers("localhost:9092")
 *       .topic("events")
 *       .codec(MyEvent.codec()));
 *
 *   // Async publish with result
 *   publisher.publish(event).thenAccept(result -&gt;
 *       result.forEach(traceId -&gt; log.info("Published: {}", traceId)));
 *
 *   // Fire-and-forget for high throughput
 *   publisher.publishFireAndForget(event);
 *
 *   // Batch publish
 *   publisher.publishBatch(events);
 * </pre>
 *
 * @param <A> The message type
 */
public interface Publisher<A> extends AutoCloseable {

    // ========================================================================
    // Core Publishing Methods
    // ========================================================================

    /**
     * Publish a message asynchronously with full tracing.
     *
     * @param message The message to publish
     * @return Future containing Result with trace ID on success
     */
    CompletableFuture<Result<String>> publish(A message);

    /**
     * Fire-and-forget publish for maximum throughput.
     * Returns trace ID immediately without waiting for ack.
     *
     * @param message The message to publish
     * @return Trace ID (may be empty if tracing not sampled)
     */
    String publishFireAndForget(A message);

    /**
     * Publish a batch of messages as a single operation.
     * More efficient than individual publishes.
     *
     * @param messages List of messages to publish
     * @return Number of messages published
     */
    int publishBatch(List<A> messages);

    /**
     * Publish pre-serialized data (raw bytes).
     * Use when data is already serialized.
     *
     * @param messages List of raw byte arrays
     * @return Number of messages published
     */
    int publishBatchRaw(List<byte[]> messages);

    // ========================================================================
    // Metrics
    // ========================================================================

    /**
     * Get total published message count.
     */
    long publishedCount();

    /**
     * Get error count.
     */
    long errorCount();

    /**
     * Get current metrics snapshot.
     */
    default Metrics metrics() {
        return new Metrics(publishedCount(), errorCount());
    }

    // ========================================================================
    // Types
    // ========================================================================

    /**
     * Publisher metrics snapshot.
     */
    record Metrics(long published, long errors) {
        public double errorRate() {
            long total = published + errors;
            return total > 0 ? (double) errors / total : 0.0;
        }
    }

    /**
     * Publisher configuration (no third-party types).
     */
    record Config(
        String destination,
        Optional<String> clientId,
        int maxInFlight,
        boolean fireAndForget
    ) {
        public static Config of(String destination) {
            return new Config(destination, Optional.empty(), 5, false);
        }

        public Config withClientId(String id) {
            return new Config(destination, Optional.of(id), maxInFlight, fireAndForget);
        }

        public Config withMaxInFlight(int max) {
            return new Config(destination, clientId, max, fireAndForget);
        }

        public Config asFireAndForget() {
            return new Config(destination, clientId, 100, true);
        }
    }

    // ========================================================================
    // Factory Methods (Implementation-specific)
    // ========================================================================

    /**
     * Create a Kafka publisher.
     * This is the only place where Kafka is referenced.
     */
    static <A> Publisher<A> kafka(java.util.function.Consumer<KafkaPublisherBuilder<A>> configure) {
        KafkaPublisherBuilder<A> builder = new KafkaPublisherBuilder<>();
        configure.accept(builder);
        return builder.build();
    }

    /**
     * Create a no-op publisher (for testing).
     */
    static <A> Publisher<A> noop() {
        return new NoopPublisher<>();
    }
}
