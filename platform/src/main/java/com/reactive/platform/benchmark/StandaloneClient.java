package com.reactive.platform.benchmark;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * Standalone client for split client/server benchmarking.
 */
public final class StandaloneClient {

    private static final String POST_BODY = "{\"e\":1}";
    private static final byte[] REQUEST = buildRequest();

    private static byte[] buildRequest() {
        return ("POST /e HTTP/1.1\r\n" +
                "Host: l\r\n" +
                "Content-Length: " + POST_BODY.length() + "\r\n" +
                "\r\n" +
                POST_BODY).getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9999;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        int usersPerThread = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        int durationSeconds = args.length > 4 ? Integer.parseInt(args[4]) : 10;

        int totalUsers = threads * usersPerThread;

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Standalone Client Benchmark");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  Target:    %s:%d%n", host, port);
        System.out.printf("  Threads:   %d%n", threads);
        System.out.printf("  Users/Thd: %d%n", usersPerThread);
        System.out.printf("  Total:     %d concurrent users%n", totalUsers);
        System.out.printf("  Duration:  %d seconds%n", durationSeconds);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Warmup
        System.out.println("Warming up...");
        runBenchmark(host, port, threads, usersPerThread, 3);

        // Benchmark
        System.out.println("Running benchmark...");
        double throughput = runBenchmark(host, port, threads, usersPerThread, durationSeconds);

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  Result: %,.0f req/s%n", throughput);
        System.out.printf("  Per-user: %.0f req/s%n", throughput / totalUsers);

        if (throughput >= 1_000_000) {
            System.out.println();
            System.out.println("  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★");
        } else {
            System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000 / throughput);
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    private static double runBenchmark(String host, int port, int clientThreads, int usersPerThread, int durationSeconds)
            throws Exception {

        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService executor = Executors.newFixedThreadPool(clientThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clientThreads);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        for (int t = 0; t < clientThreads; t++) {
            executor.submit(() -> {
                try {
                    runClientThread(host, port, usersPerThread, running, endTime, requestCount, startLatch);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(durationSeconds + 10, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        return requestCount.sum() * 1.0 / durationSeconds;
    }

    private static void runClientThread(String host, int port, int userCount, AtomicBoolean running,
                                         Instant endTime, LongAdder requestCount,
                                         CountDownLatch startLatch) {
        try {
            startLatch.await();

            Selector selector = Selector.open();
            InetSocketAddress address = new InetSocketAddress(host, port);

            ByteBuffer[] writeBuffers = new ByteBuffer[userCount];
            ByteBuffer[] readBuffers = new ByteBuffer[userCount];
            SocketChannel[] channels = new SocketChannel[userCount];

            for (int i = 0; i < userCount; i++) {
                writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
                readBuffers[i] = ByteBuffer.allocateDirect(256);

                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(address);

                SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
                key.attach(i);
                channels[i] = channel;
            }

            while (running.get() && Instant.now().isBefore(endTime)) {
                int ready = selector.select(1);
                if (ready == 0) continue;

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    int userIdx = (Integer) key.attachment();
                    SocketChannel channel = (SocketChannel) key.channel();

                    try {
                        if (key.isConnectable()) {
                            if (channel.finishConnect()) {
                                channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                                key.interestOps(SelectionKey.OP_WRITE);
                            }
                        } else if (key.isWritable()) {
                            ByteBuffer wb = writeBuffers[userIdx];
                            wb.clear();
                            wb.put(REQUEST);
                            wb.flip();
                            while (wb.hasRemaining()) {
                                channel.write(wb);
                            }
                            key.interestOps(SelectionKey.OP_READ);
                        } else if (key.isReadable()) {
                            ByteBuffer rb = readBuffers[userIdx];
                            rb.clear();
                            int read = channel.read(rb);
                            if (read > 0) {
                                requestCount.increment();
                                key.interestOps(SelectionKey.OP_WRITE);
                            } else if (read < 0) {
                                key.cancel();
                                channel.close();
                            }
                        }
                    } catch (IOException e) {
                        key.cancel();
                        try { channel.close(); } catch (IOException ignored) {}
                    }
                }
            }

            Arrays.stream(channels)
                .filter(Objects::nonNull)
                .forEach(ch -> { try { ch.close(); } catch (IOException ignored) {} });
            selector.close();

        } catch (Exception e) {
            // Thread error
        }
    }
}
