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
