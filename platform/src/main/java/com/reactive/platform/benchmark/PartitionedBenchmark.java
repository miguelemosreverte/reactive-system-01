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
 * Partitioned Benchmark - Split cores between server and client.
 *
 * With 4 cores:
 * - 2 cores for server (2 reactors)
 * - 2 cores for client (2 NIO threads)
 *
 * This simulates a production scenario where client is remote.
 */
public final class PartitionedBenchmark {

    private static final byte[] REQUEST = "POST /e HTTP/1.1\r\nHost: l\r\nContent-Length: 7\r\n\r\n{\"e\":1}".getBytes();

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Partitioned Benchmark - Simulating Remote Client");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.println("  Allocation: 2 cores server + 2 cores client");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test different partitions
        int[][] partitions = {
            {1, 1},   // 1 server reactor, 1 client thread
            {1, 2},   // 1 server reactor, 2 client threads
            {2, 1},   // 2 server reactors, 1 client thread
            {2, 2},   // 2 server reactors, 2 client threads (even split)
            {3, 1},   // 3 server reactors, 1 client thread
            {1, 3},   // 1 server reactor, 3 client threads
        };

        System.out.println("Testing partitions (connections=200 per client thread):");
        System.out.println("  Srv Reactors    Cli Threads    Total Cores    Throughput");
        System.out.println("  ─────────────────────────────────────────────────────────────");

        double best = 0;
        int bestSrv = 0, bestCli = 0;

        for (int[] p : partitions) {
            int srvReactors = p[0];
            int cliThreads = p[1];
            int totalCores = srvReactors + cliThreads;

            TurboHttpServer.Handle handle = TurboHttpServer.create()
                .reactors(srvReactors)
                .start(9999);
            Thread.sleep(200);

            try {
                // Warmup
                runNioClients(9999, cliThreads, 200, 2);

                // Measure
                double throughput = runNioClients(9999, cliThreads, 200, 5);

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %12d %14d %14d %,14.0f%n",
                    marker, srvReactors, cliThreads, totalCores, throughput);

                if (throughput > best) {
                    best = throughput;
                    bestSrv = srvReactors;
                    bestCli = cliThreads;
                }
            } finally {
                handle.close();
            }
            Thread.sleep(300);
        }

        System.out.println("  ─────────────────────────────────────────────────────────────");
        System.out.printf("  Best partition: %d server + %d client = %,.0f req/s%n",
            bestSrv, bestCli, best);

        // Now optimize connections for best partition
        System.out.println();
        System.out.println("Optimizing connection count for best partition...");

        TurboHttpServer.Handle handle = TurboHttpServer.create()
            .reactors(bestSrv)
            .start(9999);
        Thread.sleep(200);

        try {
            int[] connCounts = {50, 100, 150, 200, 300, 400, 500};

            System.out.println("  Conns/Thread    Total Conns    Throughput    Per-Conn");
            System.out.println("  ─────────────────────────────────────────────────────────");

            double best2 = 0;
            int bestConnPerThread = 0;

            for (int conns : connCounts) {
                double throughput = runNioClients(9999, bestCli, conns, 5);
                int totalConns = bestCli * conns;
                double perConn = throughput / totalConns;

                String marker = throughput >= 1_000_000 ? "★" : " ";
                System.out.printf("%s %12d %14d %,13.0f %,12.0f%n",
                    marker, conns, totalConns, throughput, perConn);

                if (throughput > best2) {
                    best2 = throughput;
                    bestConnPerThread = conns;
                }
            }

            System.out.println("  ─────────────────────────────────────────────────────────");
            System.out.printf("  Optimal: %d conns/thread = %,.0f req/s%n",
                bestConnPerThread, best2);

            // Extended test
            System.out.println();
            System.out.println("Extended test (20 seconds)...");
            double extended = runNioClients(9999, bestCli, bestConnPerThread, 20);
            System.out.printf("  Result: %,.0f req/s%n", extended);

            // Analysis
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");
            System.out.println("  ANALYSIS");
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");
            System.out.printf("  Best result: %,.0f req/s%n", extended);
            System.out.printf("  Server reactors: %d (using %d cores worth of processing)%n", bestSrv, bestSrv);
            System.out.printf("  Client threads: %d%n", bestCli);
            System.out.printf("  Total connections: %d%n", bestCli * bestConnPerThread);
            System.out.println();

            // Projection to production
            double perReactor = extended / bestSrv;
            System.out.println("  Production projections:");
            System.out.printf("    Per-reactor throughput: %,.0f req/s%n", perReactor);
            System.out.printf("    With 4 dedicated server cores: %,.0f req/s%n", perReactor * 4);
            System.out.printf("    With 8 dedicated server cores: %,.0f req/s%n", perReactor * 8);

            if (extended >= 1_000_000) {
                System.out.println();
                System.out.println("  ╔═══════════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★        ║");
                System.out.println("  ╚═══════════════════════════════════════════════════════════════╝");
            } else if (perReactor * 4 >= 1_000_000) {
                System.out.println();
                System.out.println("  ★ Would achieve 1M+ with 4 dedicated server cores (production scenario)");
            }
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        } finally {
            handle.close();
        }
    }

    private static double runNioClients(int port, int threads, int connsPerThread, int durationSeconds)
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
                    runOptimizedNioClient(port, connsPerThread, endTime, requestCount);
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

    private static void runOptimizedNioClient(int port, int connCount, long endTimeMs, LongAdder requestCount)
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
        final int SPIN_LIMIT = 500;

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
