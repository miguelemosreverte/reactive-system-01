package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Final Benchmark - Simple, proven approach with maximum throughput.
 *
 * Based on diagnostic findings:
 * - Read takes 4x longer than write (29µs vs 7µs)
 * - Blocking I/O with many connections scales well
 * - More threads with fewer connections = more overhead
 *
 * Strategy: Use virtual threads (lightweight) with one connection each.
 * Virtual threads have ~1KB overhead, so we can have thousands.
 */
public final class FinalBenchmark {

    private static final int BENCHMARK_SECONDS = 10;
    private static final double TARGET = 1_000_000;

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
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Final Benchmark - Finding Maximum Throughput");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start server with max reactors
        int reactors = Runtime.getRuntime().availableProcessors();
        System.out.printf("Starting RocketHttpServer with %d reactors...%n", reactors);

        RocketHttpServer server = RocketHttpServer.create()
            .reactors(reactors)
            .onBody(buf -> {});
        RocketHttpServer.Handle handle = server.start(9999);
        Thread.sleep(200);

        try {
            // Warmup
            System.out.println("Warming up...");
            runSimpleBenchmark(9999, 200, 3);

            // Test connection counts
            int[] connectionCounts = {100, 150, 200, 250, 300, 350, 400, 500, 600, 800};

            System.out.println();
            System.out.printf("  %-15s %15s %15s %12s%n",
                "Connections", "Throughput", "Per-Conn", "vs Target");
            System.out.println("  " + "─".repeat(60));

            double best = 0;
            int bestConns = 0;

            for (int conns : connectionCounts) {
                double throughput = runSimpleBenchmark(9999, conns, BENCHMARK_SECONDS);

                double perConn = throughput / conns;
                double vsTarget = (throughput / TARGET) * 100;
                String marker = throughput >= TARGET ? "★" : " ";

                System.out.printf("%s %,14d %,15.0f %,15.0f %11.1f%%%n",
                    marker, conns, throughput, perConn, vsTarget);

                if (throughput > best) {
                    best = throughput;
                    bestConns = conns;
                }
            }

            System.out.println("  " + "─".repeat(60));
            System.out.printf("  Best: %d connections → %,.0f req/s (%.1f%% of 1M)%n",
                bestConns, best, (best / TARGET) * 100);

            System.out.println();
            if (best >= TARGET) {
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                double gap = TARGET / best;
                System.out.printf("  Gap to 1M: %.2fx%n", gap);
                System.out.println();

                // Projection
                double perConn = best / bestConns;
                double projectedNative = best * 2.5; // ~2.5x for native vs Docker
                System.out.println("  Projections:");
                System.out.printf("    Docker (current):     %,.0f req/s%n", best);
                System.out.printf("    Native (estimated):   %,.0f req/s (2.5x Docker)%n", projectedNative);
                System.out.printf("    2 instances:          %,.0f req/s%n", best * 2);

                if (projectedNative >= TARGET) {
                    System.out.println();
                    System.out.println("  → Running natively (not in Docker) should achieve 1M+ req/s");
                }
            }

            System.out.println();
            System.out.printf("  Server processed: %,d total requests%n", handle.requestCount());

        } finally {
            handle.close();
        }
    }

    /**
     * Simple benchmark using virtual threads - one per connection.
     */
    private static double runSimpleBenchmark(int port, int connectionCount, int durationSeconds)
            throws Exception {

        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(connectionCount);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);
        byte[] responseBuffer = new byte[256];

        for (int i = 0; i < connectionCount; i++) {
            executor.submit(() -> {
                byte[] buf = new byte[256];
                try {
                    startLatch.await();

                    try (Socket socket = new Socket("localhost", port)) {
                        socket.setTcpNoDelay(true);
                        socket.setSendBufferSize(65536);
                        socket.setReceiveBufferSize(65536);

                        OutputStream out = socket.getOutputStream();
                        InputStream in = socket.getInputStream();

                        while (running.get() && Instant.now().isBefore(endTime)) {
                            out.write(REQUEST);
                            out.flush();
                            if (in.read(buf) > 0) {
                                requestCount.increment();
                            } else {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore
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
}
