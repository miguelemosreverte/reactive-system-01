package com.reactive.platform.kafka;

import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import com.reactive.platform.observe.Log.SpanType;
import com.reactive.platform.observe.Traceable;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

import static com.reactive.platform.Opt.or;

/**
 * Kafka-specific tracing helpers.
 *
 * Uses the general Log API internally but provides Kafka-aware convenience methods.
 * Hides all Kafka tracing ceremony: parent context, messaging attributes, business IDs, MDC.
 */
public final class TracedKafka {

    private TracedKafka() {}

    /**
     * Kafka message context for traced operations.
     */
    public record Context(
            String topic,
            int partition,
            long offset,
            String traceparent,
            String tracestate
    ) {
        public static Context of(String topic, int partition, long offset,
                                 String traceparent, String tracestate) {
            return new Context(topic, partition, offset, or(traceparent, ""), or(tracestate, ""));
        }
    }

    /**
     * Consume a Kafka message with full tracing.
     *
     * Handles all ceremony:
     * - Creates consumer span with parent context from headers
     * - Sets Kafka attributes (topic, partition, offset)
     * - Sets business IDs from Traceable result
     * - Manages MDC for log correlation
     *
     * Usage:
     *   CounterEvent event = TracedKafka.consume(
     *       Context.of(topic, partition, offset, traceparent, tracestate),
     *       () -> objectMapper.readValue(bytes, CounterEvent.class)
     *   );
     *
     * @param ctx Kafka context (topic, partition, offset, trace headers)
     * @param work the deserialization work that produces a Traceable result
     * @return the result from work
     */
    public static <T extends Traceable> T consume(Context ctx, Supplier<T> work) {
        SpanHandle span = Log.consumerSpanWithParent("kafka.consume", ctx.traceparent(), ctx.tracestate());

        setKafkaAttributes(span, ctx);

        try {
            T result = work.get();

            if (result != null) {
                setBusinessIds(span, result);
                Log.setMdc(span.traceId(), result);
            }

            span.success();
            return result;

        } catch (Exception e) {
            span.failure(e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            Log.clearMdc();
        }
    }

    /**
     * Receive a Kafka message with full tracing (when message is already deserialized).
     *
     * Handles all ceremony:
     * - Creates consumer span with parent context
     * - Sets Kafka attributes (topic, partition, offset)
     * - Sets business IDs from Traceable message
     * - Sets MDC for log correlation
     * - Returns SpanHandle for manual completion
     *
     * Usage:
     *   SpanHandle span = TracedKafka.receive(ctx, result);
     *   try {
     *       // ... process result ...
     *       span.success();
     *   } finally {
     *       Log.clearMdc();
     *   }
     *
     * @param ctx Kafka context (topic, partition, offset, trace headers)
     * @param message Traceable message to extract business IDs from
     */
    public static SpanHandle receive(Context ctx, Traceable message) {
        SpanHandle span = Log.consumerSpanWithParent("kafka.receive", ctx.traceparent(), ctx.tracestate());

        setKafkaAttributes(span, ctx);
        setBusinessIds(span, message);
        Log.setMdc(span.traceId(), message);

        return span;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void setKafkaAttributes(SpanHandle span, Context ctx) {
        span.attr("messaging.system", "kafka");
        span.attr("messaging.destination", ctx.topic());
        span.attr("messaging.operation", "receive");
        span.attr("messaging.kafka.partition", ctx.partition());
        span.attr("messaging.kafka.offset", ctx.offset());
    }

    private static void setBusinessIds(SpanHandle span, Traceable message) {
        span.attr("requestId", message.requestId());
        span.attr("customerId", message.customerId());
        span.attr("eventId", message.eventId());
        span.attr("session.id", message.sessionId());
    }

    /**
     * Extract a header value from Kafka headers.
     * Returns empty string if header is not present.
     *
     * @param headers Kafka headers
     * @param key header key
     * @return header value or empty string
     */
    public static String header(Headers headers, String key) {
        return Optional.ofNullable(headers.lastHeader(key))
                .map(Header::value)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse("");
    }
}
