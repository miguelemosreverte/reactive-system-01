package com.reactive.platform.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * TurboHttpServer - Optimized for maximum throughput.
 *
 * Key optimizations based on diagnostics:
 * 1. selectNow() when busy (avoids 25µs select() overhead)
 * 2. select(timeout) only when truly idle
 * 3. Process ALL ready events before next select
 * 4. Minimal response (no parsing overhead)
 * 5. Busy-spin on write to avoid re-registration
 */
public final class TurboHttpServer {

    // Minimal response - every byte counts at 1M/s
    private static final byte[] RESPONSE_202 = (
        "HTTP/1.1 202 Accepted\r\n" +
        "Content-Length: 2\r\n" +
        "\r\n" +
        "ok"
    ).getBytes();

    private static final byte[] RESPONSE_200 = (
        "HTTP/1.1 200 OK\r\n" +
        "Content-Length: 2\r\n" +
        "\r\n" +
        "ok"
    ).getBytes();

    private Consumer<ByteBuffer> bodyHandler;
    private int reactorCount;

    private TurboHttpServer() {
        this.reactorCount = Runtime.getRuntime().availableProcessors();
    }

    public static TurboHttpServer create() {
        return new TurboHttpServer();
    }

    public TurboHttpServer reactors(int count) {
        this.reactorCount = count;
        return this;
    }

    public TurboHttpServer onBody(Consumer<ByteBuffer> handler) {
        this.bodyHandler = handler;
        return this;
    }

    public Handle start(int port) {
        return new ServerHandle(port, reactorCount, bodyHandler);
    }

    public interface Handle extends AutoCloseable {
        void awaitTermination() throws InterruptedException;
        long requestCount();
        long[] perReactorCounts();
        @Override void close();
    }

    private static final class ServerHandle implements Handle {
        private final TurboReactor[] reactors;
        private final AtomicBoolean running = new AtomicBoolean(true);

        ServerHandle(int port, int reactorCount, Consumer<ByteBuffer> bodyHandler) {
            reactors = new TurboReactor[reactorCount];

            for (int i = 0; i < reactorCount; i++) {
                try {
                    reactors[i] = new TurboReactor(i, port, bodyHandler, running);
                    reactors[i].start();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to start reactor " + i, e);
                }
            }

            System.out.printf("[TurboHttpServer] Started %d reactors on port %d%n", reactorCount, port);
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            for (TurboReactor r : reactors) {
                r.join();
            }
        }

        @Override
        public long requestCount() {
            long total = 0;
            for (TurboReactor r : reactors) {
                total += r.requests.sum();
            }
            return total;
        }

        @Override
        public long[] perReactorCounts() {
            long[] counts = new long[reactors.length];
            for (int i = 0; i < reactors.length; i++) {
                counts[i] = reactors[i].requests.sum();
            }
            return counts;
        }

        @Override
        public void close() {
            running.set(false);
            for (TurboReactor r : reactors) {
                r.wakeup();
            }
            try {
                for (TurboReactor r : reactors) {
                    r.join(1000);
                    r.cleanup();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.printf("[TurboHttpServer] Stopped. Total: %,d requests%n", requestCount());
        }
    }

    private static final class TurboReactor extends Thread {
        private static final int BUFFER_SIZE = 4096;  // Smaller buffer, less cache pressure
        private static final int SPIN_COUNT = 256;    // Spins before sleeping

        private final Selector selector;
        private final ServerSocketChannel serverChannel;
        private final Consumer<ByteBuffer> bodyHandler;
        private final AtomicBoolean running;
        final LongAdder requests = new LongAdder();

        private final ByteBuffer readBuffer;
        private final ByteBuffer writeBuffer;

        TurboReactor(int id, int port, Consumer<ByteBuffer> bodyHandler, AtomicBoolean running) throws IOException {
            super("turbo-" + id);
            this.bodyHandler = bodyHandler;
            this.running = running;
            this.readBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            this.writeBuffer = ByteBuffer.allocateDirect(256);

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
            try { serverChannel.close(); } catch (IOException ignored) {}
            try { selector.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            int consecutiveEmpty = 0;

            try {
                while (running.get()) {
                    // Strategy: spin with selectNow() when busy, sleep when idle
                    int ready;
                    if (consecutiveEmpty < SPIN_COUNT) {
                        ready = selector.selectNow();
                    } else {
                        // Been idle for a while, yield CPU
                        ready = selector.select(1);
                        consecutiveEmpty = 0;
                    }

                    if (ready == 0) {
                        consecutiveEmpty++;
                        continue;
                    }

                    consecutiveEmpty = 0;
                    processEvents();
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[TurboReactor] Error: " + e.getMessage());
                }
            }
        }

        private void processEvents() throws IOException {
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) continue;

                if (key.isAcceptable()) {
                    accept();
                } else if (key.isReadable()) {
                    read(key);
                }
            }
        }

        private void accept() throws IOException {
            // Accept all pending connections in one go
            SocketChannel client;
            while ((client = serverChannel.accept()) != null) {
                client.configureBlocking(false);
                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                client.setOption(StandardSocketOptions.SO_SNDBUF, 32768);
                client.setOption(StandardSocketOptions.SO_RCVBUF, 32768);
                client.register(selector, SelectionKey.OP_READ);
            }
        }

        private void read(SelectionKey key) {
            SocketChannel channel = (SocketChannel) key.channel();

            try {
                readBuffer.clear();
                int bytesRead = channel.read(readBuffer);

                if (bytesRead <= 0) {
                    close(key, channel);
                    return;
                }

                // Respond immediately - no parsing needed for fire-and-forget
                writeBuffer.clear();
                writeBuffer.put(RESPONSE_202);
                writeBuffer.flip();

                // Busy-spin write (response is tiny, will complete in one call)
                while (writeBuffer.hasRemaining()) {
                    if (channel.write(writeBuffer) == 0) {
                        // Socket buffer full - very rare for tiny response
                        Thread.onSpinWait();
                    }
                }

                requests.increment();

            } catch (IOException e) {
                close(key, channel);
            }
        }

        private void close(SelectionKey key, SocketChannel channel) {
            try {
                key.cancel();
                channel.close();
            } catch (IOException ignored) {}
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int reactors = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        try (Handle handle = TurboHttpServer.create()
                .reactors(reactors)
                .start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.printf("%n[TurboHttpServer] Total: %,d requests%n", handle.requestCount())));
            handle.awaitTermination();
        }
    }
}
