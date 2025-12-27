package com.reactive.platform.http.server.interceptors;

import com.reactive.platform.http.server.HttpServerSpec.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Conditional Tracing Interceptor.
 *
 * Only traces requests when:
 * - X-Trace-Id header is present (uses that ID)
 * - X-Request-Trace: true header is present (generates new ID)
 * - alwaysTrace is set to true (traces everything)
 *
 * Usage:
 * <pre>
 * server.intercept(TracingInterceptor.conditional())       // Header-based
 * server.intercept(TracingInterceptor.always())            // All requests
 * server.intercept(TracingInterceptor.withCallback(span -> log(span)))
 * </pre>
 */
public class TracingInterceptor implements Interceptor {

    private final boolean alwaysTrace;
    private final Consumer<TraceContext> onStart;
    private final Consumer<TraceContext> onEnd;

    private TracingInterceptor(boolean alwaysTrace,
                                Consumer<TraceContext> onStart,
                                Consumer<TraceContext> onEnd) {
        this.alwaysTrace = alwaysTrace;
        this.onStart = onStart;
        this.onEnd = onEnd;
    }

    /**
     * Create interceptor that only traces when headers request it.
     */
    public static TracingInterceptor conditional() {
        return new TracingInterceptor(false, ctx -> {}, ctx -> {});
    }

    /**
     * Create interceptor that traces all requests.
     */
    public static TracingInterceptor always() {
        return new TracingInterceptor(true, ctx -> {}, ctx -> {});
    }

    /**
     * Create with custom callbacks for span lifecycle.
     */
    public static TracingInterceptor withCallbacks(
            Consumer<TraceContext> onStart,
            Consumer<TraceContext> onEnd) {
        return new TracingInterceptor(false, onStart, onEnd);
    }

    @Override
    public CompletableFuture<Response> intercept(Request request, Handler next) {
        // Check if we should trace this request
        String traceId = request.header("X-Trace-Id");
        boolean explicitTrace = "true".equalsIgnoreCase(request.header("X-Request-Trace"));

        boolean shouldTrace = alwaysTrace || traceId != null || explicitTrace;

        if (!shouldTrace) {
            return next.handle(request);
        }

        // Generate trace ID if not provided
        if (traceId == null) {
            traceId = generateTraceId();
        }

        // Create trace context
        TraceContext ctx = new TraceContext(
            traceId,
            generateSpanId(),
            request.method().name(),
            request.path(),
            System.nanoTime()
        );

        // Notify start
        onStart.accept(ctx);

        // Add trace context to request attributes
        Request tracedRequest = request
            .withAttribute("traceId", ctx.traceId())
            .withAttribute("spanId", ctx.spanId())
            .withAttribute("traceContext", ctx);

        // Execute and capture result
        return next.handle(tracedRequest)
            .whenComplete((response, error) -> {
                TraceContext completed = new TraceContext(
                    ctx.traceId(),
                    ctx.spanId(),
                    ctx.method(),
                    ctx.path(),
                    ctx.startNanos(),
                    System.nanoTime(),
                    response != null ? response.status() : 500,
                    error
                );
                onEnd.accept(completed);
            });
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String generateSpanId() {
        return Long.toHexString(System.nanoTime());
    }

    /**
     * Trace context passed to callbacks.
     */
    public record TraceContext(
        String traceId,
        String spanId,
        String method,
        String path,
        long startNanos,
        long endNanos,
        int statusCode,
        Throwable error
    ) {
        public TraceContext(String traceId, String spanId, String method, String path, long startNanos) {
            this(traceId, spanId, method, path, startNanos, 0, 0, null);
        }

        public long durationNanos() {
            return endNanos - startNanos;
        }

        public double durationMs() {
            return durationNanos() / 1_000_000.0;
        }

        public boolean isError() {
            return error != null || statusCode >= 400;
        }
    }
}
