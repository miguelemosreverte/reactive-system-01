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
    // Async Span Support (for Kafka, async HTTP, etc.)
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
     * Use this when work is async (e.g., Kafka callbacks, async HTTP).
     *
     * Usage:
     *   SpanHandle span = Log.asyncSpan("kafka.publish", SpanType.PRODUCER);
     *   span.attr("messaging.destination", topic);
     *
     *   // Inject headers into Kafka record
     *   span.headers().forEach((k, v) -> record.headers().add(k, v.getBytes()));
     *
     *   producer.send(record, (metadata, error) -> {
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
}
