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
 * Virtual Thread Benchmark - Using Java 21 virtual threads for maximum concurrency.
 *
 * Virtual threads have minimal overhead (~1KB stack vs 1MB for platform threads).
 * This allows us to create thousands of concurrent connections without thread pool overhead.
 *
 * Key advantage: Each virtual thread is cheap, so we can have one per connection
 * without worrying about thread pool size limits.
 */
public final class VirtualThreadBenchmark {

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
        System.out.println("  Virtual Thread Benchmark - Java 21 Lightweight Threads");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Method: Virtual threads (Project Loom), one per connection");
        System.out.println("  Each connection does sequential request-response (no pipelining)");
        System.out.println("  Virtual threads: ~1KB overhead vs ~1MB for platform threads");
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
            // Test with increasing connection counts
            int[] connectionCounts = {500, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 10000};

            System.out.println("  Warming up...");
            runVirtualThreadBenchmark(9999, 1000, WARMUP_SECONDS);

            System.out.println();
            System.out.printf("  %-15s %15s %12s %10s%n",
                "Virtual Threads", "Throughput", "vs Kafka", "Errors");
            System.out.println("  " + "─".repeat(55));

            Result best = null;
            for (int connections : connectionCounts) {
                Result r = runVirtualThreadBenchmark(9999, connections, BENCHMARK_SECONDS);

                double vsKafka = (r.throughput / KAFKA_BASELINE) * 100;
                System.out.printf("  %,15d %,15.0f %11.1f%% %,10d%n",
                    connections, r.throughput, vsKafka, r.errors);

                if (best == null || r.throughput > best.throughput) {
                    best = r;
                }

                // If throughput is decreasing significantly, stop
                if (best.throughput > r.throughput * 1.2) {
                    break;
                }
            }

            System.out.println("  " + "─".repeat(55));
            if (best != null) {
                System.out.printf("  Peak: %,.0f req/s (%.1f%% of Kafka)%n",
                    best.throughput, (best.throughput / KAFKA_BASELINE) * 100);

                if (best.throughput >= 1_000_000) {
                    System.out.println();
                    System.out.println("  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★");
                } else {
                    double gap = 1_000_000 / best.throughput;
                    System.out.printf("  Gap to 1M: %.2fx%n", gap);
                    System.out.println();
                    System.out.println("  Analysis:");
                    System.out.printf("    - Each connection: %.0f req/s%n", best.throughput / connectionCounts[0]);
                    System.out.printf("    - Round-trip time: %.2f ms%n", 1000.0 / (best.throughput / connectionCounts[0]));
                    System.out.println("    - Bottleneck: TCP round-trip latency");
                    System.out.println("    - Solution: HTTP/2 multiplexing or batch API");
                }
            }

        } finally {
            handle.close();
        }
    }

    private static Result runVirtualThreadBenchmark(int port, int connectionCount, int durationSeconds)
            throws Exception {

        LongAdder requestCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);

        // Use virtual thread executor
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(connectionCount);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        // Spawn one virtual thread per connection
        for (int i = 0; i < connectionCount; i++) {
            executor.submit(() -> {
                byte[] responseBuffer = new byte[256];

                try {
                    startLatch.await();

                    try (Socket socket = new Socket("localhost", port)) {
                        socket.setTcpNoDelay(true);
                        socket.setSoTimeout(5000);
                        socket.setKeepAlive(true);

                        OutputStream out = socket.getOutputStream();
                        InputStream in = socket.getInputStream();

                        while (running.get() && Instant.now().isBefore(endTime)) {
                            try {
                                // Send request
                                out.write(REQUEST);
                                out.flush();

                                // Read response
                                int read = in.read(responseBuffer);
                                if (read > 0) {
                                    requestCount.increment();
                                } else {
                                    errorCount.increment();
                                    break;
                                }
                            } catch (IOException e) {
                                errorCount.increment();
                                break;
                            }
                        }
                    }
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
        doneLatch.await(durationSeconds + 10, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        long requests = requestCount.sum();
        double throughput = requests * 1.0 / durationSeconds;

        return new Result(requests, throughput, errorCount.sum());
    }

    private record Result(long requests, double throughput, long errors) {}
}
