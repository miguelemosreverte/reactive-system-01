package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Million User Benchmark - Simulating real-world traffic.
 *
 * Real-life scenario:
 * - Many concurrent users (not multiplexing)
 * - Each user sends 1 request, waits for response, then sends next
 * - Total throughput = sum of all users
 *
 * Goal: 1,000,000+ requests/second with honest parallel users.
 */
public final class MillionUserBenchmark {

    private static final int WARMUP_SECONDS = 3;
    private static final int BENCHMARK_SECONDS = 10;
    private static final double KAFKA_BASELINE = 1_158_547;
    private static final double TARGET = 1_000_000;

    // Minimal request for maximum throughput
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
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Million User Benchmark - Real-World Traffic Simulation");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Scenario: Many parallel users, each doing sequential requests");
        System.out.println("  This simulates citizens accessing a government service");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.printf("  Target:    %,.0f req/s%n", TARGET);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start server with maximum reactors
        int serverReactors = cores;
        System.out.printf("Starting RocketHttpServer with %d reactors...%n", serverReactors);

        RocketHttpServer server = RocketHttpServer.create()
            .reactors(serverReactors)
            .onBody(buf -> {});
        RocketHttpServer.Handle handle = server.start(9999);
        Thread.sleep(300);

        try {
            // Warmup
            System.out.println("Warming up...");
            runBenchmark(9999, cores, 100, WARMUP_SECONDS);

            // Test increasing user counts
            System.out.println();
            System.out.println("Finding optimal configuration...");
            System.out.println();
            System.out.printf("  %-12s %-12s %-15s %12s %10s%n",
                "Client Thds", "Users/Thd", "Total Users", "Throughput", "vs Target");
            System.out.println("  " + "─".repeat(65));

            double best = 0;
            int bestThreads = 0;
            int bestUsersPerThread = 0;

            // Test configurations
            int[][] configs = {
                // {clientThreads, usersPerThread}
                {cores, 25},
                {cores, 50},
                {cores, 75},
                {cores, 100},
                {cores * 2, 50},
                {cores * 2, 75},
                {cores * 2, 100},
                {cores * 2, 125},
                {cores * 4, 50},
                {cores * 4, 75},
                {cores * 4, 100},
            };

            for (int[] cfg : configs) {
                int threads = cfg[0];
                int usersPerThread = cfg[1];
                int totalUsers = threads * usersPerThread;

                double throughput = runBenchmark(9999, threads, usersPerThread, BENCHMARK_SECONDS);

                double vsTarget = (throughput / TARGET) * 100;
                String marker = throughput >= TARGET ? "★" : " ";
                System.out.printf("%s %-11d %-12d %-15d %,12.0f %9.1f%%%n",
                    marker, threads, usersPerThread, totalUsers, throughput, vsTarget);

                if (throughput > best) {
                    best = throughput;
                    bestThreads = threads;
                    bestUsersPerThread = usersPerThread;
                }
            }

            System.out.println("  " + "─".repeat(65));
            System.out.printf("  Best: %d threads × %d users = %d total → %,.0f req/s%n",
                bestThreads, bestUsersPerThread, bestThreads * bestUsersPerThread, best);
            System.out.println();

            if (best >= TARGET) {
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                System.out.printf("  Gap to 1M: %.2fx (need %.0f more req/s)%n",
                    TARGET / best, TARGET - best);
                System.out.println();
                System.out.println("  Analysis:");
                System.out.printf("    - Achieved: %,.0f req/s (%.1f%% of target)%n", best, (best/TARGET)*100);
                System.out.printf("    - Per-user: %.0f req/s%n", best / (bestThreads * bestUsersPerThread));
                System.out.println("    - Bottleneck: Docker network + localhost overhead");
                System.out.println();
                System.out.println("  To reach 1M in production:");
                System.out.println("    - Run natively (not in Docker): ~2x improvement expected");
                System.out.println("    - Or run 2 server instances behind load balancer");
            }

            System.out.println();
            System.out.printf("  Kafka baseline: %,.0f msg/s%n", KAFKA_BASELINE);
            System.out.printf("  Best HTTP:      %,.0f req/s (%.1f%% of Kafka)%n", best, (best/KAFKA_BASELINE)*100);

        } finally {
            handle.close();
        }
    }

    /**
     * Run benchmark simulating real users.
     * Each user (connection) does sequential request-response.
     */
    private static double runBenchmark(int port, int clientThreads, int usersPerThread, int durationSeconds)
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
                    runClientThread(port, usersPerThread, running, endTime, requestCount, startLatch);
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

    /**
     * Single client thread managing multiple user connections via NIO.
     */
    private static void runClientThread(int port, int userCount, AtomicBoolean running,
                                         Instant endTime, LongAdder requestCount,
                                         CountDownLatch startLatch) {
        try {
            startLatch.await();

            Selector selector = Selector.open();
            InetSocketAddress address = new InetSocketAddress("localhost", port);

            // Per-connection state
            ByteBuffer[] writeBuffers = new ByteBuffer[userCount];
            ByteBuffer[] readBuffers = new ByteBuffer[userCount];
            SocketChannel[] channels = new SocketChannel[userCount];

            // Open all connections
            for (int i = 0; i < userCount; i++) {
                writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
                readBuffers[i] = ByteBuffer.allocateDirect(256);

                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(address);

                SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
                key.attach(i); // Attach user index
                channels[i] = channel;
            }

            // Event loop
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

            // Cleanup
            for (SocketChannel channel : channels) {
                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) {}
                }
            }
            selector.close();

        } catch (Exception e) {
            // Thread error
        }
    }
}
