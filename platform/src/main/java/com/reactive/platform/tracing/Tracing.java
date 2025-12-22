package com.reactive.platform.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Functional tracing utilities.
 *
 * Provides a clean API for instrumenting code with OpenTelemetry spans.
 *
 * Usage:
 *   Tracing tracing = Tracing.create("my-service");
 *   String result = tracing.span("operation", span -> {
 *       span.setAttribute("key", "value");
 *       return doWork();
 *   });
 */
public final class Tracing {

    private static final Logger log = LoggerFactory.getLogger(Tracing.class);

    private final Tracer tracer;
    private final String serviceName;

    private Tracing(Tracer tracer, String serviceName) {
        this.tracer = tracer;
        this.serviceName = serviceName;
    }

    /**
     * Create a Tracing instance for a service.
     */
    public static Tracing create(String serviceName) {
        OpenTelemetry otel = GlobalOpenTelemetry.get();
        Tracer tracer = otel.getTracer(serviceName);
        return new Tracing(tracer, serviceName);
    }

    /**
     * Create a Tracing instance with explicit OpenTelemetry.
     */
    public static Tracing create(String serviceName, OpenTelemetry otel) {
        Tracer tracer = otel.getTracer(serviceName);
        return new Tracing(tracer, serviceName);
    }

    /**
     * Get the underlying tracer.
     */
    public Tracer tracer() {
        return tracer;
    }

    /**
     * Get the service name.
     */
    public String serviceName() {
        return serviceName;
    }

    // ========================================================================
    // Functional span operations
    // ========================================================================

    /**
     * Execute a function within a span.
     */
    public <T> T span(String name, Function<Span, T> operation) {
        return span(name, SpanKind.INTERNAL, operation);
    }

    /**
     * Execute a function within a span with specified kind.
     */
    public <T> T span(String name, SpanKind kind, Function<Span, T> operation) {
        return span(name, kind, Context.current(), operation);
    }

    /**
     * Execute a function within a span with specified kind and parent context.
     * Use this when you need to propagate trace context from external sources (e.g., Kafka headers).
     */
    public <T> T span(String name, SpanKind kind, Context parentContext, Function<Span, T> operation) {
        Span span = tracer.spanBuilder(name)
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
     * Execute a supplier within a span.
     */
    public <T> T span(String name, Supplier<T> operation) {
        return span(name, span -> operation.get());
    }

    /**
     * Execute a runnable within a span.
     */
    public void span(String name, Runnable operation) {
        span(name, span -> {
            operation.run();
            return null;
        });
    }

    /**
     * Execute a callable within a span, handling checked exceptions.
     */
    public <T> T spanChecked(String name, Callable<T> operation) throws Exception {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = operation.call();
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
     * Create a span builder for custom configuration.
     */
    public SpanBuilder spanBuilder(String name) {
        return tracer.spanBuilder(name);
    }

    // ========================================================================
    // Context utilities
    // ========================================================================

    /**
     * Get current span.
     */
    public Span currentSpan() {
        return Span.current();
    }

    /**
     * Get current trace ID.
     */
    public String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    /**
     * Get current span ID.
     */
    public String currentSpanId() {
        return Span.current().getSpanContext().getSpanId();
    }

    /**
     * Add event to current span.
     */
    public void addEvent(String name) {
        Span.current().addEvent(name);
    }

    /**
     * Add attribute to current span.
     */
    public void setAttribute(String key, String value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Add attribute to current span.
     */
    public void setAttribute(String key, long value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Add attribute to current span.
     */
    public void setAttribute(String key, boolean value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Record exception on current span.
     */
    public void recordException(Throwable t) {
        Span.current().recordException(t);
    }

    // ========================================================================
    // Timing utilities
    // ========================================================================

    /**
     * Time an operation and record duration as span attribute.
     */
    public <T> T timed(String name, Supplier<T> operation) {
        return span(name, span -> {
            long start = System.nanoTime();
            try {
                return operation.get();
            } finally {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                span.setAttribute("duration_ms", durationMs);
            }
        });
    }

    /**
     * Measure and return duration in milliseconds.
     */
    public static long measureMs(Runnable operation) {
        long start = System.nanoTime();
        operation.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
