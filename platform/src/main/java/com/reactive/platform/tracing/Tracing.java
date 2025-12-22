package com.reactive.platform.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.function.Function;

/**
 * Pure functional tracing utilities.
 *
 * All methods are static - no instance required.
 * Uses bracket pattern for safe span lifecycle management.
 *
 * Usage:
 *   import static com.reactive.platform.tracing.Tracing.*;
 *
 *   String result = span("my-service", "operation", span -> {
 *       span.setAttribute("key", "value");
 *       return doWork();
 *   });
 */
public final class Tracing {

    private Tracing() {} // Prevent instantiation

    /**
     * Execute operation within a span (bracket pattern).
     * Automatically handles span lifecycle and error recording.
     */
    public static <T> T span(String serviceName, String spanName, Function<Span, T> operation) {
        return span(serviceName, spanName, SpanKind.INTERNAL, Context.current(), operation);
    }

    /**
     * Execute operation within a span with specified kind.
     */
    public static <T> T span(String serviceName, String spanName, SpanKind kind, Function<Span, T> operation) {
        return span(serviceName, spanName, kind, Context.current(), operation);
    }

    /**
     * Execute operation within a span with parent context (for distributed tracing).
     */
    public static <T> T span(String serviceName, String spanName, SpanKind kind, Context parentContext, Function<Span, T> operation) {
        Tracer tracer = GlobalOpenTelemetry.get().getTracer(serviceName);
        Span span = tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .setSpanKind(kind)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = operation.apply(span);
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
     * Execute a void operation within a span.
     */
    public static void span(String serviceName, String spanName, Runnable operation) {
        span(serviceName, spanName, span -> {
            operation.run();
            return null;
        });
    }

    /**
     * Get current trace ID.
     */
    public static String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    /**
     * Get current span ID.
     */
    public static String currentSpanId() {
        return Span.current().getSpanContext().getSpanId();
    }

    /**
     * Get current span.
     */
    public static Span currentSpan() {
        return Span.current();
    }

    /**
     * Measure execution time in milliseconds.
     */
    public static long measureMs(Runnable operation) {
        long start = System.nanoTime();
        operation.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
