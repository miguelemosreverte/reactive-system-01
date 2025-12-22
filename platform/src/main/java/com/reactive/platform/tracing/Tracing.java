package com.reactive.platform.tracing;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Tracing API for the entire project.
 *
 * NO third-party types in this interface.
 * All OpenTelemetry details are hidden in the implementation.
 *
 * Usage:
 *   import static com.reactive.platform.tracing.Tracing.*;
 *
 *   traced("process-order", () -> {
 *       attr("orderId", orderId);
 *       return processOrder();
 *   });
 *
 *   // With parent context (distributed tracing)
 *   traced("process", parentContext, () -> doWork());
 */
public final class Tracing {

    private static TracingImpl impl = new TracingImpl();

    private Tracing() {}

    /** Configure the tracing implementation (called once at startup). */
    public static void configure(String serviceName) {
        impl = new TracingImpl(serviceName);
    }

    // ========================================================================
    // Traced Execution
    // ========================================================================

    /** Execute operation within a traced span. */
    public static <T> T traced(String operation, Supplier<T> work) {
        return impl.traced(operation, null, work);
    }

    /** Execute operation within a traced span with parent context. */
    public static <T> T traced(String operation, TraceContext parent, Supplier<T> work) {
        return impl.traced(operation, parent, work);
    }

    /** Execute void operation within a traced span. */
    public static void traced(String operation, Runnable work) {
        impl.traced(operation, null, () -> { work.run(); return null; });
    }

    // ========================================================================
    // Attributes (add to current span)
    // ========================================================================

    public static void attr(String key, String value) {
        impl.attr(key, value);
    }

    public static void attr(String key, long value) {
        impl.attr(key, value);
    }

    public static void attrs(Map<String, String> attributes) {
        attributes.forEach(Tracing::attr);
    }

    // ========================================================================
    // Context Propagation
    // ========================================================================

    /** Get current trace context (for passing to downstream services). */
    public static TraceContext context() {
        return impl.currentContext();
    }

    /** Extract trace context from W3C headers. */
    public static TraceContext contextFrom(String traceparent, String tracestate) {
        return new TraceContext(traceparent, tracestate);
    }

    /** Current trace ID (for logging/response). */
    public static String traceId() {
        return impl.traceId();
    }

    // ========================================================================
    // Types
    // ========================================================================

    /** Trace context for distributed tracing propagation. */
    public record TraceContext(String traceparent, String tracestate) {
        public static final TraceContext EMPTY = new TraceContext("", "");

        /** Canonical constructor ensures no nulls. */
        public TraceContext {
            traceparent = traceparent != null ? traceparent : "";
            tracestate = tracestate != null ? tracestate : "";
        }

        public boolean isValid() {
            return !traceparent.isEmpty() && !traceparent.startsWith("00-00000000");
        }
    }
}
