package com.reactive.platform.http;

import com.reactive.platform.base.Result;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * UltraHttpServer - Maximum throughput through I/O minimization.
 *
 * Key optimizations:
 * 1. Absolute minimum response (17 bytes)
 * 2. selectNow() always (no timeout syscalls)
 * 3. Process ALL events in one loop iteration
 * 4. Direct buffer reuse
 * 5. Batch accepts
 */
public final class UltraHttpServer {

    // Minimal valid HTTP response with proper status text
    private static final byte[] RESPONSE_MIN = "HTTP/1.1 202 OK\r\nContent-Length:0\r\n\r\n".getBytes();

    private int reactorCount;

    private UltraHttpServer() {
        this.reactorCount = Runtime.getRuntime().availableProcessors();
    }

    public static UltraHttpServer create() {
        return new UltraHttpServer();
    }

    public UltraHttpServer reactors(int count) {
        this.reactorCount = count;
        return this;
    }

    public Handle start(int port) {
        return new ServerHandle(port, reactorCount);
    }

    public interface Handle extends AutoCloseable {
        void awaitTermination() throws InterruptedException;
        long requestCount();
        @Override void close();
    }

    private static final class ServerHandle implements Handle {
        private final UltraReactor[] reactors;
        private final AtomicBoolean running = new AtomicBoolean(true);

        ServerHandle(int port, int reactorCount) {
            reactors = new UltraReactor[reactorCount];

            for (int i = 0; i < reactorCount; i++) {
                try {
                    reactors[i] = new UltraReactor(i, port, running);
                    reactors[i].start();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to start reactor " + i, e);
                }
            }

            System.out.printf("[UltraHttpServer] Started %d reactors on port %d (%.1f bytes/response)%n",
                reactorCount, port, (double) RESPONSE_MIN.length);
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            for (UltraReactor r : reactors) {
                r.join();
            }
        }

        @Override
        public long requestCount() {
            long total = 0;
            for (UltraReactor r : reactors) {
                total += r.requests.sum();
            }
            return total;
        }

        @Override
        public void close() {
            running.set(false);
            for (UltraReactor r : reactors) {
                r.wakeup();
            }
            for (UltraReactor r : reactors) {
                Result.join(r, 1000);
                r.cleanup();
            }
            System.out.printf("[UltraHttpServer] Stopped. Total: %,d requests%n", requestCount());
        }
    }

    private static final class UltraReactor extends Thread {
        private final Selector selector;
        private final ServerSocketChannel serverChannel;
        private final AtomicBoolean running;
        final LongAdder requests = new LongAdder();

        // Pre-allocated response buffer (never changes)
        private final ByteBuffer responseBuffer;
        private final ByteBuffer readBuffer;

        UltraReactor(int id, int port, AtomicBoolean running) throws IOException {
            super("ultra-" + id);
            this.running = running;

            // Pre-allocate with response content
            this.responseBuffer = ByteBuffer.allocateDirect(RESPONSE_MIN.length);
            this.responseBuffer.put(RESPONSE_MIN);

            this.readBuffer = ByteBuffer.allocateDirect(4096);

            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);

            try {
                serverChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            } catch (UnsupportedOperationException e) {
                if (id > 0) {
                    serverChannel.close();
                    throw new IOException("SO_REUSEPORT not supported");
                }
            }

            serverChannel.bind(new InetSocketAddress(port), 8192);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        void wakeup() {
            selector.wakeup();
        }

        void cleanup() {
            Result.run(serverChannel::close);
            Result.run(selector::close);
        }

        @Override
        public void run() {
            int spinCount = 0;
            final int SPIN_LIMIT = 1000;  // Spin more before sleeping

            try {
                while (running.get()) {
                    int ready;
                    if (spinCount < SPIN_LIMIT) {
                        ready = selector.selectNow();
                    } else {
                        // Yield briefly to prevent 100% CPU when truly idle
                        ready = selector.select(1);
                        spinCount = 0;
                    }

                    if (ready == 0) {
                        spinCount++;
                        Thread.onSpinWait();
                        continue;
                    }

                    spinCount = 0;

                    // Process all events without removing until done
                    Set<SelectionKey> keys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = keys.iterator();

                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();
                        iter.remove();

                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            acceptAll();
                        } else if (key.isReadable()) {
                            handleRequest(key);
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[UltraReactor] Error: " + e.getMessage());
                }
            }
        }

        private void acceptAll() throws IOException {
            SocketChannel client;
            while ((client = serverChannel.accept()) != null) {
                client.configureBlocking(false);
                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                client.register(selector, SelectionKey.OP_READ);
            }
        }

        private void handleRequest(SelectionKey key) {
            SocketChannel channel = (SocketChannel) key.channel();

            try {
                readBuffer.clear();
                int bytesRead = channel.read(readBuffer);

                if (bytesRead <= 0) {
                    close(key, channel);
                    return;
                }

                // Write minimal response directly
                responseBuffer.rewind();
                int written = channel.write(responseBuffer);

                // If couldn't write all, spin-wait (response is tiny)
                while (responseBuffer.hasRemaining()) {
                    Thread.onSpinWait();
                    channel.write(responseBuffer);
                }

                requests.increment();

            } catch (IOException e) {
                close(key, channel);
            }
        }

        private void close(SelectionKey key, SocketChannel channel) {
            key.cancel();
            Result.run(channel::close);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int reactors = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        try (Handle handle = UltraHttpServer.create()
                .reactors(reactors)
                .start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.printf("%n[UltraHttpServer] Total: %,d requests%n", handle.requestCount())));
            handle.awaitTermination();
        }
    }
}
