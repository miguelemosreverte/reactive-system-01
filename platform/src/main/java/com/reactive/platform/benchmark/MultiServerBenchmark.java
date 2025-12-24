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
import java.util.concurrent.atomic.LongAdder;

/**
 * Multi-Server Benchmark - Test if multiple server instances scale linearly.
 *
 * Theory: If single server saturates at 580K, can 2 servers do 1.16M?
 * This tests horizontal scaling within a single machine.
 */
public final class MultiServerBenchmark {

    private static final String POST_BODY = "{\"e\":1}";
    private static final byte[] REQUEST_TEMPLATE = (
        "POST /e HTTP/1.1\r\n" +
        "Host: l\r\n" +
        "Content-Length: " + POST_BODY.length() + "\r\n" +
        "\r\n" +
        POST_BODY
    ).getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Multi-Server Benchmark - Testing Horizontal Scaling");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test 1: Single server baseline (2 reactors to leave room for more servers)
        System.out.println("TEST 1: Single Server Baseline (2 reactors)");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");

        TurboHttpServer.Handle server1 = startServer(9991, 2);
        Thread.sleep(200);

        double single = runCombinedBenchmark(new int[]{9991}, 4, 100, 8);
        System.out.printf("  1 server × 2 reactors: %,.0f req/s%n", single);

        server1.close();
        Thread.sleep(500);

        // Test 2: Two servers
        System.out.println();
        System.out.println("TEST 2: Two Servers (2 reactors each)");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");

        server1 = startServer(9991, 2);
        TurboHttpServer.Handle server2 = startServer(9992, 2);
        Thread.sleep(200);

        double dual = runCombinedBenchmark(new int[]{9991, 9992}, 4, 100, 8);
        double scaleFactor2 = dual / single;
        System.out.printf("  2 servers × 2 reactors: %,.0f req/s (%.2fx vs single)%n", dual, scaleFactor2);

        server1.close();
        server2.close();
        Thread.sleep(500);

        // Test 3: Four servers (1 reactor each)
        System.out.println();
        System.out.println("TEST 3: Four Servers (1 reactor each)");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");

        TurboHttpServer.Handle[] servers = new TurboHttpServer.Handle[4];
        int[] ports = {9991, 9992, 9993, 9994};
        for (int i = 0; i < 4; i++) {
            servers[i] = startServer(ports[i], 1);
        }
        Thread.sleep(200);

        double quad = runCombinedBenchmark(ports, 4, 100, 8);
        double scaleFactor4 = quad / single;
        System.out.printf("  4 servers × 1 reactor: %,.0f req/s (%.2fx vs single)%n", quad, scaleFactor4);

        for (TurboHttpServer.Handle s : servers) s.close();
        Thread.sleep(500);

        // Test 4: Find optimal configuration
        System.out.println();
        System.out.println("TEST 4: Optimal Configuration Search");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────");
        System.out.println("  Servers    Reactors/Srv    Total Reactors    Throughput    Efficiency");
        System.out.println("  ─────────────────────────────────────────────────────────────────────");

        int[][] configs = {
            {1, 4},  // 1 server, 4 reactors
            {2, 2},  // 2 servers, 2 reactors each
            {4, 1},  // 4 servers, 1 reactor each
            {1, 2},  // 1 server, 2 reactors
            {2, 1},  // 2 servers, 1 reactor each
        };

        double best = 0;
        String bestConfig = "";

        for (int[] cfg : configs) {
            int numServers = cfg[0];
            int reactorsPerServer = cfg[1];
            int totalReactors = numServers * reactorsPerServer;

            TurboHttpServer.Handle[] testServers = new TurboHttpServer.Handle[numServers];
            int[] testPorts = new int[numServers];
            for (int i = 0; i < numServers; i++) {
                testPorts[i] = 9991 + i;
                testServers[i] = startServer(testPorts[i], reactorsPerServer);
            }
            Thread.sleep(200);

            double throughput = runCombinedBenchmark(testPorts, 8, 100, 5);
            double efficiency = throughput / (single * numServers) * 100;

            System.out.printf("  %7d %14d %17d %,13.0f %12.1f%%%n",
                numServers, reactorsPerServer, totalReactors, throughput, efficiency);

            if (throughput > best) {
                best = throughput;
                bestConfig = numServers + " servers × " + reactorsPerServer + " reactors";
            }

            for (TurboHttpServer.Handle s : testServers) s.close();
            Thread.sleep(300);
        }

        System.out.println("  ─────────────────────────────────────────────────────────────────────");
        System.out.printf("  Best: %s = %,.0f req/s%n", bestConfig, best);

        // Summary
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  BEST RESULT: %,.0f req/s%n", best);

        if (best >= 1_000_000) {
            System.out.println();
            System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
            System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
            System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
        } else {
            double linearProjection = single * 4;  // If 4 servers scaled linearly
            System.out.printf("  Linear projection (4 servers): %,.0f req/s%n", linearProjection);
            System.out.printf("  Actual efficiency: %.1f%%%n", (best / linearProjection) * 100);
            System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000.0 / best);

            // Estimate servers needed
            double perServer = best / 4;  // Assuming 4 servers was best
            int serversNeeded = (int) Math.ceil(1_000_000.0 / perServer);
            System.out.printf("  Estimated servers for 1M: %d%n", serversNeeded);
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    private static TurboHttpServer.Handle startServer(int port, int reactors) {
        TurboHttpServer server = TurboHttpServer.create()
            .reactors(reactors)
            .onBody(buf -> {});
        return server.start(port);
    }

    /**
     * Run benchmark against multiple servers simultaneously.
     */
    private static double runCombinedBenchmark(int[] ports, int threadsPerPort, int connsPerThread, int durationSeconds)
            throws Exception {
        int totalThreads = ports.length * threadsPerPort;
        LongAdder requestCount = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        // Start clients for each port
        for (int port : ports) {
            for (int t = 0; t < threadsPerPort; t++) {
                final int targetPort = port;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        runNioClient(targetPort, connsPerThread, endTime, requestCount);
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
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
            writeBuffers[i] = ByteBuffer.allocateDirect(REQUEST_TEMPLATE.length);
            readBuffers[i] = ByteBuffer.allocateDirect(128);

            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.connect(address);
            SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
            key.attach(i);
        }

        int spinCount = 0;

        while (System.currentTimeMillis() < endTimeMs) {
            int ready;
            if (spinCount < 256) {
                ready = selector.selectNow();
            } else {
                ready = selector.select(1);
                spinCount = 0;
            }

            if (ready == 0) {
                spinCount++;
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
                        wb.put(REQUEST_TEMPLATE);
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
