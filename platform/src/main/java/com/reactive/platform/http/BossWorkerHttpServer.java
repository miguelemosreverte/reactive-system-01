package com.reactive.platform.http;

import com.reactive.platform.base.Result;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * BossWorkerHttpServer - App-layer routing for linear scaling.
 *
 * Architecture (like Netty/Kafka):
 * - 1 Boss thread: accepts connections, distributes to workers
 * - N Worker threads: handle I/O for their assigned connections
 * - Round-robin distribution at app layer (no kernel SO_REUSEPORT)
 */
public final class BossWorkerHttpServer {

    private static final byte[] RESPONSE = "HTTP/1.1 202 OK\r\nContent-Length: 0\r\n\r\n".getBytes();

    private int workerCount;

    private BossWorkerHttpServer() {
        this.workerCount = Runtime.getRuntime().availableProcessors();
    }

    public static BossWorkerHttpServer create() {
        return new BossWorkerHttpServer();
    }

    public BossWorkerHttpServer workers(int count) {
        this.workerCount = count;
        return this;
    }

    public Handle start(int port) {
        return new ServerHandle(port, workerCount);
    }

    public interface Handle extends AutoCloseable {
        long requestCount();
        long[] perWorkerCounts();
        @Override void close();
    }

    private static final class ServerHandle implements Handle {
        private final Boss boss;
        private final Worker[] workers;
        private final AtomicBoolean running = new AtomicBoolean(true);

        ServerHandle(int port, int workerCount) {
            workers = new Worker[workerCount];

            for (int i = 0; i < workerCount; i++) {
                try {
                    workers[i] = new Worker(i, running);
                    workers[i].start();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to start worker " + i, e);
                }
            }

            try {
                boss = new Boss(port, workers, running);
                boss.start();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start boss", e);
            }

            System.out.printf("[BossWorkerHttpServer] Started 1 boss + %d workers on port %d%n",
                workerCount, port);
        }

        @Override
        public long requestCount() {
            return Arrays.stream(workers)
                .mapToLong(w -> w.requests.sum())
                .sum();
        }

        @Override
        public long[] perWorkerCounts() {
            return Arrays.stream(workers)
                .mapToLong(w -> w.requests.sum())
                .toArray();
        }

        @Override
        public void close() {
            running.set(false);
            boss.wakeup();
            Arrays.stream(workers).forEach(Worker::wakeup);

            Result.join(boss, 1000);
            for (Worker w : workers) Result.join(w, 1000);

            boss.cleanup();
            Arrays.stream(workers).forEach(Worker::cleanup);
            System.out.printf("[BossWorkerHttpServer] Stopped. Total: %,d requests%n", requestCount());
        }
    }

    private static final class Boss extends Thread {
        private final Selector selector;
        private final ServerSocketChannel serverChannel;
        private final Worker[] workers;
        private final AtomicBoolean running;
        private final AtomicInteger nextWorker = new AtomicInteger(0);

        Boss(int port, Worker[] workers, AtomicBoolean running) throws IOException {
            super("boss");
            this.workers = workers;
            this.running = running;

            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(port), 8192);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        }

        void wakeup() { selector.wakeup(); }

        void cleanup() {
            Result.run(serverChannel::close);
            Result.run(selector::close);
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    if (selector.select(100) == 0) continue;

                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (key.isAcceptable()) {
                            SocketChannel client;
                            while ((client = serverChannel.accept()) != null) {
                                client.configureBlocking(false);
                                client.setOption(StandardSocketOptions.TCP_NODELAY, true);

                                int idx = nextWorker.getAndIncrement() % workers.length;
                                workers[idx].addConnection(client);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) System.err.println("[Boss] Error: " + e.getMessage());
            }
        }
    }

    private static final class Worker extends Thread {
        private final Selector selector;
        private final AtomicBoolean running;
        final LongAdder requests = new LongAdder();
        private final Queue<SocketChannel> pendingConnections = new ConcurrentLinkedQueue<>();
        private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(4096);
        private final ByteBuffer writeBuffer;

        Worker(int id, AtomicBoolean running) throws IOException {
            super("worker-" + id);
            this.running = running;
            this.selector = Selector.open();
            this.writeBuffer = ByteBuffer.allocateDirect(RESPONSE.length);
            this.writeBuffer.put(RESPONSE);
        }

        void addConnection(SocketChannel channel) {
            pendingConnections.add(channel);
            selector.wakeup();
        }

        void wakeup() { selector.wakeup(); }

        void cleanup() {
            Result.run(selector::close);
        }

        @Override
        public void run() {
            try {
                while (running.get()) {
                    // Always process pending connections first
                    SocketChannel ch;
                    while ((ch = pendingConnections.poll()) != null) {
                        try {
                            ch.register(selector, SelectionKey.OP_READ);
                        } catch (Exception e) {
                            Result.run(ch::close);
                        }
                    }

                    // Wait for events (with short timeout)
                    int ready = selector.select(1);
                    if (ready == 0) continue;

                    // Process all ready channels
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        if (!key.isValid()) continue;

                        if (key.isReadable()) {
                            SocketChannel channel = (SocketChannel) key.channel();
                            try {
                                readBuffer.clear();
                                int n = channel.read(readBuffer);
                                if (n <= 0) {
                                    key.cancel();
                                    channel.close();
                                    continue;
                                }

                                writeBuffer.rewind();
                                while (writeBuffer.hasRemaining()) {
                                    channel.write(writeBuffer);
                                }
                                requests.increment();

                            } catch (IOException e) {
                                key.cancel();
                                Result.run(channel::close);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (running.get()) System.err.println("[Worker] Error: " + e.getMessage());
            }
        }

        private void processPendingConnections() {
            SocketChannel ch;
            while ((ch = pendingConnections.poll()) != null) {
                try {
                    ch.register(selector, SelectionKey.OP_READ);
                } catch (ClosedChannelException e) {
                    // Channel was closed before we could register
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        try (Handle handle = BossWorkerHttpServer.create().workers(workers).start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            Thread.currentThread().join();
        }
    }
}
