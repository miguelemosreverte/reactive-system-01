package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP/2 Benchmark - Testing multiplexing for 1M+ req/s.
 *
 * HTTP/2 multiplexing allows multiple concurrent streams over a single connection.
 * This should achieve similar throughput to HTTP pipelining, but with a modern,
 * widely-supported protocol.
 *
 * Key insight: HTTP/2 multiplexing is what HTTP pipelining should have been.
 */
public final class Http2Benchmark {

    private static final int WARMUP_SECONDS = 3;
    private static final int BENCHMARK_SECONDS = 10;
    private static final double KAFKA_BASELINE = 1_158_547;

    // Multiplexing configurations to test
    private static final int[] CONCURRENT_STREAMS = {16, 64, 256, 1024};
    private static final int[] CONNECTION_COUNTS = {1, 4, 16};

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  HTTP/2 Multiplexing Benchmark - Target: 1,000,000+ req/s");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  HTTP/2 multiplexing = multiple streams over single connection");
        System.out.println("  This is the modern, standardized approach to high throughput");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Test HTTP/2 Server
        System.out.println("━━━ Testing Http2Server (Netty HTTP/2) ━━━");
        testHttp2Server();

        // Compare with HTTP/1.1 servers
        System.out.println();
        System.out.println("━━━ Comparison: HTTP/1.1 Servers (async client) ━━━");
        testHttp1Servers();
    }

    private static void testHttp2Server() throws Exception {
        Http2Server server = Http2Server.create().workers(4);
        Http2Server.Handle handle = server.start(9999);
        Thread.sleep(200);

        try {
            // Warmup
            System.out.println("  Warming up...");
            runMultiplexedBenchmark("http://localhost:9999/events", 64, 4, WARMUP_SECONDS);

            System.out.println();
            System.out.printf("  %-12s %-12s %15s %10s %10s%n",
                "Connections", "Streams/Conn", "Throughput", "vs Kafka", "Errors");
            System.out.println("  " + "─".repeat(65));

            List<Result> results = new ArrayList<>();

            for (int connections : CONNECTION_COUNTS) {
                for (int streams : CONCURRENT_STREAMS) {
                    Result r = runMultiplexedBenchmark(
                        "http://localhost:9999/events",
                        streams,
                        connections,
                        BENCHMARK_SECONDS
                    );
                    results.add(r);

                    double vsKafka = (r.throughput / KAFKA_BASELINE) * 100;
                    System.out.printf("  %,12d %,12d %,15.0f %9.1f%% %,10d%n",
                        connections, streams, r.throughput, vsKafka, r.errors);
                }
            }

            // Find best result
            Result best = results.stream()
                .max((a, b) -> Double.compare(a.throughput, b.throughput))
                .orElse(null);

            System.out.println("  " + "─".repeat(65));
            if (best != null) {
                System.out.printf("  Best: %,.0f req/s (%.1f%% of Kafka)%n",
                    best.throughput, (best.throughput / KAFKA_BASELINE) * 100);

                if (best.throughput >= 1_000_000) {
                    System.out.println("  ★★★ TARGET ACHIEVED: 1 MILLION+ REQUESTS/SECOND! ★★★");
                }
            }

        } finally {
            handle.close();
            Thread.sleep(200);
        }
    }

    private static void testHttp1Servers() throws Exception {
        // Test UltraFast with async client (to compare fairly)
        System.out.println("  Testing UltraFastHttpServer with async client...");

        UltraFastHttpServer ultra = UltraFastHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());

        UltraFastHttpServer.Handle ultraHandle = ultra.start(9998);
        Thread.sleep(200);

        try {
            runMultiplexedBenchmark("http://localhost:9998/events", 64, 4, WARMUP_SECONDS);
            Result r = runMultiplexedBenchmark("http://localhost:9998/events", 256, 16, BENCHMARK_SECONDS);
            System.out.printf("    Result: %,.0f req/s (%.1f%% of Kafka)%n",
                r.throughput, (r.throughput / KAFKA_BASELINE) * 100);
        } finally {
            ultraHandle.close();
        }

        // Test Rocket with async client
        System.out.println("  Testing RocketHttpServer with async client...");

        RocketHttpServer rocket = RocketHttpServer.create()
            .reactors(4)
            .onBody(buf -> {});

        RocketHttpServer.Handle rocketHandle = rocket.start(9997);
        Thread.sleep(200);

        try {
            runMultiplexedBenchmark("http://localhost:9997/events", 64, 4, WARMUP_SECONDS);
            Result r = runMultiplexedBenchmark("http://localhost:9997/events", 256, 16, BENCHMARK_SECONDS);
            System.out.printf("    Result: %,.0f req/s (%.1f%% of Kafka)%n",
                r.throughput, (r.throughput / KAFKA_BASELINE) * 100);
        } finally {
            rocketHandle.close();
        }
    }

    /**
     * Run benchmark with multiple async requests in flight.
     * For HTTP/2: uses stream multiplexing
     * For HTTP/1.1: uses multiple connections
     */
    private static Result runMultiplexedBenchmark(String url, int concurrentRequests, int connections, int durationSeconds)
            throws Exception {

        // Create multiple HttpClients to get multiple connections
        List<HttpClient> clients = new ArrayList<>();
        for (int i = 0; i < connections; i++) {
            clients.add(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)  // Prefer HTTP/2
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newCachedThreadPool())
                .build());
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"test\",\"ts\":" + System.currentTimeMillis() + "}"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .build();

        LongAdder successCount = new LongAdder();
        LongAdder errorCount = new LongAdder();

        Semaphore inFlight = new Semaphore(concurrentRequests);
        Instant endTime = Instant.now().plusSeconds(durationSeconds);

        // Request generator threads (one per connection)
        ExecutorService executor = Executors.newFixedThreadPool(connections);
        CountDownLatch done = new CountDownLatch(connections);

        for (HttpClient client : clients) {
            executor.submit(() -> {
                try {
                    while (Instant.now().isBefore(endTime)) {
                        if (!inFlight.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                            continue;
                        }

                        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                            .whenComplete((response, error) -> {
                                if (error == null && response.statusCode() < 400) {
                                    successCount.increment();
                                } else {
                                    errorCount.increment();
                                }
                                inFlight.release();
                            });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();

        // Wait for in-flight requests
        Thread.sleep(1000);

        executor.shutdown();

        long total = successCount.sum() + errorCount.sum();
        double throughput = total * 1.0 / durationSeconds;

        return new Result(throughput, errorCount.sum());
    }

    private record Result(double throughput, long errors) {}
}
