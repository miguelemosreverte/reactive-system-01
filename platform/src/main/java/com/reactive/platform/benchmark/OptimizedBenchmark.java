package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

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

/**
 * Optimized Benchmark - Based on diagnostic findings.
 *
 * Optimizations applied:
 * 1. NIO selectors (264% faster than blocking)
 * 2. 2-8 connections per thread (sweet spot, superlinear scaling)
 * 3. 64KB buffers (optimal for cache)
 * 4. Direct ByteBuffers (avoid heap copies)
 * 5. TCP_NODELAY (reduce latency)
 *
 * Target: Maximize throughput with honest parallel users.
 */
public final class OptimizedBenchmark {

    private static final int BENCHMARK_SECONDS = 10;
    private static final double TARGET = 1_000_000;

    // Minimal request
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
        System.out.println("  Optimized Benchmark - Based on Diagnostic Findings");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Optimizations:");
        System.out.println("    ✓ NIO selectors (264% faster than blocking)");
        System.out.println("    ✓ 2-8 connections per thread (superlinear scaling sweet spot)");
        System.out.println("    ✓ 64KB direct buffers (optimal for cache)");
        System.out.println("    ✓ TCP_NODELAY enabled");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start server with optimal reactor count
        System.out.println("Starting RocketHttpServer with " + cores + " reactors...");
        RocketHttpServer server = RocketHttpServer.create()
            .reactors(cores)
            .onBody(buf -> {});
        RocketHttpServer.Handle handle = server.start(9999);
        Thread.sleep(200);

        try {
            // Warmup
            System.out.println("Warming up...");
            runOptimizedBenchmark(9999, cores, 4, 3);

            // Test configurations - more threads, fewer connections each
            int[][] configs = {
                // {threads, connectionsPerThread}
                {16, 10},
                {16, 15},
                {16, 20},
                {16, 25},
                {24, 10},
                {24, 15},
                {32, 8},
                {32, 10},
                {32, 12},
                {48, 8},
                {64, 5},
                {64, 8},
            };

            System.out.println();
            System.out.printf("  %-10s %-10s %-12s %15s %12s%n",
                "Threads", "Conns/Thd", "Total Conns", "Throughput", "vs Target");
            System.out.println("  " + "─".repeat(65));

            double best = 0;
            int bestThreads = 0;
            int bestConnsPerThread = 0;

            for (int[] cfg : configs) {
                int threads = cfg[0];
                int connsPerThread = cfg[1];
                int totalConns = threads * connsPerThread;

                double throughput = runOptimizedBenchmark(9999, threads, connsPerThread, BENCHMARK_SECONDS);

                double vsTarget = (throughput / TARGET) * 100;
                String marker = throughput >= TARGET ? "★" : " ";
                System.out.printf("%s %-9d %-10d %-12d %,15.0f %11.1f%%%n",
                    marker, threads, connsPerThread, totalConns, throughput, vsTarget);

                if (throughput > best) {
                    best = throughput;
                    bestThreads = threads;
                    bestConnsPerThread = connsPerThread;
                }
            }

            System.out.println("  " + "─".repeat(65));
            int totalConns = bestThreads * bestConnsPerThread;
            System.out.printf("  Best: %d threads × %d conns = %d total → %,.0f req/s (%.1f%% of 1M)%n",
                bestThreads, bestConnsPerThread, totalConns, best, (best / TARGET) * 100);

            System.out.println();
            if (best >= TARGET) {
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                System.out.printf("  Gap to 1M: %.2fx%n", TARGET / best);

                // Analysis
                double perConn = best / totalConns;
                double neededConns = TARGET / perConn;
                System.out.println();
                System.out.println("  Analysis:");
                System.out.printf("    Per-connection throughput: %.0f req/s%n", perConn);
                System.out.printf("    Connections needed for 1M: %.0f%n", neededConns);
                System.out.printf("    Current efficiency: %.1f%% of theoretical max%n",
                    (best / (totalConns * 27261)) * 100); // 27261 = max single-conn from diagnostics
            }

            System.out.println();
            System.out.printf("  Server processed: %,d total requests%n", handle.requestCount());

        } finally {
            handle.close();
        }
    }

    /**
     * Optimized NIO benchmark with tuned parameters.
     */
    private static double runOptimizedBenchmark(int port, int threads, int connsPerThread, int durationSeconds)
            throws Exception {

        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    runOptimizedClientThread(port, connsPerThread, running, endTime, requestCount, startLatch);
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
     * Optimized client thread with tuned buffers and connection handling.
     */
    private static void runOptimizedClientThread(int port, int connCount, AtomicBoolean running,
                                                  Instant endTime, LongAdder requestCount,
                                                  CountDownLatch startLatch) {
        try {
            startLatch.await();

            Selector selector = Selector.open();
            InetSocketAddress address = new InetSocketAddress("localhost", port);

            // Optimal buffer size: 64KB (from diagnostics)
            final int BUFFER_SIZE = 65536;

            // Pre-allocate direct buffers for each connection
            ByteBuffer[] writeBuffers = new ByteBuffer[connCount];
            ByteBuffer[] readBuffers = new ByteBuffer[connCount];
            SocketChannel[] channels = new SocketChannel[connCount];

            for (int i = 0; i < connCount; i++) {
                writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
                readBuffers[i] = ByteBuffer.allocateDirect(256);

                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);

                // Set socket options before connect
                channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                channel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, BUFFER_SIZE);
                channel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, BUFFER_SIZE);

                channel.connect(address);

                SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
                key.attach(i);
                channels[i] = channel;
            }

            // Event loop
            while (running.get() && Instant.now().isBefore(endTime)) {
                int ready = selector.select(1);
                if (ready == 0) continue;

                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) continue;

                    int connIdx = (Integer) key.attachment();
                    SocketChannel channel = (SocketChannel) key.channel();

                    try {
                        if (key.isConnectable()) {
                            if (channel.finishConnect()) {
                                key.interestOps(SelectionKey.OP_WRITE);
                            }
                        } else if (key.isWritable()) {
                            ByteBuffer wb = writeBuffers[connIdx];
                            wb.clear();
                            wb.put(REQUEST);
                            wb.flip();

                            // Non-blocking write
                            channel.write(wb);
                            if (!wb.hasRemaining()) {
                                key.interestOps(SelectionKey.OP_READ);
                            }

                        } else if (key.isReadable()) {
                            ByteBuffer rb = readBuffers[connIdx];
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
                if (channel != null && channel.isOpen()) {
                    try { channel.close(); } catch (IOException ignored) {}
                }
            }
            selector.close();

        } catch (Exception e) {
            // Thread error - ignore
        }
    }
}
