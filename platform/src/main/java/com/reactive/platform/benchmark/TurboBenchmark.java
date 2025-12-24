package com.reactive.platform.benchmark;

import com.reactive.platform.http.TurboHttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark for TurboHttpServer - optimized selector handling.
 */
public final class TurboBenchmark {

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
        System.out.println("  TurboBenchmark - Optimized Selector Handling");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.println("  Optimizations:");
        System.out.println("    ✓ selectNow() when busy (no 1ms wait)");
        System.out.println("    ✓ select(1) only when idle");
        System.out.println("    ✓ Spin-loop strategy");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test different reactor counts
        int[] reactorCounts = {1, 2, 4, 6, 8};

        for (int reactors : reactorCounts) {
            System.out.printf("Testing with %d reactors...%n", reactors);

            TurboHttpServer server = TurboHttpServer.create()
                .reactors(reactors)
                .onBody(buf -> {});

            TurboHttpServer.Handle handle = server.start(9999);
            Thread.sleep(300);

            try {
                // Warmup
                runBenchmark(9999, 4, 50, 2);

                // Test different client configs
                double best = 0;
                String bestConfig = "";

                int[][] configs = {
                    {4, 50},   // 200 connections
                    {8, 50},   // 400 connections
                    {8, 100},  // 800 connections
                    {16, 50},  // 800 connections
                    {4, 100},  // 400 connections
                };

                for (int[] cfg : configs) {
                    double throughput = runBenchmark(9999, cfg[0], cfg[1], 5);
                    if (throughput > best) {
                        best = throughput;
                        bestConfig = cfg[0] + "t×" + cfg[1] + "c";
                    }
                }

                double perReactor = best / reactors;
                double linearEfficiency = (perReactor / (best / reactors)) * 100;

                System.out.printf("  %d reactors: %,12.0f req/s  (%,.0f per reactor)  [%s]%n",
                    reactors, best, perReactor, bestConfig);

                // Print per-reactor distribution
                long[] perReactorCounts = handle.perReactorCounts();
                long total = Arrays.stream(perReactorCounts).sum();
                StringBuilder dist = new StringBuilder("    Distribution: ");
                for (int i = 0; i < perReactorCounts.length; i++) {
                    double pct = (perReactorCounts[i] * 100.0) / total;
                    dist.append(String.format("R%d:%.0f%% ", i, pct));
                }
                System.out.println(dist);
                System.out.println();

            } finally {
                handle.close();
            }

            Thread.sleep(500);
        }

        // Final test with optimal config
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  FINAL TEST - Finding Maximum Throughput");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        TurboHttpServer server = TurboHttpServer.create()
            .reactors(4)
            .onBody(buf -> {});

        TurboHttpServer.Handle handle = server.start(9999);
        Thread.sleep(300);

        try {
            // Extended test with various configs
            int[][] configs = {
                {4, 25},
                {4, 50},
                {4, 75},
                {4, 100},
                {8, 25},
                {8, 50},
                {8, 75},
                {8, 100},
                {12, 50},
                {16, 50},
            };

            System.out.println("  Threads × Conns/Thd = Total     Throughput      Per-Conn");
            System.out.println("  ─────────────────────────────────────────────────────────");

            double best = 0;
            String bestConfig = "";

            for (int[] cfg : configs) {
                int threads = cfg[0];
                int connsPerThread = cfg[1];
                int total = threads * connsPerThread;

                double throughput = runBenchmark(9999, threads, connsPerThread, 8);
                double perConn = throughput / total;

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %5d × %5d = %5d %,14.0f %,13.0f%n",
                    marker, threads, connsPerThread, total, throughput, perConn);

                if (throughput > best) {
                    best = throughput;
                    bestConfig = threads + "×" + connsPerThread;
                }
            }

            System.out.println("  ─────────────────────────────────────────────────────────");
            System.out.printf("  Best: %s = %,.0f req/s%n", bestConfig, best);

            if (best >= 1_000_000) {
                System.out.println();
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                System.out.println();
                System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000.0 / best);
            }

        } finally {
            handle.close();
        }
    }

    private static double runBenchmark(int port, int threads, int connsPerThread, int durationSeconds)
            throws Exception {
        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    runNioClient(port, connsPerThread, endTime, requestCount);
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(durationSeconds + 5, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        return requestCount.sum() * 1.0 / durationSeconds;
    }

    private static void runNioClient(int port, int connCount, long endTimeMs, LongAdder requestCount)
            throws Exception {
        Selector selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", port);

        ByteBuffer[] writeBuffers = new ByteBuffer[connCount];
        ByteBuffer[] readBuffers = new ByteBuffer[connCount];

        for (int i = 0; i < connCount; i++) {
            writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
            readBuffers[i] = ByteBuffer.allocateDirect(256);

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(address);
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            key.attach(i);
        }

        int consecutiveEmpty = 0;

        while (System.currentTimeMillis() < endTimeMs) {
            int ready;
            if (consecutiveEmpty < 256) {
                ready = selector.selectNow();
            } else {
                ready = selector.select(1);
                consecutiveEmpty = 0;
            }

            if (ready == 0) {
                consecutiveEmpty++;
                continue;
            }

            consecutiveEmpty = 0;
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) continue;

                int idx = (Integer) key.attachment();
                SocketChannel channel = (SocketChannel) key.channel();

                try {
                    if (key.isConnectable() && channel.finishConnect()) {
                        key.interestOps(SelectionKey.OP_WRITE);
                    } else if (key.isWritable()) {
                        ByteBuffer wb = writeBuffers[idx];
                        wb.clear();
                        wb.put(REQUEST);
                        wb.flip();
                        while (wb.hasRemaining()) {
                            if (channel.write(wb) == 0) Thread.onSpinWait();
                        }
                        key.interestOps(SelectionKey.OP_READ);
                    } else if (key.isReadable()) {
                        ByteBuffer rb = readBuffers[idx];
                        rb.clear();
                        if (channel.read(rb) > 0) {
                            requestCount.increment();
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    }
                } catch (IOException e) {
                    key.cancel();
                }
            }
        }

        selector.close();
    }
}
