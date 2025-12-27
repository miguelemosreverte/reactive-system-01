package com.reactive.platform.http.server.interceptors;

import com.reactive.platform.http.server.HttpServerSpec.*;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Configurable Logging Interceptor.
 *
 * Log levels:
 * - NONE: No logging (interceptor passes through)
 * - ERRORS_ONLY: Only log requests that result in errors (status >= 400 or exception)
 * - ALL: Log all requests with timing
 *
 * Usage:
 * <pre>
 * server.intercept(LoggingInterceptor.all())           // Log everything
 * server.intercept(LoggingInterceptor.errorsOnly())    // Only errors
 * server.intercept(LoggingInterceptor.none())          // Disabled
 * server.intercept(LoggingInterceptor.custom(log -> System.out.println(log)))
 * </pre>
 */
public class LoggingInterceptor implements Interceptor {

    public enum Level {
        NONE,        // Skip logging entirely
        ERRORS_ONLY, // Only log errors (status >= 400 or exceptions)
        ALL          // Log all requests
    }

    private final Level level;
    private final Consumer<LogEntry> logger;

    private LoggingInterceptor(Level level, Consumer<LogEntry> logger) {
        this.level = level;
        this.logger = logger;
    }

    /**
     * Create interceptor that logs nothing (passthrough).
     */
    public static LoggingInterceptor none() {
        return new LoggingInterceptor(Level.NONE, entry -> {});
    }

    /**
     * Create interceptor that only logs errors.
     */
    public static LoggingInterceptor errorsOnly() {
        return new LoggingInterceptor(Level.ERRORS_ONLY, LoggingInterceptor::defaultLog);
    }

    /**
     * Create interceptor that logs all requests.
     */
    public static LoggingInterceptor all() {
        return new LoggingInterceptor(Level.ALL, LoggingInterceptor::defaultLog);
    }

    /**
     * Create interceptor with custom logger.
     */
    public static LoggingInterceptor custom(Level level, Consumer<LogEntry> logger) {
        return new LoggingInterceptor(level, logger);
    }

    /**
     * Create interceptor that logs all with custom logger.
     */
    public static LoggingInterceptor custom(Consumer<LogEntry> logger) {
        return new LoggingInterceptor(Level.ALL, logger);
    }

    @Override
    public CompletableFuture<Response> intercept(Request request, Handler next) {
        if (level == Level.NONE) {
            return next.handle(request);
        }

        long startNanos = System.nanoTime();
        Instant startTime = Instant.now();

        return next.handle(request)
            .whenComplete((response, error) -> {
                long durationNanos = System.nanoTime() - startNanos;
                int status = response != null ? response.status() : 500;
                boolean isError = error != null || status >= 400;

                if (level == Level.ALL || (level == Level.ERRORS_ONLY && isError)) {
                    LogEntry entry = new LogEntry(
                        startTime,
                        request.method().name(),
                        request.path(),
                        status,
                        durationNanos,
                        error,
                        request.header("X-Trace-Id"),
                        request.header("User-Agent"),
                        request.header("X-Forwarded-For")
                    );
                    logger.accept(entry);
                }
            });
    }

    private static void defaultLog(LogEntry entry) {
        String level = entry.isError() ? "ERROR" : "INFO";
        String traceInfo = entry.traceId != null ? " [trace:" + entry.traceId + "]" : "";
        String errorInfo = entry.error != null ? " error=" + entry.error.getMessage() : "";

        System.out.printf("[%s] %s %s %s -> %d (%.2fms)%s%s%n",
            entry.timestamp,
            level,
            entry.method,
            entry.path,
            entry.status,
            entry.durationMs(),
            traceInfo,
            errorInfo
        );
    }

    /**
     * Log entry passed to custom loggers.
     */
    public record LogEntry(
        Instant timestamp,
        String method,
        String path,
        int status,
        long durationNanos,
        Throwable error,
        String traceId,
        String userAgent,
        String forwardedFor
    ) {
        public double durationMs() {
            return durationNanos / 1_000_000.0;
        }

        public boolean isError() {
            return error != null || status >= 400;
        }

        public boolean isSuccess() {
            return !isError();
        }

        public boolean is2xx() {
            return status >= 200 && status < 300;
        }

        public boolean is4xx() {
            return status >= 400 && status < 500;
        }

        public boolean is5xx() {
            return status >= 500;
        }
    }
}
