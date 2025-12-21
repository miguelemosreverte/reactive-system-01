package com.reactive.platform.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Higher-order functions for endpoint decoration.
 *
 * Reduces boilerplate by providing composable decorators for:
 * - Tracing (with OpenTelemetry spans)
 * - Debug logging (activated via headers)
 * - Timing measurement
 * - Error handling
 *
 * Usage:
 *   import static com.reactive.platform.web.EndpointDecorators.*;
 *
 *   return traced("my.operation", tracer,
 *       debugLogged(debugMode, () -> {
 *           return doWork();
 *       }));
 *
 * Or fluent style:
 *   return endpoint("my.operation")
 *       .tracer(tracer)
 *       .debug(debugMode)
 *       .attributes(Map.of("key", "value"))
 *       .execute(() -> doWork());
 */
public final class EndpointDecorators {

    private static final Logger log = LoggerFactory.getLogger(EndpointDecorators.class);

    private EndpointDecorators() {}

    // ========================================================================
    // Static decorators (composable)
    // ========================================================================

    /**
     * Wrap an operation with a traced span.
     */
    public static <T> T traced(String name, Tracer tracer, Supplier<T> operation) {
        return traced(name, tracer, SpanKind.INTERNAL, Map.of(), operation);
    }

    /**
     * Wrap an operation with a traced span and attributes.
     */
    public static <T> T traced(
            String name,
            Tracer tracer,
            SpanKind kind,
            Map<String, Object> attributes,
            Supplier<T> operation
    ) {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .startSpan();

        // Add attributes
        attributes.forEach((k, v) -> {
            if (v instanceof String s) span.setAttribute(k, s);
            else if (v instanceof Long l) span.setAttribute(k, l);
            else if (v instanceof Integer i) span.setAttribute(k, (long) i);
            else if (v instanceof Boolean b) span.setAttribute(k, b);
            else if (v instanceof Double d) span.setAttribute(k, d);
            else span.setAttribute(k, String.valueOf(v));
        });

        try (Scope scope = span.makeCurrent()) {
            T result = operation.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Wrap an operation with debug logging (only logs if debugMode is true).
     */
    public static <T> T debugLogged(boolean debugMode, Supplier<T> operation) {
        if (!debugMode) {
            return operation.get();
        }

        long start = System.nanoTime();
        log.debug("Starting operation");

        try {
            T result = operation.get();
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("Operation completed in {}ms", durationMs);
            return result;
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("Operation failed after {}ms: {}", durationMs, e.getMessage());
            throw e;
        }
    }

    /**
     * Wrap an operation with MDC context.
     */
    public static <T> T withMdc(Map<String, String> context, Supplier<T> operation) {
        try {
            context.forEach(MDC::put);
            return operation.get();
        } finally {
            context.keySet().forEach(MDC::remove);
        }
    }

    /**
     * Wrap an operation with timing measurement (returns result and duration).
     */
    public static <T> TimedResult<T> timed(Supplier<T> operation) {
        long start = System.nanoTime();
        T result = operation.get();
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        return new TimedResult<>(result, durationMs);
    }

    // ========================================================================
    // Fluent builder
    // ========================================================================

    /**
     * Create an endpoint builder for fluent configuration.
     */
    public static EndpointBuilder endpoint(String name) {
        return new EndpointBuilder(name);
    }

    public static class EndpointBuilder {
        private final String name;
        private Tracer tracer;
        private SpanKind spanKind = SpanKind.INTERNAL;
        private Map<String, Object> attributes = Map.of();
        private Map<String, String> mdcContext = Map.of();
        private boolean debugMode = false;
        private boolean timed = false;

        EndpointBuilder(String name) {
            this.name = name;
        }

        public EndpointBuilder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public EndpointBuilder spanKind(SpanKind kind) {
            this.spanKind = kind;
            return this;
        }

        public EndpointBuilder attributes(Map<String, Object> attrs) {
            this.attributes = attrs;
            return this;
        }

        public EndpointBuilder mdc(Map<String, String> context) {
            this.mdcContext = context;
            return this;
        }

        public EndpointBuilder debug(boolean enabled) {
            this.debugMode = enabled;
            return this;
        }

        public EndpointBuilder timed(boolean enabled) {
            this.timed = enabled;
            return this;
        }

        /**
         * Execute the operation with all configured decorators.
         */
        public <T> T execute(Supplier<T> operation) {
            Supplier<T> decorated = operation;

            // Layer decorators from innermost to outermost
            if (debugMode) {
                final Supplier<T> inner = decorated;
                decorated = () -> debugLogged(true, inner);
            }

            if (!mdcContext.isEmpty()) {
                final Supplier<T> inner = decorated;
                decorated = () -> withMdc(mdcContext, inner);
            }

            if (tracer != null) {
                final Supplier<T> inner = decorated;
                decorated = () -> traced(name, tracer, spanKind, attributes, inner);
            }

            return decorated.get();
        }

        /**
         * Execute and return with timing information.
         */
        public <T> TimedResult<T> executeWithTiming(Supplier<T> operation) {
            return EndpointDecorators.timed(() -> execute(operation));
        }
    }

    // ========================================================================
    // Result types
    // ========================================================================

    public record TimedResult<T>(T result, long durationMs) {}
}
