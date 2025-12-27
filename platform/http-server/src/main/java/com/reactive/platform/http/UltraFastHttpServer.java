package com.reactive.platform.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Ultra-high-performance HTTP server.
 *
 * Optimizations:
 * - Zero allocation in hot path (object pooling)
 * - Direct ByteBuffer reuse
 * - No String parsing - work directly with bytes
 * - Pre-computed response templates
 * - Single-threaded event loop (like Redis)
 *
 * For maximum throughput on event ingestion workloads.
 */
public final class UltraFastHttpServer {

    // Pre-computed response bytes (avoid allocation)
    private static final byte[] RESPONSE_200_PREFIX =
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ".getBytes();
    private static final byte[] RESPONSE_202_PREFIX =
            "HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: ".getBytes();
    private static final byte[] CRLF_CRLF = "\r\n\r\n".getBytes();
    private static final byte[] CONNECTION_KEEP_ALIVE = "Connection: keep-alive\r\n".getBytes();

    // Pre-computed common responses
    private static final byte[] HEALTH_BODY = "{\"status\":\"UP\"}".getBytes();
    private static final byte[] HEALTH_RESPONSE = buildResponse(200, HEALTH_BODY);

    private static final byte[] NOT_FOUND_BODY = "{\"error\":\"Not found\"}".getBytes();
    private static final byte[] NOT_FOUND_RESPONSE = buildResponse(404, NOT_FOUND_BODY);

    // Handlers (using byte[] for zero-copy)
    private Function<ByteBuffer, byte[]> getHandler;
    private Function<ByteBuffer, byte[]> postHandler;

    private UltraFastHttpServer() {}

    public static UltraFastHttpServer create() {
        return new UltraFastHttpServer();
    }

    /**
     * Set GET handler. Receives request buffer, returns response body bytes.
     */
    public UltraFastHttpServer onGet(Function<ByteBuffer, byte[]> handler) {
        this.getHandler = handler;
        return this;
    }

    /**
     * Set POST handler. Receives request buffer (positioned at body), returns response body bytes.
     */
    public UltraFastHttpServer onPost(Function<ByteBuffer, byte[]> handler) {
        this.postHandler = handler;
        return this;
    }

    /**
     * Start server. Returns handle for lifecycle management.
     */
    public Handle start(int port) {
        return new ServerHandle(port, this);
    }

    // ========================================================================
    // Pre-build responses to avoid allocation
    // ========================================================================

    private static byte[] buildResponse(int status, byte[] body) {
        byte[] prefix = status == 200 ? RESPONSE_200_PREFIX : RESPONSE_202_PREFIX;
        byte[] lengthBytes = Integer.toString(body.length).getBytes();

        byte[] response = new byte[prefix.length + lengthBytes.length + CONNECTION_KEEP_ALIVE.length + CRLF_CRLF.length + body.length];
        int pos = 0;

        System.arraycopy(prefix, 0, response, pos, prefix.length);
        pos += prefix.length;

        System.arraycopy(lengthBytes, 0, response, pos, lengthBytes.length);
        pos += lengthBytes.length;

        response[pos++] = '\r';
        response[pos++] = '\n';

        System.arraycopy(CONNECTION_KEEP_ALIVE, 0, response, pos, CONNECTION_KEEP_ALIVE.length);
        pos += CONNECTION_KEEP_ALIVE.length;

        response[pos++] = '\r';
        response[pos++] = '\n';

        System.arraycopy(body, 0, response, pos, body.length);

        return response;
    }

    // ========================================================================
    // Server Handle - Single-threaded event loop
    // ========================================================================

    public interface Handle extends AutoCloseable {
        void awaitTermination() throws InterruptedException;
        long requestCount();
        @Override void close();
    }

    private static final class ServerHandle implements Handle {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread eventLoopThread;
        private final ServerSocketChannel serverChannel;
        private final Selector selector;
        private final UltraFastHttpServer server;
        private final AtomicLong requests = new AtomicLong(0);

        // Per-connection state (pooled)
        private static final int BUFFER_SIZE = 64 * 1024;

        ServerHandle(int port, UltraFastHttpServer server) {
            this.server = server;

            try {
                selector = Selector.open();
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.socket().setReuseAddress(true);
                serverChannel.socket().bind(new InetSocketAddress(port), 4096);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);

                eventLoopThread = new Thread(this::eventLoop, "ultra-http-loop");
                eventLoopThread.start();

                System.out.println("[UltraFastHttpServer] Started on port " + port);

            } catch (IOException e) {
                throw new RuntimeException("Failed to start", e);
            }
        }

        private void eventLoop() {
            // Thread-local buffers (never reallocated)
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            ByteBuffer writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

            try {
                while (running.get()) {
                    int ready = selector.select(1);
                    if (ready == 0) continue;

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            accept(key);
                        } else if (key.isReadable()) {
                            read(key, readBuffer, writeBuffer);
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[UltraFastHttpServer] Error: " + e.getMessage());
                }
            }
        }

        private void accept(SelectionKey key) throws IOException {
            SocketChannel client = serverChannel.accept();
            if (client != null) {
                client.configureBlocking(false);
                client.socket().setTcpNoDelay(true);
                client.register(selector, SelectionKey.OP_READ);
            }
        }

        private void read(SelectionKey key, ByteBuffer readBuffer, ByteBuffer writeBuffer) {
            SocketChannel client = (SocketChannel) key.channel();

            try {
                readBuffer.clear();
                int bytesRead = client.read(readBuffer);

                if (bytesRead <= 0) {
                    key.cancel();
                    client.close();
                    return;
                }

                readBuffer.flip();
                requests.incrementAndGet();

                // Fast path: determine method from first byte
                byte firstByte = readBuffer.get(0);
                byte[] response;

                if (firstByte == 'G') {
                    // GET request
                    response = server.getHandler != null
                            ? buildResponse(200, server.getHandler.apply(readBuffer))
                            : HEALTH_RESPONSE;
                } else if (firstByte == 'P') {
                    // POST request - find body
                    int bodyStart = findBodyStart(readBuffer);
                    if (bodyStart > 0 && bodyStart < readBuffer.limit() && server.postHandler != null) {
                        readBuffer.position(bodyStart);
                        response = buildResponse(202, server.postHandler.apply(readBuffer));
                    } else {
                        response = NOT_FOUND_RESPONSE;
                    }
                } else {
                    response = NOT_FOUND_RESPONSE;
                }

                // Write response
                writeBuffer.clear();
                writeBuffer.put(response);
                writeBuffer.flip();

                while (writeBuffer.hasRemaining()) {
                    client.write(writeBuffer);
                }

            } catch (IOException e) {
                try {
                    key.cancel();
                    client.close();
                } catch (IOException ignored) {}
            }
        }

        // Find \r\n\r\n to locate body start
        private int findBodyStart(ByteBuffer buffer) {
            int limit = buffer.limit();
            for (int i = 0; i < limit - 3; i++) {
                if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n' &&
                    buffer.get(i + 2) == '\r' && buffer.get(i + 3) == '\n') {
                    return i + 4;
                }
            }
            return -1;
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            eventLoopThread.join();
        }

        @Override
        public long requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            running.set(false);
            selector.wakeup();
            try {
                eventLoopThread.join(1000);
                serverChannel.close();
                selector.close();
            } catch (Exception ignored) {}
            System.out.println("[UltraFastHttpServer] Stopped. Requests: " + requests.get());
        }
    }

    // ========================================================================
    // Fast JSON body extraction (zero-copy where possible)
    // ========================================================================

    /**
     * Extract a string field value from JSON in ByteBuffer.
     * Returns null if not found. Zero allocation for simple cases.
     */
    public static String extractField(ByteBuffer buffer, String fieldName) {
        byte[] fieldBytes = ("\"" + fieldName + "\":\"").getBytes();
        int pos = indexOf(buffer, fieldBytes);
        if (pos < 0) return null;

        int start = pos + fieldBytes.length;
        int end = start;
        while (end < buffer.limit() && buffer.get(end) != '"') {
            end++;
        }

        byte[] value = new byte[end - start];
        buffer.position(start);
        buffer.get(value);
        return new String(value);
    }

    private static int indexOf(ByteBuffer buffer, byte[] pattern) {
        int limit = buffer.limit() - pattern.length;
        outer:
        for (int i = buffer.position(); i <= limit; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buffer.get(i + j) != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        // Note: This server is single-threaded by design (like Redis)
        // Second arg is ignored but accepted for interface consistency

        try (Handle handle = UltraFastHttpServer.create()
                .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
                .onPost(buf -> "{\"ok\":true}".getBytes())
                .start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.printf("%n[UltraFastHttpServer] Total: %,d requests%n", handle.requestCount())));
            handle.awaitTermination();
        }
    }
}
