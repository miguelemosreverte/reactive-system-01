package com.reactive.platform.http.server;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * HTTP Server Specification - The standard interface for all HTTP server implementations.
 *
 * Design principles:
 * 1. Declarative route registration
 * 2. Interceptor chain for cross-cutting concerns (tracing, logging, auth)
 * 3. Implementation-agnostic - works with NIO, io_uring, Netty, etc.
 * 4. Simple to implement, powerful to use
 *
 * Example usage:
 * <pre>
 * HttpServerSpec server = SomeHttpServer.create()
 *     .intercept(new TracingInterceptor())
 *     .intercept(new LoggingInterceptor())
 *     .route(GET, "/health", req -> Response.ok("{\"status\":\"UP\"}"))
 *     .route(POST, "/events", req -> {
 *         processEvent(req.body());
 *         return Response.accepted();
 *     });
 *
 * try (ServerHandle handle = server.start(8080)) {
 *     handle.awaitTermination();
 * }
 * </pre>
 */
public interface HttpServerSpec {

    // ========================================================================
    // Route Registration
    // ========================================================================

    /**
     * Register a route handler.
     */
    HttpServerSpec route(Method method, String path, Handler handler);

    /**
     * Convenience method for GET routes.
     */
    default HttpServerSpec get(String path, Handler handler) {
        return route(Method.GET, path, handler);
    }

    /**
     * Convenience method for POST routes.
     */
    default HttpServerSpec post(String path, Handler handler) {
        return route(Method.POST, path, handler);
    }

    /**
     * Convenience method for PUT routes.
     */
    default HttpServerSpec put(String path, Handler handler) {
        return route(Method.PUT, path, handler);
    }

    /**
     * Convenience method for DELETE routes.
     */
    default HttpServerSpec delete(String path, Handler handler) {
        return route(Method.DELETE, path, handler);
    }

    // ========================================================================
    // Interceptors (Middleware)
    // ========================================================================

    /**
     * Add an interceptor to the chain.
     * Interceptors are called in order of registration.
     *
     * Use cases:
     * - Tracing (OpenTelemetry)
     * - Logging
     * - Authentication/Authorization
     * - Rate limiting
     * - Metrics collection
     */
    HttpServerSpec intercept(Interceptor interceptor);

    // ========================================================================
    // Server Lifecycle
    // ========================================================================

    /**
     * Start the server on the specified port.
     * Returns a handle for lifecycle management.
     */
    ServerHandle start(int port);

    /**
     * Start with custom configuration.
     */
    ServerHandle start(int port, ServerConfig config);

    // ========================================================================
    // HTTP Method enum
    // ========================================================================

    enum Method {
        GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
    }

    // ========================================================================
    // Request
    // ========================================================================

    interface Request {
        Method method();
        String path();
        String header(String name);
        java.util.Map<String, String> headers();
        java.util.Map<String, String> queryParams();
        byte[] body();
        String bodyAsString();

        /**
         * Attach contextual data (e.g., trace context, user info).
         */
        Request withAttribute(String key, Object value);
        <T> T attribute(String key);
    }

    // ========================================================================
    // Response
    // ========================================================================

    interface Response {
        int status();
        java.util.Map<String, String> headers();
        byte[] body();

        // Factory methods
        static Response ok(String body) {
            return new SimpleResponse(200, body.getBytes());
        }

        static Response ok(byte[] body) {
            return new SimpleResponse(200, body);
        }

        static Response accepted() {
            return new SimpleResponse(202, "{\"ok\":true}".getBytes());
        }

        static Response accepted(String body) {
            return new SimpleResponse(202, body.getBytes());
        }

        static Response notFound() {
            return new SimpleResponse(404, "{\"error\":\"Not Found\"}".getBytes());
        }

        static Response error(int status, String message) {
            return new SimpleResponse(status, ("{\"error\":\"" + message + "\"}").getBytes());
        }

        static Response serverError(String message) {
            return error(500, message);
        }
    }

    // ========================================================================
    // Handler
    // ========================================================================

    @FunctionalInterface
    interface Handler {
        /**
         * Handle a request and return a response.
         * Can be sync or async.
         */
        CompletableFuture<Response> handle(Request request);

        /**
         * Create a synchronous handler.
         */
        static Handler sync(Function<Request, Response> fn) {
            return request -> CompletableFuture.completedFuture(fn.apply(request));
        }

        /**
         * Create a handler that always returns the same response (for benchmarks).
         */
        static Handler fixed(Response response) {
            return request -> CompletableFuture.completedFuture(response);
        }
    }

    // ========================================================================
    // Interceptor (Middleware)
    // ========================================================================

    @FunctionalInterface
    interface Interceptor {
        /**
         * Intercept the request/response.
         *
         * @param request The incoming request
         * @param next The next handler in the chain
         * @return The response (possibly modified)
         */
        CompletableFuture<Response> intercept(Request request, Handler next);
    }

    // ========================================================================
    // Server Handle
    // ========================================================================

    interface ServerHandle extends AutoCloseable {
        /**
         * Block until the server is terminated.
         */
        void awaitTermination() throws InterruptedException;

        /**
         * Get the port the server is listening on.
         */
        int port();

        /**
         * Get request count (for benchmarking).
         */
        long requestCount();

        @Override
        void close();
    }

    // ========================================================================
    // Server Configuration
    // ========================================================================

    record ServerConfig(
        int workerThreads,
        int backlog,
        int maxRequestSize,
        boolean reusePort
    ) {
        public static ServerConfig defaults() {
            return new ServerConfig(
                Runtime.getRuntime().availableProcessors(),
                8192,
                65536,
                true
            );
        }

        public ServerConfig withWorkerThreads(int threads) {
            return new ServerConfig(threads, backlog, maxRequestSize, reusePort);
        }
    }
}
