package com.reactive.platform.tracing;

import com.reactive.platform.tracing.Tracing.TraceContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OpenTelemetry implementation of tracing.
 *
 * ALL OpenTelemetry types are confined to this class.
 * The rest of the codebase uses only the clean Tracing API.
 */
final class TracingImpl {

    private final Tracer tracer;

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : "";
        }
    };

    TracingImpl() {
        this.tracer = GlobalOpenTelemetry.get().getTracer("platform");
    }

    TracingImpl(String serviceName) {
        this.tracer = GlobalOpenTelemetry.get().getTracer(serviceName);
    }

    /**
     * Execute work within a traced span.
     */
    <T> T traced(String operation, TraceContext parent, Supplier<T> work) {
        Context parentContext = extractContext(parent);

        Span span = tracer.spanBuilder(operation)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            return work.get();
        } catch (Exception e) {
            span.recordException(e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    /**
     * Add string attribute to current span.
     */
    void attr(String key, String value) {
        Span.current().setAttribute(key, value.isEmpty() ? "" : value);
    }

    /**
     * Add long attribute to current span.
     */
    void attr(String key, long value) {
        Span.current().setAttribute(key, value);
    }

    /**
     * Get current trace context for propagation.
     */
    TraceContext currentContext() {
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), headers, Map::put);

        String traceparent = headers.getOrDefault("traceparent", "");
        String tracestate = headers.getOrDefault("tracestate", "");

        return new TraceContext(traceparent, tracestate);
    }

    /**
     * Get current trace ID.
     */
    String traceId() {
        String id = Span.current().getSpanContext().getTraceId();
        return "00000000000000000000000000000000".equals(id) ? "" : id;
    }

    /**
     * Extract OTel Context from our TraceContext.
     */
    private Context extractContext(TraceContext parent) {
        if (parent == null || !parent.isValid()) {
            return Context.current();
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", parent.traceparent());
        if (!parent.tracestate().isEmpty()) {
            headers.put("tracestate", parent.tracestate());
        }

        return GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), headers, MAP_GETTER);
    }
}
