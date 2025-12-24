package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Raw socket benchmark for maximum throughput measurement.
 *
 * Uses raw TCP sockets to minimize client overhead:
 * - No HTTP client library overhead
 * - Persistent connections (keep-alive)
 * - Pre-built request bytes
 * - Minimal response reading
 *
 * Each connection does sequential request-response (no pipelining).
 * Total throughput = connections × (1 / round-trip-time)
 */
public final class RawThroughputBenchmark {

    private static final int WARMUP_SECONDS = 3;
    private static final int BENCHMARK_SECONDS = 10;
    private static final double KAFKA_BASELINE = 1_158_547;

    // Test with increasing connection counts
    private static final int[] CONNECTION_COUNTS = {100, 500, 1000, 2000, 4000, 8000};

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
        System.out.println("  Raw Socket Benchmark - Maximum Throughput Measurement");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Method: Raw TCP sockets with keep-alive, no pipelining");
        System.out.println("  Each request waits for response before next (sequential)");
        System.out.println("  Throughput = Connections × Requests-per-Connection-per-Second");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        List<ServerConfig> servers = List.of(
            new ServerConfig("UltraFastHttpServer", () -> startUltraFast(9999)),
            new ServerConfig("RocketHttpServer (4r)", () -> startRocket(9999, 4)),
            new ServerConfig("RawHttpServer (4l)", () -> startRaw(9999, 4))
        );

        for (ServerConfig config : servers) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  Testing: " + config.name);
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            ServerHandle handle = config.starter.start();
            Thread.sleep(300);

            try {
                // Warmup
                System.out.println("  Warming up...");
                runRawBenchmark(9999, 500, WARMUP_SECONDS);

                System.out.println();
                System.out.printf("  %-15s %15s %12s %10s%n",
                    "Connections", "Throughput", "vs Kafka", "Errors");
                System.out.println("  " + "─".repeat(55));

                Result best = null;
                for (int connections : CONNECTION_COUNTS) {
                    Result r = runRawBenchmark(9999, connections, BENCHMARK_SECONDS);

                    double vsKafka = (r.throughput / KAFKA_BASELINE) * 100;
                    System.out.printf("  %,15d %,15.0f %11.1f%% %,10d%n",
                        connections, r.throughput, vsKafka, r.errors);

                    if (best == null || r.throughput > best.throughput) {
                        best = r;
                    }

                    // If too many errors, stop scaling
                    if (r.errors > r.requests * 0.1) {
                        System.out.println("  (stopping - too many errors)");
                        break;
                    }
                }

                System.out.println("  " + "─".repeat(55));
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
                Thread.sleep(200);
            }
            System.out.println();
        }
    }

    private static Result runRawBenchmark(int port, int connectionCount, int durationSeconds) throws Exception {
        LongAdder requestCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(connectionCount);

        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        // Start worker threads
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

                                // Read response (just enough to confirm success)
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
        doneLatch.await(durationSeconds + 5, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        long requests = requestCount.sum();
        double throughput = requests * 1.0 / durationSeconds;

        return new Result(requests, throughput, errorCount.sum());
    }

    // Server starters
    private static ServerHandle startUltraFast(int port) {
        UltraFastHttpServer server = UltraFastHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());
        UltraFastHttpServer.Handle h = server.start(port);
        return h::close;
    }

    private static ServerHandle startRocket(int port, int reactors) {
        RocketHttpServer server = RocketHttpServer.create()
            .reactors(reactors)
            .onBody(buf -> {});
        RocketHttpServer.Handle h = server.start(port);
        return h::close;
    }

    private static ServerHandle startRaw(int port, int loops) {
        RawHttpServer server = RawHttpServer.create(RawHttpServer.Config.defaults().withEventLoops(loops))
            .get("/health", req -> RawHttpServer.Response.ok("{\"status\":\"UP\"}"))
            .post("/events", req -> RawHttpServer.Response.accepted("{\"ok\":true}"));
        RawHttpServer.Handle h = server.start(port);
        return h::close;
    }

    private record Result(long requests, double throughput, long errors) {}

    private record ServerConfig(String name, ServerStarter starter) {}

    @FunctionalInterface
    private interface ServerStarter {
        ServerHandle start() throws Exception;
    }

    @FunctionalInterface
    private interface ServerHandle {
        void close();
    }
}
