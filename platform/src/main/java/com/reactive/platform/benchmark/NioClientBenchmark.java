package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * NIO-based client benchmark for maximum throughput.
 *
 * Uses Java NIO selectors to manage many connections with few threads:
 * - Each thread handles 100-1000 connections via selector
 * - Non-blocking I/O minimizes overhead
 * - Pre-allocated buffers avoid GC pressure
 *
 * This simulates what a high-performance load balancer or proxy would do.
 */
public final class NioClientBenchmark {

    private static final int WARMUP_SECONDS = 3;
    private static final int BENCHMARK_SECONDS = 10;
    private static final double KAFKA_BASELINE = 1_158_547;

    // Pre-built HTTP request
    private static final String POST_BODY = "{\"event\":\"test\",\"ts\":123456789}";
    private static final byte[] REQUEST = buildRequest();

    private static byte[] buildRequest() {
        return ("POST /events HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + POST_BODY.length() + "\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n" +
                POST_BODY).getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  NIO Client Benchmark - Maximum Throughput with Minimal Threads");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Method: NIO selectors, many connections per thread, no pipelining");
        System.out.println("  Each connection does sequential request-response");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start RocketHttpServer (best performer)
        System.out.println("Testing against RocketHttpServer (4 reactors)...");
        RocketHttpServer server = RocketHttpServer.create()
            .reactors(4)
            .onBody(buf -> {});
        RocketHttpServer.Handle handle = server.start(9999);
        Thread.sleep(300);

        try {
            // Test configurations: (clientThreads, connectionsPerThread)
            int[][] configs = {
                {1, 100},    // 1 thread, 100 connections
                {2, 200},    // 2 threads, 400 total connections
                {4, 250},    // 4 threads, 1000 total connections
                {8, 250},    // 8 threads, 2000 total connections
                {4, 500},    // 4 threads, 2000 total connections
                {8, 500},    // 8 threads, 4000 total connections
                {16, 250},   // 16 threads, 4000 total connections
            };

            System.out.println("  Warming up...");
            runNioBenchmark(9999, 4, 100, WARMUP_SECONDS);

            System.out.println();
            System.out.printf("  %-10s %-15s %-15s %12s %10s%n",
                "Threads", "Conns/Thread", "Total Conns", "Throughput", "vs Kafka");
            System.out.println("  " + "─".repeat(65));

            Result best = null;
            for (int[] cfg : configs) {
                int threads = cfg[0];
                int connsPerThread = cfg[1];
                int totalConns = threads * connsPerThread;

                Result r = runNioBenchmark(9999, threads, connsPerThread, BENCHMARK_SECONDS);

                double vsKafka = (r.throughput / KAFKA_BASELINE) * 100;
                System.out.printf("  %,10d %,15d %,15d %,12.0f %9.1f%%%n",
                    threads, connsPerThread, totalConns, r.throughput, vsKafka);

                if (best == null || r.throughput > best.throughput) {
                    best = r;
                }
            }

            System.out.println("  " + "─".repeat(65));
            if (best != null) {
                System.out.printf("  Peak: %,.0f req/s (%.1f%% of Kafka)%n",
                    best.throughput, (best.throughput / KAFKA_BASELINE) * 100);

                if (best.throughput >= 1_000_000) {
                    System.out.println("  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★");
                } else {
                    System.out.printf("  Gap to 1M: %.1fx%n", 1_000_000 / best.throughput);
                }
            }

        } finally {
            handle.close();
        }
    }

    private static Result runNioBenchmark(int port, int clientThreads, int connectionsPerThread, int durationSeconds)
            throws Exception {

        LongAdder requestCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService executor = Executors.newFixedThreadPool(clientThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clientThreads);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        for (int t = 0; t < clientThreads; t++) {
            executor.submit(() -> {
                try {
                    runClientLoop(port, connectionsPerThread, running, endTime,
                                  requestCount, errorCount, startLatch);
                } catch (Exception e) {
                    errorCount.increment();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion
        doneLatch.await(durationSeconds + 5, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        long requests = requestCount.sum();
        double throughput = requests * 1.0 / durationSeconds;

        return new Result(requests, throughput, errorCount.sum());
    }

    /**
     * Single-threaded NIO client loop managing multiple connections.
     */
    private static void runClientLoop(int port, int connectionCount, AtomicBoolean running,
                                       Instant endTime, LongAdder requestCount, LongAdder errorCount,
                                       CountDownLatch startLatch) throws Exception {

        startLatch.await();

        Selector selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", port);

        // Connection state
        ByteBuffer writeBuffer = ByteBuffer.allocateDirect(REQUEST.length);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(256);

        // Open all connections
        List<SocketChannel> channels = new ArrayList<>();
        for (int i = 0; i < connectionCount; i++) {
            try {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(address);
                channel.register(selector, SelectionKey.OP_CONNECT);
                channels.add(channel);
            } catch (IOException e) {
                errorCount.increment();
            }
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

                SocketChannel channel = (SocketChannel) key.channel();

                try {
                    if (key.isConnectable()) {
                        if (channel.finishConnect()) {
                            // Connected - start writing
                            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } else if (key.isWritable()) {
                        // Send request
                        writeBuffer.clear();
                        writeBuffer.put(REQUEST);
                        writeBuffer.flip();

                        while (writeBuffer.hasRemaining()) {
                            channel.write(writeBuffer);
                        }

                        key.interestOps(SelectionKey.OP_READ);

                    } else if (key.isReadable()) {
                        // Read response
                        readBuffer.clear();
                        int read = channel.read(readBuffer);

                        if (read > 0) {
                            requestCount.increment();
                            // Ready for next request
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else if (read < 0) {
                            // Connection closed
                            key.cancel();
                            channel.close();
                            errorCount.increment();
                        }
                    }
                } catch (IOException e) {
                    key.cancel();
                    try { channel.close(); } catch (IOException ignored) {}
                    errorCount.increment();
                }
            }
        }

        // Cleanup
        for (SocketChannel channel : channels) {
            try { channel.close(); } catch (IOException ignored) {}
        }
        selector.close();
    }

    private record Result(long requests, double throughput, long errors) {}
}
