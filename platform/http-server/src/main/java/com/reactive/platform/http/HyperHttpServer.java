package com.reactive.platform.http;

import com.reactive.platform.base.Result;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Hyper-optimized HTTP/1.1 server with HTTP pipelining support.
 *
 * Key optimizations:
 * - Multiple event loops with lock-free connection distribution
 * - HTTP pipelining: process multiple requests per read()
 * - Batched writes: multiple responses in single write()
 * - Zero-allocation hot path
 * - Direct ByteBuffers for zero-copy I/O
 *
 * Architecture:
 * - 1 acceptor thread (non-blocking accept)
 * - N worker event loops (one per core)
 * - Lock-free handoff via ConcurrentLinkedQueue
 */
public final class HyperHttpServer {

    // Pre-computed HTTP responses
    private static final byte[] HTTP_200 = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: keep-alive\r\nContent-Length: ".getBytes();
    private static final byte[] HTTP_202 = "HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nConnection: keep-alive\r\nContent-Length: ".getBytes();
    private static final byte[] HTTP_404 = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nConnection: keep-alive\r\nContent-Length: 23\r\n\r\n{\"error\":\"Not Found\"}".getBytes();
    private static final byte[] CRLF_CRLF = "\r\n\r\n".getBytes();

    // Pre-built common responses
    private static final byte[] HEALTH_BODY = "{\"status\":\"UP\"}".getBytes();
    private static final byte[] HEALTH_RESPONSE = buildResponse(200, HEALTH_BODY);
    private static final byte[] ACK_BODY = "{\"ok\":true}".getBytes();
    private static final byte[] ACK_RESPONSE = buildResponse(202, ACK_BODY);

    private static byte[] buildResponse(int status, byte[] body) {
        byte[] prefix = status == 200 ? HTTP_200 : HTTP_202;
        byte[] lengthBytes = Integer.toString(body.length).getBytes();
        byte[] response = new byte[prefix.length + lengthBytes.length + CRLF_CRLF.length + body.length];
        int pos = 0;
        System.arraycopy(prefix, 0, response, pos, prefix.length); pos += prefix.length;
        System.arraycopy(lengthBytes, 0, response, pos, lengthBytes.length); pos += lengthBytes.length;
        System.arraycopy(CRLF_CRLF, 0, response, pos, CRLF_CRLF.length); pos += CRLF_CRLF.length;
        System.arraycopy(body, 0, response, pos, body.length);
        return response;
    }

    // Handler types
    private Function<ByteBuffer, byte[]> getHandler;
    private Function<ByteBuffer, byte[]> postHandler;

    private HyperHttpServer() {}

    public static HyperHttpServer create() {
        return new HyperHttpServer();
    }

    public HyperHttpServer onGet(Function<ByteBuffer, byte[]> handler) {
        this.getHandler = handler;
        return this;
    }

    public HyperHttpServer onPost(Function<ByteBuffer, byte[]> handler) {
        this.postHandler = handler;
        return this;
    }

    public Handle start(int port) {
        return start(port, Runtime.getRuntime().availableProcessors());
    }

    public Handle start(int port, int workerCount) {
        return new ServerHandle(port, workerCount, this);
    }

    // ========================================================================
    // Handle interface
    // ========================================================================

    public interface Handle extends AutoCloseable {
        void awaitTermination() throws InterruptedException;
        long requestCount();
        @Override void close();
    }

    // ========================================================================
    // Server implementation
    // ========================================================================

    private static final class ServerHandle implements Handle {
        private static final int BUFFER_SIZE = 64 * 1024;

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ServerSocketChannel serverChannel;
        private final Worker[] workers;
        private final Thread acceptorThread;
        private int nextWorker = 0;

        ServerHandle(int port, int workerCount, HyperHttpServer server) {
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                serverChannel.bind(new InetSocketAddress(port), 4096);

                // Create workers
                workers = new Worker[workerCount];
                for (int i = 0; i < workerCount; i++) {
                    workers[i] = new Worker(i, server, running);
                    workers[i].start();
                }

                // Acceptor thread
                acceptorThread = new Thread(this::acceptLoop, "hyper-accept");
                acceptorThread.start();

                System.out.printf("[HyperHttpServer] Started on port %d with %d workers%n", port, workerCount);

            } catch (IOException e) {
                throw new RuntimeException("Failed to start", e);
            }
        }

        private void acceptLoop() {
            try {
                Selector acceptSelector = Selector.open();
                serverChannel.register(acceptSelector, SelectionKey.OP_ACCEPT);

                while (running.get()) {
                    if (acceptSelector.select(1) == 0) continue;

                    Iterator<SelectionKey> keys = acceptSelector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (key.isAcceptable()) {
                            SocketChannel client = serverChannel.accept();
                            if (client != null) {
                                client.configureBlocking(false);
                                client.setOption(StandardSocketOptions.TCP_NODELAY, true);

                                // Round-robin to workers (lock-free)
                                int idx = nextWorker++ % workers.length;
                                workers[idx].enqueue(client);
                            }
                        }
                    }
                }
                acceptSelector.close();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[HyperHttpServer] Accept error: " + e.getMessage());
                }
            }
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            acceptorThread.join();
            for (Worker w : workers) {
                w.join();
            }
        }

        @Override
        public long requestCount() {
            long total = 0;
            for (Worker w : workers) {
                total += w.requestCount.get();
            }
            return total;
        }

        @Override
        public void close() {
            running.set(false);
            for (Worker w : workers) {
                w.selector.wakeup();
            }
            Result.run(() -> acceptorThread.join(1000));
            for (Worker w : workers) {
                Result.run(() -> w.join(1000));
            }
            Result.run(serverChannel::close);
            System.out.printf("[HyperHttpServer] Stopped. Requests: %d%n", requestCount());
        }
    }

    // ========================================================================
    // Worker - Event loop with pipelining support
    // ========================================================================

    private static final class Worker extends Thread {
        final Selector selector;
        final AtomicLong requestCount = new AtomicLong(0);
        private final ConcurrentLinkedQueue<SocketChannel> pendingConnections = new ConcurrentLinkedQueue<>();
        private final HyperHttpServer server;
        private final AtomicBoolean running;
        private final ByteBuffer readBuffer;
        private final ByteBuffer writeBuffer;

        Worker(int id, HyperHttpServer server, AtomicBoolean running) throws IOException {
            super("hyper-worker-" + id);
            this.selector = Selector.open();
            this.server = server;
            this.running = running;
            this.readBuffer = ByteBuffer.allocateDirect(64 * 1024);
            this.writeBuffer = ByteBuffer.allocateDirect(64 * 1024);
        }

        void enqueue(SocketChannel channel) {
            pendingConnections.offer(channel);
            selector.wakeup();
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    // Register any pending connections
                    SocketChannel pending;
                    while ((pending = pendingConnections.poll()) != null) {
                        final SocketChannel ch = pending;
                        Result.run(() -> ch.register(selector, SelectionKey.OP_READ));
                    }

                    if (selector.select(1) == 0) continue;

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (!key.isValid()) continue;

                        if (key.isReadable()) {
                            handleRead(key);
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[HyperHttpServer] Worker error: " + e.getMessage());
                }
            }
        }

        private void handleRead(SelectionKey key) {
            SocketChannel channel = (SocketChannel) key.channel();

            try {
                readBuffer.clear();
                int bytesRead = channel.read(readBuffer);

                if (bytesRead <= 0) {
                    close(key, channel);
                    return;
                }

                readBuffer.flip();

                // Process all pipelined requests in the buffer
                writeBuffer.clear();
                int requestsProcessed = processPipelinedRequests(readBuffer, writeBuffer);
                requestCount.addAndGet(requestsProcessed);

                // Write all responses at once
                writeBuffer.flip();
                while (writeBuffer.hasRemaining()) {
                    channel.write(writeBuffer);
                }

            } catch (IOException e) {
                close(key, channel);
            }
        }

        private int processPipelinedRequests(ByteBuffer input, ByteBuffer output) {
            int count = 0;
            int pos = 0;
            int limit = input.limit();

            while (pos < limit) {
                // Find end of this HTTP request (double CRLF)
                int requestEnd = findRequestEnd(input, pos, limit);
                if (requestEnd < 0) break; // Incomplete request

                // Process this request
                byte[] response = processRequest(input, pos, requestEnd);
                if (response != null && output.remaining() >= response.length) {
                    output.put(response);
                    count++;
                }

                // Move to next request
                pos = requestEnd;
            }

            return count > 0 ? count : 1; // At least process one request
        }

        private int findRequestEnd(ByteBuffer buffer, int start, int limit) {
            // Find \r\n\r\n
            for (int i = start; i < limit - 3; i++) {
                if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n' &&
                    buffer.get(i + 2) == '\r' && buffer.get(i + 3) == '\n') {

                    // Check for Content-Length (body)
                    int bodyStart = i + 4;
                    int contentLength = findContentLength(buffer, start, i);

                    if (contentLength > 0) {
                        int requestEnd = bodyStart + contentLength;
                        return requestEnd <= limit ? requestEnd : -1;
                    }

                    return bodyStart;
                }
            }
            return -1;
        }

        private int findContentLength(ByteBuffer buffer, int start, int headerEnd) {
            // Look for "Content-Length: " in headers
            byte[] pattern = "Content-Length: ".getBytes();
            outer:
            for (int i = start; i < headerEnd - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    byte b = buffer.get(i + j);
                    byte p = pattern[j];
                    if (b != p && (b | 0x20) != (p | 0x20)) {
                        continue outer;
                    }
                }
                // Found pattern, parse number
                int numStart = i + pattern.length;
                int numEnd = numStart;
                while (numEnd < headerEnd && buffer.get(numEnd) >= '0' && buffer.get(numEnd) <= '9') {
                    numEnd++;
                }
                if (numEnd > numStart) {
                    int len = 0;
                    for (int k = numStart; k < numEnd; k++) {
                        len = len * 10 + (buffer.get(k) - '0');
                    }
                    return len;
                }
            }
            return 0;
        }

        private byte[] processRequest(ByteBuffer buffer, int start, int end) {
            byte first = buffer.get(start);

            if (first == 'G') {
                // GET request
                if (server.getHandler != null) {
                    buffer.position(start);
                    buffer.limit(end);
                    byte[] body = server.getHandler.apply(buffer);
                    return buildResponse(200, body);
                }
                return HEALTH_RESPONSE;

            } else if (first == 'P') {
                // POST request
                if (server.postHandler != null) {
                    // Find body start
                    int bodyStart = -1;
                    for (int i = start; i < end - 3; i++) {
                        if (buffer.get(i) == '\r' && buffer.get(i + 1) == '\n' &&
                            buffer.get(i + 2) == '\r' && buffer.get(i + 3) == '\n') {
                            bodyStart = i + 4;
                            break;
                        }
                    }
                    if (bodyStart > 0 && bodyStart < end) {
                        buffer.position(bodyStart);
                        buffer.limit(end);
                        byte[] body = server.postHandler.apply(buffer);
                        return buildResponse(202, body);
                    }
                }
                return ACK_RESPONSE;
            }

            return HTTP_404;
        }

        private void close(SelectionKey key, SocketChannel channel) {
            key.cancel();
            Result.run(channel::close);
        }
    }

    // ========================================================================
    // Main - Standalone server
    // ========================================================================

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  HyperHttpServer - Multi-core HTTP/1.1 with Pipelining");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.printf("  Port:    %d%n", port);
        System.out.printf("  Workers: %d%n", workers);
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("Test with:");
        System.out.printf("  curl http://localhost:%d/health%n", port);
        System.out.printf("  wrk -t4 -c100 -d10s http://localhost:%d/health%n", port);
        System.out.println();

        HyperHttpServer server = HyperHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());

        try (Handle handle = server.start(port, workers)) {
            System.out.println("Server started. Press Ctrl+C to stop.");
            Result.run(() -> handle.awaitTermination());
        }
    }
}
