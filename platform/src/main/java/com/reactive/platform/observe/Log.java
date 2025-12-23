package com.reactive.platform.observe;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Unified observability API for the entire project.
 *
 * NO third-party types in this interface.
 * SLF4J and OpenTelemetry details hidden in implementation.
 *
 * Usage:
 *   import static com.reactive.platform.observe.Log.*;
 *
 *   // Always logged (production)
 *   info("Processed order: {}", orderId);
 *   warn("Unusual condition: {}", detail);
 *   error("Failed to process", exception);
 *
 *   // Debug + Span (only when investigation mode active)
 *   traced("process-order", () -> {
 *       return doWork();
 *   });
 *
 *   // Or use @Traced annotation on methods (Spring AOP)
 */
public final class Log {

    private static final LogImpl impl = new LogImpl();

    private Log() {}

    // ========================================================================
    // Always-On Logging (Production)
    // ========================================================================

    public static void info(String message) {
        impl.info(message);
    }

    public static void info(String format, Object arg) {
        impl.info(format, arg);
    }

    public static void info(String format, Object arg1, Object arg2) {
        impl.info(format, arg1, arg2);
    }

    public static void info(String format, Object... args) {
        impl.info(format, args);
    }

    public static void warn(String message) {
        impl.warn(message);
    }

    public static void warn(String format, Object arg) {
        impl.warn(format, arg);
    }

    public static void warn(String format, Object arg1, Object arg2) {
        impl.warn(format, arg1, arg2);
    }

    public static void warn(String format, Object... args) {
        impl.warn(format, args);
    }

    public static void error(String message) {
        impl.error(message);
    }

    public static void error(String message, Throwable t) {
        impl.error(message, t);
    }

    public static void error(String format, Object arg) {
        impl.error(format, arg);
    }

    public static void error(String format, Object... args) {
        impl.error(format, args);
    }

    // ========================================================================
    // Investigation Mode (Debug + Tracing)
    // ========================================================================

    /**
     * Execute work with debug logging and tracing span.
     * Only active when investigation mode is enabled for the request.
     *
     * When active:
     *   [DEBUG] → operation-name
     *   [DEBUG] ← operation-name (45ms)
     *   + Creates span in Jaeger
     *
     * When inactive: just executes work, zero overhead.
     */
    public static <T> T traced(String operation, Supplier<T> work) {
        return impl.traced(operation, work);
    }

    /**
     * Execute void work with debug logging and tracing span.
     */
    public static void traced(String operation, Runnable work) {
        impl.traced(operation, () -> { work.run(); return Optional.empty(); });
    }

    // ========================================================================
    // Investigation Context Control
    // ========================================================================

    /**
     * Enable investigation mode for current thread.
     * Call this when request has X-Debug header.
     */
    public static void enableInvestigation() {
        InvestigationContext.enable();
    }

    /**
     * Disable investigation mode for current thread.
     * Call this after request completes.
     */
    public static void disableInvestigation() {
        InvestigationContext.disable();
    }

    /**
     * Check if investigation mode is active.
     */
    public static boolean isInvestigating() {
        return InvestigationContext.isActive();
    }

    // ========================================================================
    // Trace Context (for distributed tracing propagation)
    // ========================================================================

    /**
     * Get current trace ID (for including in API responses).
     * Returns empty string if no active trace.
     */
    public static String traceId() {
        return impl.traceId();
    }

    /**
     * Check if current span is sampled (recording).
     * Use to skip expensive attribute operations when not sampling.
     */
    public static boolean isSampled() {
        return impl.isSampled();
    }

    /**
     * Get current trace context for propagation to downstream services.
     */
    public static TraceContext context() {
        return impl.currentContext();
    }

    /**
     * Create trace context from W3C headers (from incoming request).
     */
    public static TraceContext contextFrom(String traceparent, String tracestate) {
        return new TraceContext(traceparent, tracestate);
    }

    /**
     * Add attribute to current span.
     */
    public static void attr(String key, String value) {
        impl.attr(key, value);
    }

    /**
     * Add attribute to current span.
     */
    public static void attr(String key, long value) {
        impl.attr(key, value);
    }

    // ========================================================================
    // Types
    // ========================================================================

    /**
     * Trace context for distributed tracing propagation (W3C format).
     */
    public record TraceContext(String traceparent, String tracestate) {
        public static final TraceContext EMPTY = new TraceContext("", "");

        public TraceContext {
            traceparent = traceparent != null ? traceparent : "";
            tracestate = tracestate != null ? tracestate : "";
        }

        public boolean isValid() {
            return !traceparent.isEmpty() && !traceparent.startsWith("00-00000000");
        }
    }

    // ========================================================================
    // Async Span Support
    // ========================================================================

    /**
     * Span type for async operations.
     */
    public enum SpanType {
        INTERNAL,
        PRODUCER,
        CONSUMER
    }

    /**
     * Handle for async span operations.
     * Use this when work is async (callbacks, futures, reactive streams).
     *
     * Usage:
     *   SpanHandle span = Log.asyncSpan("send.message", SpanType.PRODUCER);
     *   span.attr("destination", topic);
     *
     *   sendAsync(message, (result, error) -> {
     *       if (error != null) span.failure(error);
     *       else span.success();
     *   });
     */
    public interface SpanHandle extends AutoCloseable {
        /** Get trace ID for this span. */
        String traceId();

        /** Get propagation headers (W3C format) for downstream context injection. */
        java.util.Map<String, String> headers();

        /** Add string attribute to span. */
        void attr(String key, String value);

        /** Add numeric attribute to span. */
        void attr(String key, long value);

        /** Create a child span under this span. */
        SpanHandle childSpan(String operation, SpanType type);

        /** Mark span as successful and end it. */
        void success();

        /** Mark span as failed with error and end it. */
        void failure(Throwable error);

        /** End span (equivalent to success). */
        @Override
        default void close() { success(); }
    }

    /**
     * Start an async span for producer/consumer/internal operations.
     * Caller is responsible for calling success() or failure() to end the span.
     */
    public static SpanHandle asyncSpan(String operation, SpanType type) {
        return impl.asyncSpan(operation, type);
    }

    /**
     * Start a producer span (convenience for SpanType.PRODUCER).
     */
    public static SpanHandle producerSpan(String operation) {
        return impl.asyncSpan(operation, SpanType.PRODUCER);
    }

    /**
     * Start a consumer span (convenience for SpanType.CONSUMER).
     */
    public static SpanHandle consumerSpan(String operation) {
        return impl.asyncSpan(operation, SpanType.CONSUMER);
    }

    /**
     * Start a consumer span with parent context from W3C trace headers.
     * Use this when receiving messages to continue the distributed trace.
     *
     * @param operation the span name
     * @param traceparent W3C traceparent header value (may be null or empty)
     * @param tracestate W3C tracestate header value (may be null or empty)
     */
    public static SpanHandle consumerSpanWithParent(String operation, String traceparent, String tracestate) {
        return impl.consumerSpanWithParent(operation, traceparent, tracestate);
    }

    // ========================================================================
    // High-Level Traced Operations (hide all ceremony)
    // ========================================================================

    /**
     * Execute work with a traced span, auto-extracting attributes from Traceable.
     *
     * Handles all ceremony:
     * - Creates span of given type
     * - Sets business IDs from Traceable input
     * - Manages MDC for log correlation
     * - Handles success/failure/cleanup
     *
     * Usage:
     *   CounterResult result = Log.tracedProcess("flink.process", event, () -> {
     *       return processEvent(event);
     *   });
     *
     * @param operation span name
     * @param input Traceable input for attribute extraction
     * @param work the work to execute
     * @return the result from work
     */
    public static <T> T tracedProcess(String operation, Traceable input,
                                       java.util.function.Supplier<T> work) {
        return impl.tracedProcess(operation, input, work);
    }

    /**
     * Execute async work with tracing, returning SpanHandle for manual completion.
     *
     * Use this when work is truly async (callbacks, futures) and you need
     * to call success()/failure() later.
     *
     * Usage:
     *   SpanHandle span = Log.asyncTracedProcess("drools.call", input);
     *   httpClient.sendAsync(request).thenAccept(response -> {
     *       span.attr("http.status", response.statusCode());
     *       span.success();
     *   }).exceptionally(e -> {
     *       span.failure(e);
     *       return null;
     *   });
     */
    public static SpanHandle asyncTracedProcess(String operation, Traceable input, SpanType type) {
        return impl.asyncTracedProcess(operation, input, type);
    }

    /**
     * Process a message that carries trace context, continuing the distributed trace.
     *
     * Handles all ceremony:
     * - Creates span with parent context from message's traceparent/tracestate
     * - Sets business IDs from TracedMessage
     * - Manages MDC for log correlation
     * - Returns SpanHandle for async completion
     *
     * Usage:
     *   SpanHandle span = Log.asyncTracedConsume("flink.process", event);
     *   span.attr("counter.action", action);  // Add operation-specific attrs
     *   try {
     *       // ... do work ...
     *       span.success();
     *   } catch (Exception e) {
     *       span.failure(e);
     *   }
     *
     * @param operation span name
     * @param message TracedMessage with business IDs and trace context
     * @param type span type (CONSUMER for processing incoming, PRODUCER for outgoing)
     */
    public static SpanHandle asyncTracedConsume(String operation, TracedMessage message, SpanType type) {
        return impl.asyncTracedConsume(operation, message, type);
    }

    /**
     * Receive/process a Traceable message with auto-extracted business IDs.
     *
     * Handles all ceremony:
     * - Creates span of given type
     * - Sets business IDs from Traceable
     * - Manages MDC for log correlation
     * - Returns SpanHandle for manual completion
     *
     * Use for operations where you already have a Traceable object
     * and want to wrap work in a traced span.
     *
     * Usage:
     *   SpanHandle span = Log.tracedReceive("deserialize", event, SpanType.CONSUMER);
     *   span.attr("message.size", bytes.length);  // Add operation-specific attrs
     *   // ... do work ...
     *   span.success();
     *
     * @param operation span name
     * @param message Traceable with business IDs
     * @param type span type
     */
    public static SpanHandle tracedReceive(String operation, Traceable message, SpanType type) {
        return impl.tracedReceive(operation, message, type);
    }

    // ========================================================================
    // MDC Management (for log correlation)
    // ========================================================================

    /**
     * Set MDC context for log correlation from a Traceable message.
     * Sets requestId, customerId (if present), and traceId.
     *
     * @param traceId the trace ID from the current span
     * @param message Traceable to extract business IDs from
     */
    public static void setMdc(String traceId, Traceable message) {
        impl.setMdc(traceId, message);
    }

    /**
     * Clear all MDC context.
     * Call this in a finally block after processing is complete.
     */
    public static void clearMdc() {
        impl.clearMdc();
    }
}
