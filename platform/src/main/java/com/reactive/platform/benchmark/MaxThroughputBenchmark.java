package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Maximum Throughput Benchmark - Finding the absolute ceiling.
 *
 * This benchmark tests various server configurations to find the maximum
 * achievable throughput with HTTP/1.1 sequential requests.
 */
public final class MaxThroughputBenchmark {

    private static final int WARMUP_SECONDS = 3;
    private static final int BENCHMARK_SECONDS = 10;
    private static final double KAFKA_BASELINE = 1_158_547;

    // Pre-built HTTP request
    private static final String POST_BODY = "{\"event\":\"test\"}";
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
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Maximum Throughput Benchmark - Finding the Ceiling");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores:   %d%n", cores);
        System.out.printf("  Benchmark:   %d seconds%n", BENCHMARK_SECONDS);
        System.out.printf("  Kafka ref:   %,.0f msg/s%n", KAFKA_BASELINE);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test configurations: (serverReactors, clientThreads, connectionsPerThread)
        int[][] configs = {
            // Vary server reactors
            {1, 4, 50},    // 1 reactor, 4 clients × 50 = 200 connections
            {2, 4, 50},    // 2 reactors
            {4, 4, 50},    // 4 reactors (match cores)
            {cores, 4, 50}, // cores reactors

            // Vary client config with optimal server
            {4, 2, 100},   // 2 clients × 100 = 200 connections
            {4, 4, 100},   // 4 clients × 100 = 400 connections
            {4, 8, 100},   // 8 clients × 100 = 800 connections
            {4, 4, 200},   // 4 clients × 200 = 800 connections
            {4, 8, 200},   // 8 clients × 200 = 1600 connections
        };

        List<BenchResult> results = new ArrayList<>();

        System.out.printf("  %-10s %-10s %-10s %-12s %12s %10s%n",
            "Reactors", "Clients", "Conns", "Total Conns", "Throughput", "vs Kafka");
        System.out.println("  " + "─".repeat(70));

        for (int[] cfg : configs) {
            int reactors = cfg[0];
            int clientThreads = cfg[1];
            int connsPerThread = cfg[2];
            int totalConns = clientThreads * connsPerThread;

            // Start server
            RocketHttpServer server = RocketHttpServer.create()
                .reactors(reactors)
                .onBody(buf -> {});
            RocketHttpServer.Handle handle = server.start(9999);
            Thread.sleep(200);

            try {
                // Warmup
                runNioBenchmark(9999, 4, 50, WARMUP_SECONDS);

                // Benchmark
                Result r = runNioBenchmark(9999, clientThreads, connsPerThread, BENCHMARK_SECONDS);

                double vsKafka = (r.throughput / KAFKA_BASELINE) * 100;
                System.out.printf("  %,10d %,10d %,10d %,12d %,12.0f %9.1f%%%n",
                    reactors, clientThreads, connsPerThread, totalConns, r.throughput, vsKafka);

                results.add(new BenchResult(reactors, clientThreads, connsPerThread, r.throughput));

            } finally {
                handle.close();
                Thread.sleep(100);
            }
        }

        // Find and print best result
        BenchResult best = results.stream()
            .max((a, b) -> Double.compare(a.throughput, b.throughput))
            .orElse(null);

        System.out.println("  " + "─".repeat(70));
        if (best != null) {
            System.out.printf("  Best: %d reactors, %d×%d connections → %,.0f req/s (%.1f%% of Kafka)%n",
                best.reactors, best.clientThreads, best.connsPerThread,
                best.throughput, (best.throughput / KAFKA_BASELINE) * 100);

            if (best.throughput >= 1_000_000) {
                System.out.println();
                System.out.println("  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★");
            } else {
                System.out.printf("  Gap to 1M: %.2fx%n", 1_000_000 / best.throughput);
            }
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  ANALYSIS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Without HTTP pipelining or HTTP/2 multiplexing, throughput is limited by:");
        System.out.println("    - TCP round-trip latency (~1-2ms in Docker)");
        System.out.println("    - Each connection can only do ~500-4000 req/s");
        System.out.println();
        System.out.println("  To achieve 1M+ req/s, options are:");
        System.out.println("    1. HTTP/2 multiplexing (modern, widely supported)");
        System.out.println("    2. Run outside Docker (lower latency)");
        System.out.println("    3. Multiple load balancer instances");
        System.out.println("    4. io_uring on Linux (kernel-level optimization)");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    /**
     * NIO client benchmark with configurable threads and connections.
     */
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

        startLatch.countDown();
        doneLatch.await(durationSeconds + 5, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        long requests = requestCount.sum();
        double throughput = requests * 1.0 / durationSeconds;

        return new Result(requests, throughput, errorCount.sum());
    }

    private static void runClientLoop(int port, int connectionCount, AtomicBoolean running,
                                       Instant endTime, LongAdder requestCount, LongAdder errorCount,
                                       CountDownLatch startLatch) throws Exception {

        startLatch.await();

        Selector selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", port);

        ByteBuffer writeBuffer = ByteBuffer.allocateDirect(REQUEST.length);
        ByteBuffer readBuffer = ByteBuffer.allocateDirect(256);

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
                            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                            key.interestOps(SelectionKey.OP_WRITE);
                        }
                    } else if (key.isWritable()) {
                        writeBuffer.clear();
                        writeBuffer.put(REQUEST);
                        writeBuffer.flip();
                        while (writeBuffer.hasRemaining()) {
                            channel.write(writeBuffer);
                        }
                        key.interestOps(SelectionKey.OP_READ);
                    } else if (key.isReadable()) {
                        readBuffer.clear();
                        int read = channel.read(readBuffer);
                        if (read > 0) {
                            requestCount.increment();
                            key.interestOps(SelectionKey.OP_WRITE);
                        } else if (read < 0) {
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

        for (SocketChannel channel : channels) {
            try { channel.close(); } catch (IOException ignored) {}
        }
        selector.close();
    }

    private record Result(long requests, double throughput, long errors) {}
    private record BenchResult(int reactors, int clientThreads, int connsPerThread, double throughput) {}
}
