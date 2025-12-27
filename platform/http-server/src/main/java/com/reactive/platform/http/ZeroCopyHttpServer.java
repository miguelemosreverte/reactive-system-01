package com.reactive.platform.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * ZeroCopyHttpServer - Pure Java, minimal syscall HTTP server.
 *
 * Optimizations for reducing kernel overhead:
 * 1. Busy-polling with selectNow() - no blocking syscalls when active
 * 2. Direct ByteBuffers - avoid heap-to-native copies
 * 3. Pre-allocated response - no allocation per request
 * 4. Scatter/gather I/O ready (future: use GatheringByteChannel)
 * 5. Adaptive polling - busy-poll when active, sleep when idle
 * 6. Connection batching - register multiple connections per wakeup
 */
public final class ZeroCopyHttpServer {

    private static final byte[] RESPONSE = (
        "HTTP/1.1 202 Accepted\r\n" +
        "Content-Length: 2\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n" +
        "ok"
    ).getBytes();

    private int workerCount;
    private boolean busyPoll = true;

    private ZeroCopyHttpServer() {
        this.workerCount = Runtime.getRuntime().availableProcessors();
    }

    public static ZeroCopyHttpServer create() {
        return new ZeroCopyHttpServer();
    }

    public ZeroCopyHttpServer workers(int count) {
        this.workerCount = count;
        return this;
    }

    public ZeroCopyHttpServer busyPoll(boolean enabled) {
        this.busyPoll = enabled;
        return this;
    }

    public Handle start(int port) {
        return new ServerHandle(port, workerCount, busyPoll);
    }

    public interface Handle extends AutoCloseable {
        long requestCount();
        long[] perWorkerCounts();
        void awaitTermination() throws InterruptedException;
        @Override void close();
    }

    private static final class ServerHandle implements Handle {
        private final Acceptor acceptor;
        private final Worker[] workers;
        private final AtomicBoolean running = new AtomicBoolean(true);

        ServerHandle(int port, int workerCount, boolean busyPoll) {
            workers = new Worker[workerCount];

            for (int i = 0; i < workerCount; i++) {
                try {
                    workers[i] = new Worker(i, running, busyPoll);
                    workers[i].start();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to start worker " + i, e);
                }
            }

            try {
                acceptor = new Acceptor(port, workers, running);
                acceptor.start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start acceptor", e);
            }

            System.out.printf("[ZeroCopyHttpServer] Started on port %d with %d workers (busyPoll=%s)%n",
                port, workerCount, busyPoll);
        }

        @Override
        public long requestCount() {
            long total = 0;
            for (Worker w : workers) {
                total += w.requests.sum();
            }
            return total;
        }

        @Override
        public long[] perWorkerCounts() {
            long[] counts = new long[workers.length];
            for (int i = 0; i < workers.length; i++) {
                counts[i] = workers[i].requests.sum();
            }
            return counts;
        }

        @Override
        public void awaitTermination() throws InterruptedException {
            acceptor.join();
        }

        @Override
        public void close() {
            running.set(false);
            acceptor.wakeup();
            for (Worker w : workers) {
                w.wakeup();
            }
            try {
                acceptor.join(1000);
                for (Worker w : workers) {
                    w.join(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acceptor.cleanup();
            for (Worker w : workers) {
                w.cleanup();
            }
            System.out.printf("[ZeroCopyHttpServer] Stopped. Total: %,d requests%n", requestCount());
        }
    }

    /**
     * Acceptor thread - only accepts connections, delegates to workers.
     * Uses selectNow() for busy-polling on accept.
     */
    private static final class Acceptor extends Thread {
        private final Selector selector;
        private final ServerSocketChannel serverChannel;
        private final Worker[] workers;
        private final AtomicBoolean running;
        private final AtomicInteger nextWorker = new AtomicInteger(0);

        Acceptor(int port, Worker[] workers, AtomicBoolean running) throws IOException {
            super("acceptor");
            this.workers = workers;
            this.running = running;

            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, 65536);
            serverChannel.bind(new InetSocketAddress(port), 65536); // Large backlog
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        void wakeup() { selector.wakeup(); }

        void cleanup() {
            try { serverChannel.close(); } catch (IOException ignored) {}
            try { selector.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            int idleCount = 0;
            try {
                while (running.get()) {
                    int ready;
                    if (idleCount < 1000) {
                        ready = selector.selectNow();
                    } else {
                        ready = selector.select(1);
                    }

                    if (ready == 0) {
                        idleCount++;
                        if (idleCount > 10000) {
                            Thread.onSpinWait();
                        }
                        continue;
                    }
                    idleCount = 0;

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (key.isAcceptable()) {
                            acceptAll();
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) System.err.println("[Acceptor] Error: " + e.getMessage());
            }
        }

        private void acceptAll() throws IOException {
            SocketChannel client;
            while ((client = serverChannel.accept()) != null) {
                client.configureBlocking(false);
                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                client.setOption(StandardSocketOptions.SO_RCVBUF, 32768);
                client.setOption(StandardSocketOptions.SO_SNDBUF, 32768);

                // Round-robin to workers
                int idx = Math.abs(nextWorker.getAndIncrement() % workers.length);
                workers[idx].addConnection(client);
            }
        }
    }

    /**
     * Worker thread - handles I/O for assigned connections.
     * Uses busy-polling with selectNow() to minimize syscall latency.
     */
    private static final class Worker extends Thread {
        private final Selector selector;
        private final AtomicBoolean running;
        private final boolean busyPoll;
        final LongAdder requests = new LongAdder();
        private final Queue<SocketChannel> pendingConnections = new ConcurrentLinkedQueue<>();

        // Pre-allocated buffers per worker (no sharing, no locks)
        private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(8192);
        private final ByteBuffer writeBuffer;

        Worker(int id, AtomicBoolean running, boolean busyPoll) throws IOException {
            super("worker-" + id);
            this.running = running;
            this.busyPoll = busyPoll;
            this.selector = Selector.open();

            // Pre-fill write buffer with response
            this.writeBuffer = ByteBuffer.allocateDirect(RESPONSE.length);
            this.writeBuffer.put(RESPONSE);
        }

        void addConnection(SocketChannel channel) {
            pendingConnections.add(channel);
            selector.wakeup();
        }

        void wakeup() { selector.wakeup(); }

        void cleanup() {
            try { selector.close(); } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            int idleCount = 0;

            try {
                while (running.get()) {
                    // Register pending connections
                    registerPending();

                    // Select with adaptive strategy
                    int ready;
                    if (busyPoll && idleCount < 1000) {
                        ready = selector.selectNow();
                    } else {
                        ready = selector.select(1);
                    }

                    if (ready == 0) {
                        idleCount++;
                        if (idleCount > 10000) {
                            Thread.onSpinWait();
                        }
                        continue;
                    }
                    idleCount = 0;

                    // Process ready channels
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
            } catch (Exception e) {
                if (running.get()) System.err.println("[Worker] Error: " + e.getMessage());
            }
        }

        private void registerPending() {
            SocketChannel ch;
            while ((ch = pendingConnections.poll()) != null) {
                try {
                    ch.register(selector, SelectionKey.OP_READ);
                } catch (Exception e) {
                    try { ch.close(); } catch (IOException ignored) {}
                }
            }
        }

        private void handleRead(SelectionKey key) {
            SocketChannel channel = (SocketChannel) key.channel();
            try {
                readBuffer.clear();
                int bytesRead = channel.read(readBuffer);

                if (bytesRead <= 0) {
                    key.cancel();
                    channel.close();
                    return;
                }

                // Simple request detection - just look for any data
                // In fire-and-forget mode, we respond immediately
                writeBuffer.rewind();
                while (writeBuffer.hasRemaining()) {
                    channel.write(writeBuffer);
                }
                requests.increment();

            } catch (IOException e) {
                key.cancel();
                try { channel.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();
        boolean busyPoll = args.length <= 2 || Boolean.parseBoolean(args[2]);

        try (Handle handle = ZeroCopyHttpServer.create()
                .workers(workers)
                .busyPoll(busyPoll)
                .start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down...");
            }));
            handle.awaitTermination();
        }
    }
}
