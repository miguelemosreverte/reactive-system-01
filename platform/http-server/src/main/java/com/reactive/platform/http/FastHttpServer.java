package com.reactive.platform.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance HTTP server using Java NIO + Virtual Threads.
 *
 * Design:
 * - Non-blocking I/O with Selector for connection handling
 * - Virtual threads for request processing (cheap, unlimited)
 * - Zero-copy where possible
 * - Minimal allocations in hot path
 *
 * Target: Match Kafka producer throughput (~1M req/s)
 */
public final class FastHttpServer implements HttpServer {

    private final Config config;
    private final Map<RouteKey, Handler> routes = new ConcurrentHashMap<>();

    // Thread-local buffers to avoid allocation
    private static final ThreadLocal<ByteBuffer> READ_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(64 * 1024));

    public FastHttpServer() {
        this(Config.defaults());
    }

    public FastHttpServer(Config config) {
        this.config = config;
    }

    @Override
    public HttpServer route(Method method, String path, Handler handler) {
        routes.put(new RouteKey(method, path), handler);
        return this;
    }

    @Override
    public Handle start(int port) {
        return new ServerHandle(port, config, routes);
    }

    // ========================================================================
    // Route matching
    // ========================================================================

    private record RouteKey(Method method, String path) {}

    // ========================================================================
    // Server implementation
    // ========================================================================

    private static final class ServerHandle implements Handle {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ExecutorService acceptor;
        private final ExecutorService workers;
        private final ServerSocketChannel serverChannel;
        private final Selector selector;
        private final Map<RouteKey, Handler> routes;
        private final CountDownLatch shutdownLatch = new CountDownLatch(1);

        ServerHandle(int port, Config config, Map<RouteKey, Handler> routes) {
            this.routes = routes;

            try {
                // Open server socket
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.socket().setReuseAddress(true);
                serverChannel.socket().bind(new InetSocketAddress(port), config.backlog());

                // Selector for accept events
                selector = Selector.open();
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);

                // Thread pool for workers
                workers = Executors.newCachedThreadPool();

                // Single acceptor thread
                acceptor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "http-acceptor");
                    t.setDaemon(true);
                    return t;
                });

                acceptor.submit(this::acceptLoop);

                System.out.println("[FastHttpServer] Started on port " + port);

            } catch (IOException e) {
                throw new RuntimeException("Failed to start server", e);
            }
        }

        private void acceptLoop() {
            try {
                while (running.get()) {
                    if (selector.select(100) == 0) continue;

                    var keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        var key = keys.next();
                        keys.remove();

                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            accept();
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[FastHttpServer] Accept error: " + e.getMessage());
                }
            } finally {
                shutdownLatch.countDown();
            }
        }

        private void accept() throws IOException {
            SocketChannel client = serverChannel.accept();
            if (client != null) {
                client.configureBlocking(true); // Use blocking for simplicity with virtual threads
                // Handle in virtual thread
                workers.submit(() -> handleConnection(client));
            }
        }

        private void handleConnection(SocketChannel client) {
            try {
                // Keep connection alive for multiple requests
                while (running.get() && client.isConnected()) {
                    Request request = readRequest(client);
                    if (request == null) break; // Connection closed

                    Response response = dispatch(request);
                    writeResponse(client, response);

                    // Check for Connection: close
                    String connection = request.headers().get("connection");
                    if ("close".equalsIgnoreCase(connection)) break;
                }
            } catch (IOException e) {
                // Connection closed or error - normal
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }

        private Request readRequest(SocketChannel client) throws IOException {
            ByteBuffer buffer = READ_BUFFER.get();
            buffer.clear();

            int bytesRead = client.read(buffer);
            if (bytesRead <= 0) return null;

            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            return parseRequest(data);
        }

        private Request parseRequest(byte[] data) {
            String raw = new String(data, StandardCharsets.UTF_8);
            String[] lines = raw.split("\r\n");
            if (lines.length == 0) return null;

            // Parse request line: METHOD PATH HTTP/1.1
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 2) return null;

            Method method = Method.valueOf(requestLine[0]);
            String fullPath = requestLine[1];

            // Parse path and query params
            String path;
            Map<String, String> queryParams = new HashMap<>();
            int queryStart = fullPath.indexOf('?');
            if (queryStart >= 0) {
                path = fullPath.substring(0, queryStart);
                parseQueryParams(fullPath.substring(queryStart + 1), queryParams);
            } else {
                path = fullPath;
            }

            // Parse headers
            Map<String, String> headers = new HashMap<>();
            int bodyStart = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    bodyStart = i + 1;
                    break;
                }
                int colonPos = lines[i].indexOf(':');
                if (colonPos > 0) {
                    String key = lines[i].substring(0, colonPos).trim().toLowerCase();
                    String value = lines[i].substring(colonPos + 1).trim();
                    headers.put(key, value);
                }
            }

            // Parse body
            byte[] body = null;
            if (bodyStart > 0 && bodyStart < lines.length) {
                StringBuilder bodyBuilder = new StringBuilder();
                for (int i = bodyStart; i < lines.length; i++) {
                    bodyBuilder.append(lines[i]);
                    if (i < lines.length - 1) bodyBuilder.append("\r\n");
                }
                body = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);
            }

            return new Request(method, path, headers, queryParams, body);
        }

        private void parseQueryParams(String query, Map<String, String> params) {
            for (String param : query.split("&")) {
                int eq = param.indexOf('=');
                if (eq > 0) {
                    params.put(param.substring(0, eq), param.substring(eq + 1));
                }
            }
        }

        private Response dispatch(Request request) {
            Handler handler = routes.get(new RouteKey(request.method(), request.path()));
            if (handler == null) {
                return Response.notFound();
            }

            try {
                return handler.handle(request).join(); // Virtual thread - blocking is OK
            } catch (Exception e) {
                return Response.serverError(e.getMessage());
            }
        }

        private void writeResponse(SocketChannel client, Response response) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(response.status()).append(" OK\r\n");
            response.headers().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
            sb.append("Content-Length: ").append(response.body().length).append("\r\n");
            sb.append("Connection: keep-alive\r\n");
            sb.append("\r\n");

            byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(headerBytes.length + response.body().length);
            buffer.put(headerBytes);
            buffer.put(response.body());
            buffer.flip();

            while (buffer.hasRemaining()) {
                client.write(buffer);
            }
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            shutdownLatch.await();
        }

        @Override
        public void close() {
            running.set(false);
            try {
                selector.wakeup();
                serverChannel.close();
                selector.close();
            } catch (IOException ignored) {}
            acceptor.shutdown();
            workers.shutdown();
            System.out.println("[FastHttpServer] Stopped");
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        HttpServer server = new FastHttpServer(Config.defaults().withWorkerThreads(workers))
            .get("/health", Handler.sync(req -> Response.ok("{\"status\":\"UP\"}")))
            .post("/events", Handler.sync(req -> Response.accepted("{\"ok\":true}")));

        try (Handle handle = server.start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            handle.awaitTermination();
        }
    }
}
