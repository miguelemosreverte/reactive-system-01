package com.reactive.platform.benchmark;

import com.reactive.platform.http.UltraHttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ultra Benchmark - Matching the minimal server with minimal client.
 */
public final class UltraBenchmark {

    // Minimal request (no body)
    private static final byte[] REQUEST = "POST /e HTTP/1.1\r\nHost: l\r\nContent-Length: 0\r\n\r\n".getBytes();

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  UltraBenchmark - Minimal I/O for Maximum Throughput");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.printf("  Request size: %d bytes%n", REQUEST.length);
        System.out.printf("  Response size: 17 bytes%n");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test different reactor counts
        int[] reactorCounts = {1, 2, 4};

        System.out.println("Phase 1: Reactor Scaling");
        System.out.println("  Reactors    Throughput      Per-Reactor    Scaling");
        System.out.println("  ─────────────────────────────────────────────────────");

        double baseline = 0;

        for (int reactors : reactorCounts) {
            UltraHttpServer.Handle handle = startServer(9999, reactors);
            Thread.sleep(200);

            try {
                // Warmup
                runBenchmark(9999, 4, 100, 2);

                // Measure
                double throughput = runBenchmark(9999, 4, 100, 5);
                double perReactor = throughput / reactors;

                if (reactors == 1) baseline = throughput;
                double scaling = throughput / baseline;

                System.out.printf("  %8d %,13.0f %,15.0f %10.2fx%n",
                    reactors, throughput, perReactor, scaling);

            } finally {
                handle.close();
            }
            Thread.sleep(300);
        }

        // Phase 2: Find optimal client configuration
        System.out.println();
        System.out.println("Phase 2: Client Configuration (4 reactors)");
        System.out.println("  Threads    Conns/Thd    Total    Throughput    Per-Conn");
        System.out.println("  ──────────────────────────────────────────────────────────");

        UltraHttpServer.Handle handle = startServer(9999, 4);
        Thread.sleep(200);

        try {
            int[][] configs = {
                {2, 100},
                {4, 50},
                {4, 100},
                {4, 150},
                {4, 200},
                {8, 50},
                {8, 100},
                {8, 150},
                {16, 50},
                {16, 100},
            };

            double best = 0;
            String bestConfig = "";

            for (int[] cfg : configs) {
                int threads = cfg[0];
                int connsPerThread = cfg[1];
                int total = threads * connsPerThread;

                double throughput = runBenchmark(9999, threads, connsPerThread, 5);
                double perConn = throughput / total;

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %7d %11d %8d %,13.0f %,12.0f%n",
                    marker, threads, connsPerThread, total, throughput, perConn);

                if (throughput > best) {
                    best = throughput;
                    bestConfig = threads + " threads × " + connsPerThread + " conns";
                }
            }

            System.out.println("  ──────────────────────────────────────────────────────────");
            System.out.printf("  Best: %s = %,.0f req/s%n", bestConfig, best);

            // Phase 3: Extended test with best config
            System.out.println();
            System.out.println("Phase 3: Extended Test (15 seconds)");

            String[] parts = bestConfig.split(" ");
            int bestThreads = Integer.parseInt(parts[0]);
            int bestConns = Integer.parseInt(parts[3]);

            double extended = runBenchmark(9999, bestThreads, bestConns, 15);
            System.out.printf("  15-second result: %,.0f req/s%n", extended);

            // Summary
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

            if (extended >= 1_000_000) {
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                System.out.printf("  BEST RESULT: %,.0f req/s (%.1f%% of 1M)%n", extended, extended / 10000);
                System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000.0 / extended);
            }
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        } finally {
            handle.close();
        }
    }

    private static UltraHttpServer.Handle startServer(int port, int reactors) {
        return UltraHttpServer.create()
            .reactors(reactors)
            .start(port);
    }

    private static double runBenchmark(int port, int threads, int connsPerThread, int durationSeconds)
            throws Exception {
        LongAdder requestCount = new LongAdder();
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
            readBuffers[i] = ByteBuffer.allocateDirect(64);  // Minimal read buffer

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(address);
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            key.attach(i);
        }

        int spinCount = 0;
        final int SPIN_LIMIT = 1000;

        while (System.currentTimeMillis() < endTimeMs) {
            int ready;
            if (spinCount < SPIN_LIMIT) {
                ready = selector.selectNow();
            } else {
                ready = selector.select(1);
                spinCount = 0;
            }

            if (ready == 0) {
                spinCount++;
                Thread.onSpinWait();
                continue;
            }

            spinCount = 0;
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
                        channel.write(wb);
                        if (!wb.hasRemaining()) {
                            key.interestOps(SelectionKey.OP_READ);
                        }
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
