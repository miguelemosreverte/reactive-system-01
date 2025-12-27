package com.reactive.platform.observe;

import com.reactive.platform.observe.Log.TraceContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Implementation of observability with SLF4J and OpenTelemetry.
 *
 * ALL third-party types (SLF4J, OpenTelemetry) are confined to this class.
 */
final class LogImpl {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ConcurrentMap<Class<?>, Logger> LOGGERS = new ConcurrentHashMap<>();

    private static final String LOG_CLASS = Log.class.getName();
    private static final String IMPL_CLASS = LogImpl.class.getName();

    private final Tracer tracer;

    LogImpl() {
        this.tracer = GlobalOpenTelemetry.get().getTracer("platform");
    }

    // ========================================================================
    // Info (always logged)
    // ========================================================================

    void info(String message) {
        getCallerLogger().info(message);
    }

    void info(String format, Object arg) {
        getCallerLogger().info(format, arg);
    }

    void info(String format, Object arg1, Object arg2) {
        getCallerLogger().info(format, arg1, arg2);
    }

    void info(String format, Object... args) {
        getCallerLogger().info(format, args);
    }

    // ========================================================================
    // Warn (always logged)
    // ========================================================================

    void warn(String message) {
        getCallerLogger().warn(message);
    }

    void warn(String format, Object arg) {
        getCallerLogger().warn(format, arg);
    }

    void warn(String format, Object arg1, Object arg2) {
        getCallerLogger().warn(format, arg1, arg2);
    }

    void warn(String format, Object... args) {
        getCallerLogger().warn(format, args);
    }

    // ========================================================================
    // Error (always logged)
    // ========================================================================

    void error(String message) {
        getCallerLogger().error(message);
    }

    void error(String message, Throwable t) {
        getCallerLogger().error(message, t);
    }

    void error(String format, Object arg) {
        getCallerLogger().error(format, arg);
    }

    void error(String format, Object... args) {
        getCallerLogger().error(format, args);
    }

    // ========================================================================
    // Traced (only when investigation active)
    // ========================================================================

    <T> T traced(String operation, Supplier<T> work) {
        if (!InvestigationContext.isActive()) {
            // Production mode: zero overhead
            return work.get();
        }

        // Investigation mode: debug logs + span
        Logger logger = getCallerLogger();
        logger.debug("→ {}", operation);

        long startTime = System.currentTimeMillis();

        Span span = tracer.spanBuilder(operation)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            T result = work.get();
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("← {} ({}ms)", operation, duration);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("✗ {} ({}ms) - {}", operation, duration, e.getMessage());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    // ========================================================================
    // Trace Context
    // ========================================================================

    String traceId() {
        String id = Span.current().getSpanContext().getTraceId();
        return "00000000000000000000000000000000".equals(id) ? "" : id;
    }

    boolean isSampled() {
        return Span.current().isRecording();
    }

    TraceContext currentContext() {
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(Context.current(), headers, Map::put);

        return new TraceContext(
                headers.getOrDefault("traceparent", ""),
                headers.getOrDefault("tracestate", "")
        );
    }

    void attr(String key, String value) {
        Span.current().setAttribute(key, value);
    }

    void attr(String key, long value) {
        Span.current().setAttribute(key, value);
    }

    // ========================================================================
    // Async Span Support
    // ========================================================================

    Log.SpanHandle asyncSpan(String operation, Log.SpanType type) {
        SpanKind kind = switch (type) {
            case PRODUCER -> SpanKind.PRODUCER;
            case CONSUMER -> SpanKind.CONSUMER;
            case INTERNAL -> SpanKind.INTERNAL;
        };

        Span span = tracer.spanBuilder(operation)
                .setSpanKind(kind)
                .startSpan();

        // Create context with this span for header injection
        Context context = Context.current().with(span);

        // Extract propagation headers
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, headers, Map::put);

        return new SpanHandleImpl(span, context, tracer, headers);
    }

    Log.SpanHandle consumerSpanWithParent(String operation, String traceparent, String tracestate) {
        // Extract parent context from W3C trace headers
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null && !traceparent.isEmpty()) {
            carrier.put("traceparent", traceparent);
        }
        if (tracestate != null && !tracestate.isEmpty()) {
            carrier.put("tracestate", tracestate);
        }

        Context extractedContext = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), carrier, HEADER_GETTER);

        // Create consumer span with extracted parent
        Span span = tracer.spanBuilder(operation)
                .setParent(extractedContext)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        // Create context with this span for header injection
        Context context = extractedContext.with(span);

        // Extract propagation headers for downstream
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, headers, Map::put);

        return new SpanHandleImpl(span, context, tracer, headers);
    }

    private static final TextMapGetter<Map<String, String>> HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }
    };

    /**
     * Implementation of SpanHandle - all OTel details contained here.
     */
    private static final class SpanHandleImpl implements Log.SpanHandle {
        private final Span span;
        private final Context context;
        private final Tracer tracer;
        private final Map<String, String> headers;
        private volatile boolean ended = false;

        SpanHandleImpl(Span span, Context context, Tracer tracer, Map<String, String> headers) {
            this.span = span;
            this.context = context;
            this.tracer = tracer;
            this.headers = Map.copyOf(headers); // Immutable copy
        }

        @Override
        public String traceId() {
            return span.getSpanContext().getTraceId();
        }

        @Override
        public Map<String, String> headers() {
            return headers;
        }

        @Override
        public void attr(String key, String value) {
            span.setAttribute(key, value);
        }

        @Override
        public void attr(String key, long value) {
            span.setAttribute(key, value);
        }

        @Override
        public Log.SpanHandle childSpan(String operation, Log.SpanType type) {
            SpanKind kind = switch (type) {
                case PRODUCER -> SpanKind.PRODUCER;
                case CONSUMER -> SpanKind.CONSUMER;
                case INTERNAL -> SpanKind.INTERNAL;
            };

            // Create child span with this span as parent
            Span childSpan = tracer.spanBuilder(operation)
                    .setParent(context)
                    .setSpanKind(kind)
                    .startSpan();

            Context childContext = context.with(childSpan);

            // Extract propagation headers for the child
            Map<String, String> childHeaders = new HashMap<>();
            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(childContext, childHeaders, Map::put);

            return new SpanHandleImpl(childSpan, childContext, tracer, childHeaders);
        }

        @Override
        public void success() {
            if (!ended) {
                ended = true;
                span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
                span.end();
            }
        }

        @Override
        public void failure(Throwable error) {
            if (!ended) {
                ended = true;
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, error.getMessage());
                span.recordException(error);
                span.end();
            }
        }

        @Override
        public <T> T runInContext(java.util.function.Supplier<T> action) {
            try (io.opentelemetry.context.Scope scope = context.makeCurrent()) {
                return action.get();
            }
        }
    }

    // ========================================================================
    // High-Level Traced Operations
    // ========================================================================

    <T> T tracedProcess(String operation, Traceable input,
                        java.util.function.Supplier<T> work) {
        Span span = tracer.spanBuilder(operation)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        // Business ID attributes from input
        span.setAttribute("requestId", input.requestId());
        span.setAttribute("customerId", input.customerId());
        span.setAttribute("eventId", input.eventId());
        span.setAttribute("session.id", input.sessionId());

        // MDC for log correlation
        if (!input.requestId().isEmpty()) {
            org.slf4j.MDC.put("requestId", input.requestId());
        }
        org.slf4j.MDC.put("traceId", span.getSpanContext().getTraceId());

        try (Scope scope = span.makeCurrent()) {
            T result = work.get();
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            span.end();
            org.slf4j.MDC.clear();
        }
    }

    Log.SpanHandle asyncTracedProcess(String operation, Traceable input, Log.SpanType type) {
        SpanKind kind = switch (type) {
            case PRODUCER -> SpanKind.PRODUCER;
            case CONSUMER -> SpanKind.CONSUMER;
            case INTERNAL -> SpanKind.INTERNAL;
        };

        Span span = tracer.spanBuilder(operation)
                .setSpanKind(kind)
                .startSpan();

        // Business ID attributes from input
        span.setAttribute("requestId", input.requestId());
        span.setAttribute("customerId", input.customerId());
        span.setAttribute("eventId", input.eventId());
        span.setAttribute("session.id", input.sessionId());

        // MDC for log correlation
        if (!input.requestId().isEmpty()) {
            org.slf4j.MDC.put("requestId", input.requestId());
        }
        org.slf4j.MDC.put("traceId", span.getSpanContext().getTraceId());

        Context context = Context.current().with(span);
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, headers, Map::put);

        return new SpanHandleImpl(span, context, tracer, headers);
    }

    Log.SpanHandle asyncTracedConsume(String operation, TracedMessage message, Log.SpanType type) {
        SpanKind kind = switch (type) {
            case PRODUCER -> SpanKind.PRODUCER;
            case CONSUMER -> SpanKind.CONSUMER;
            case INTERNAL -> SpanKind.INTERNAL;
        };

        // Extract parent context from message's trace headers
        Map<String, String> carrier = new HashMap<>();
        String traceparent = message.traceparent();
        String tracestate = message.tracestate();
        if (traceparent != null && !traceparent.isEmpty()) {
            carrier.put("traceparent", traceparent);
        }
        if (tracestate != null && !tracestate.isEmpty()) {
            carrier.put("tracestate", tracestate);
        }

        Context extractedContext = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), carrier, HEADER_GETTER);

        // Create span with extracted parent
        Span span = tracer.spanBuilder(operation)
                .setParent(extractedContext)
                .setSpanKind(kind)
                .startSpan();

        // Business ID attributes from message (auto-extracted)
        span.setAttribute("requestId", message.requestId());
        span.setAttribute("customerId", message.customerId());
        span.setAttribute("eventId", message.eventId());
        span.setAttribute("session.id", message.sessionId());

        // MDC for log correlation
        if (!message.requestId().isEmpty()) {
            org.slf4j.MDC.put("requestId", message.requestId());
        }
        org.slf4j.MDC.put("traceId", span.getSpanContext().getTraceId());

        Context context = extractedContext.with(span);
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, headers, Map::put);

        return new SpanHandleImpl(span, context, tracer, headers);
    }

    Log.SpanHandle tracedReceive(String operation, Traceable message, Log.SpanType type) {
        SpanKind kind = switch (type) {
            case PRODUCER -> SpanKind.PRODUCER;
            case CONSUMER -> SpanKind.CONSUMER;
            case INTERNAL -> SpanKind.INTERNAL;
        };

        Span span = tracer.spanBuilder(operation)
                .setSpanKind(kind)
                .startSpan();

        // Business ID attributes from message (auto-extracted)
        span.setAttribute("requestId", message.requestId());
        span.setAttribute("customerId", message.customerId());
        span.setAttribute("eventId", message.eventId());
        span.setAttribute("session.id", message.sessionId());

        // MDC for log correlation
        if (!message.requestId().isEmpty()) {
            org.slf4j.MDC.put("requestId", message.requestId());
        }
        if (!message.customerId().isEmpty()) {
            org.slf4j.MDC.put("customerId", message.customerId());
        }
        org.slf4j.MDC.put("traceId", span.getSpanContext().getTraceId());

        Context context = Context.current().with(span);
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, headers, Map::put);

        return new SpanHandleImpl(span, context, tracer, headers);
    }

    // ========================================================================
    // MDC Management
    // ========================================================================

    void setMdc(String traceId, Traceable message) {
        if (!message.requestId().isEmpty()) {
            org.slf4j.MDC.put("requestId", message.requestId());
        }
        if (!message.customerId().isEmpty()) {
            org.slf4j.MDC.put("customerId", message.customerId());
        }
        if (traceId != null && !traceId.isEmpty()) {
            org.slf4j.MDC.put("traceId", traceId);
        }
    }

    void clearMdc() {
        org.slf4j.MDC.clear();
    }

    // ========================================================================
    // Helper
    // ========================================================================

    private Logger getCallerLogger() {
        Class<?> callerClass = WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !c.getName().equals(LOG_CLASS) && !c.getName().equals(IMPL_CLASS))
                .findFirst()
                .orElse(LogImpl.class));

        return LOGGERS.computeIfAbsent(callerClass, LoggerFactory::getLogger);
    }
}
