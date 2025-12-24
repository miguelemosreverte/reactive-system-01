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
 * Multi-Client Benchmark - Run multiple independent client processes.
 *
 * This tests if the server can handle more load than a single client can generate.
 */
public final class MultiClientBenchmark {

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
        System.out.println("  Multi-Client Benchmark - Finding True Server Capacity");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start server with 4 reactors
        TurboHttpServer server = TurboHttpServer.create()
            .reactors(4)
            .onBody(buf -> {});

        TurboHttpServer.Handle handle = server.start(9999);
        Thread.sleep(300);

        try {
            // Warmup
            System.out.println("Warming up...");
            runClientPool(9999, 4, 50, 2);

            // Test: Increase total client threads while keeping connections optimal
            System.out.println();
            System.out.println("Testing client capacity (all clients in parallel):");
            System.out.println("  Client Threads    Conns/Thread    Total Conns    Throughput       Per-Conn");
            System.out.println("  ───────────────────────────────────────────────────────────────────────────");

            // Key insight: Each NIO selector thread can handle many connections efficiently
            // But the selector itself has overhead. More threads = more parallelism but more overhead.

            int[][] configs = {
                // {threads, connsPerThread}
                {1, 400},   // 1 selector with many connections
                {2, 200},   // 2 selectors
                {4, 100},   // 4 selectors
                {4, 200},   // 4 selectors, more connections
                {8, 100},   // 8 selectors
                {8, 200},   // 8 selectors, more connections
                {16, 100},  // 16 selectors
                {16, 200},  // 16 selectors, more connections
                {32, 100},  // 32 selectors
                {64, 50},   // 64 selectors
                {64, 100},  // 64 selectors, more connections
            };

            double best = 0;
            String bestConfig = "";

            for (int[] cfg : configs) {
                int threads = cfg[0];
                int connsPerThread = cfg[1];
                int totalConns = threads * connsPerThread;

                double throughput = runClientPool(9999, threads, connsPerThread, 5);
                double perConn = throughput / totalConns;

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %13d %15d %14d %,14.0f %,14.0f%n",
                    marker, threads, connsPerThread, totalConns, throughput, perConn);

                if (throughput > best) {
                    best = throughput;
                    bestConfig = threads + " threads × " + connsPerThread + " conns";
                }
            }

            System.out.println("  ───────────────────────────────────────────────────────────────────────────");
            System.out.printf("  Best: %s = %,.0f req/s%n", bestConfig, best);

            // Now test with virtual threads (one per connection for maximum parallelism)
            System.out.println();
            System.out.println("Testing with Virtual Threads (one per connection):");
            System.out.println("  Connections    Throughput       Per-Conn");
            System.out.println("  ──────────────────────────────────────────");

            int[] vthreadConns = {100, 200, 400, 800, 1600, 3200};
            double vthreadBest = 0;
            int vthreadBestConns = 0;

            for (int conns : vthreadConns) {
                double throughput = runVirtualThreadClients(9999, conns, 5);
                double perConn = throughput / conns;

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %10d %,14.0f %,14.0f%n",
                    marker, conns, throughput, perConn);

                if (throughput > vthreadBest) {
                    vthreadBest = throughput;
                    vthreadBestConns = conns;
                }
            }

            System.out.println("  ──────────────────────────────────────────");
            System.out.printf("  Best: %d connections = %,.0f req/s%n", vthreadBestConns, vthreadBest);

            // Final summary
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");
            double overallBest = Math.max(best, vthreadBest);
            System.out.printf("  BEST RESULT: %,.0f req/s (%.1f%% of 1M)%n", overallBest, overallBest / 10000);

            if (overallBest >= 1_000_000) {
                System.out.println();
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else {
                System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000.0 / overallBest);
            }
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        } finally {
            handle.close();
        }
    }

    /**
     * Run a pool of NIO client threads.
     */
    private static double runClientPool(int port, int threads, int connsPerThread, int durationSeconds)
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
                    runNioClientOptimized(port, connsPerThread, endTime, requestCount);
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

    /**
     * Optimized NIO client - uses selectNow() for low latency.
     */
    private static void runNioClientOptimized(int port, int connCount, long endTimeMs, LongAdder requestCount)
            throws Exception {
        Selector selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", port);

        ByteBuffer[] writeBuffers = new ByteBuffer[connCount];
        ByteBuffer[] readBuffers = new ByteBuffer[connCount];

        for (int i = 0; i < connCount; i++) {
            writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST.length);
            readBuffers[i] = ByteBuffer.allocateDirect(128);

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(address);
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            key.attach(i);
        }

        int spinCount = 0;
        final int MAX_SPIN = 256;

        while (System.currentTimeMillis() < endTimeMs) {
            int ready;
            if (spinCount < MAX_SPIN) {
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

    /**
     * Run with virtual threads - one per connection.
     */
    private static double runVirtualThreadClients(int port, int connectionCount, int durationSeconds)
            throws Exception {
        LongAdder requestCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(connectionCount);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        byte[] readBuf = new byte[128];

        for (int i = 0; i < connectionCount; i++) {
            executor.submit(() -> {
                byte[] buf = new byte[128];
                try {
                    startLatch.await();

                    java.net.Socket socket = new java.net.Socket("localhost", port);
                    socket.setTcpNoDelay(true);

                    java.io.OutputStream out = socket.getOutputStream();
                    java.io.InputStream in = socket.getInputStream();

                    while (running.get() && System.currentTimeMillis() < endTime) {
                        out.write(REQUEST);
                        out.flush();
                        if (in.read(buf) > 0) {
                            requestCount.increment();
                        } else {
                            break;
                        }
                    }

                    socket.close();
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
}
