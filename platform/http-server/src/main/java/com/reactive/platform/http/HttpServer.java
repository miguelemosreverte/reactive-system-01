package com.reactive.platform.http;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Minimal HTTP server interface.
 *
 * Design principles:
 * - Zero dependencies on Spring or heavyweight frameworks
 * - Functional style: handlers are pure functions
 * - Immutable request/response types
 * - Async-first with CompletableFuture
 */
public interface HttpServer {

    /**
     * Start the server on the given port.
     * Returns a handle to stop the server.
     */
    Handle start(int port);

    /**
     * Register a route handler.
     */
    HttpServer route(Method method, String path, Handler handler);

    /**
     * Convenience for GET routes.
     */
    default HttpServer get(String path, Handler handler) {
        return route(Method.GET, path, handler);
    }

    /**
     * Convenience for POST routes.
     */
    default HttpServer post(String path, Handler handler) {
        return route(Method.POST, path, handler);
    }

    // ========================================================================
    // Types
    // ========================================================================

    enum Method { GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS }

    /**
     * Immutable HTTP request.
     */
    record Request(
            Method method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            byte[] body
    ) {
        public String bodyAsString() {
            return body != null ? new String(body) : "";
        }

        public <T> T bodyAs(Function<byte[], T> decoder) {
            return decoder.apply(body);
        }
    }

    /**
     * Immutable HTTP response.
     */
    record Response(
            int status,
            Map<String, String> headers,
            byte[] body
    ) {
        // Common responses
        public static Response ok(byte[] body) {
            return new Response(200, Map.of("Content-Type", "application/json"), body);
        }

        public static Response ok(String body) {
            return ok(body.getBytes());
        }

        public static Response accepted(String body) {
            return new Response(202, Map.of("Content-Type", "application/json"), body.getBytes());
        }

        public static Response badRequest(String message) {
            return new Response(400, Map.of("Content-Type", "application/json"),
                    ("{\"error\":\"" + message + "\"}").getBytes());
        }

        public static Response notFound() {
            return new Response(404, Map.of("Content-Type", "application/json"),
                    "{\"error\":\"Not found\"}".getBytes());
        }

        public static Response serverError(String message) {
            return new Response(500, Map.of("Content-Type", "application/json"),
                    ("{\"error\":\"" + message + "\"}").getBytes());
        }
    }

    /**
     * Handler: Request -> Response (sync or async).
     */
    @FunctionalInterface
    interface Handler {
        CompletableFuture<Response> handle(Request request);

        /**
         * Create sync handler from function.
         */
        static Handler sync(Function<Request, Response> fn) {
            return req -> CompletableFuture.completedFuture(fn.apply(req));
        }
    }

    /**
     * Server handle for lifecycle management.
     */
    interface Handle extends AutoCloseable {
        void awaitTermination() throws InterruptedException;
        @Override void close();
    }

    // ========================================================================
    // Factory
    // ========================================================================

    /**
     * Create a new HTTP server instance.
     */
    static HttpServer create() {
        return new FastHttpServer();
    }

    /**
     * Create with configuration.
     */
    static HttpServer create(Config config) {
        return new FastHttpServer(config);
    }

    /**
     * Server configuration.
     */
    record Config(
            int ioThreads,
            int workerThreads,
            int maxRequestSize,
            int backlog
    ) {
        public static Config defaults() {
            int cores = Runtime.getRuntime().availableProcessors();
            return new Config(
                    cores,           // IO threads = CPU cores
                    cores * 2,       // Worker threads
                    1024 * 64,       // 64KB max request
                    1024             // Connection backlog
            );
        }

        public Config withIoThreads(int n) {
            return new Config(n, workerThreads, maxRequestSize, backlog);
        }

        public Config withWorkerThreads(int n) {
            return new Config(ioThreads, n, maxRequestSize, backlog);
        }
    }
}
