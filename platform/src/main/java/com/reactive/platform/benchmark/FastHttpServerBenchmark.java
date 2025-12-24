package com.reactive.platform.benchmark;

import com.reactive.platform.http.HttpServer;
import com.reactive.platform.http.HttpServer.*;
import com.reactive.platform.http.Json;
import com.reactive.platform.http.NettyHttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LongSummaryStatistics;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark for FastHttpServer.
 *
 * Compares our minimal HTTP server against Spring baseline.
 * Target: Match Kafka producer throughput (~1M req/s).
 */
public final class FastHttpServerBenchmark {

    public record Result(
            long totalRequests,
            long successfulRequests,
            long durationMs,
            double requestsPerSecond,
            double avgLatencyMicros,
            double p50LatencyMicros,
            double p99LatencyMicros,
            long errors
    ) {
        @Override
        public String toString() {
            return String.format(
                    "FastHttpServer: %,d reqs in %,dms = %,.0f req/s (avg=%.1fµs, p50=%.1fµs, p99=%.1fµs, errors=%d)",
                    totalRequests, durationMs, requestsPerSecond, avgLatencyMicros, p50LatencyMicros, p99LatencyMicros, errors);
        }
    }

    /**
     * Run benchmark against FastHttpServer.
     */
    public static Result run(long durationMs, int concurrency, boolean postMode) {
        return run(durationMs, concurrency, postMode, false);
    }

    /**
     * Run benchmark with server type selection.
     */
    public static Result run(long durationMs, int concurrency, boolean postMode, boolean useNetty) {
        // Start server with test endpoints
        HttpServer server = useNetty
                ? NettyHttpServer.createNetty()
                : HttpServer.create();

        server
                .get("/health", Handler.sync(req ->
                        Response.ok("{\"status\":\"UP\"}")))
                .post("/events", Handler.sync(req -> {
                    // Parse JSON, do minimal work
                    var json = Json.parse(req.body());
                    return Response.accepted(Json.stringify(
                            "accepted", true,
                            "action", json.getOrDefault("action", "unknown")
                    ));
                }));

        Handle handle = server.start(9999);

        try {
            // Give server time to start
            Thread.sleep(100);

            String url = postMode
                    ? "http://localhost:9999/events"
                    : "http://localhost:9999/health";
            String body = postMode
                    ? "{\"action\":\"increment\",\"value\":1}"
                    : null;

            return runBenchmark(url, body, durationMs, concurrency);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            handle.close();
        }
    }

    private static Result runBenchmark(String url, String body, long durationMs, int concurrency) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newCachedThreadPool())
                .build();

        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(Duration.ofMillis(durationMs));

        // Start workers
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    while (Instant.now().isBefore(endTime)) {
                        long start = System.nanoTime();
                        try {
                            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(10));

                            if (body != null) {
                                reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body))
                                        .header("Content-Type", "application/json");
                            } else {
                                reqBuilder.GET();
                            }

                            HttpResponse<String> response = client.send(
                                    reqBuilder.build(),
                                    HttpResponse.BodyHandlers.ofString());

                            long latencyMicros = (System.nanoTime() - start) / 1000;
                            requestCount.incrementAndGet();
                            latencies.offer(latencyMicros);

                            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            requestCount.incrementAndGet();
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Go!
        startLatch.countDown();

        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long actualDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        // Calculate stats
        LongSummaryStatistics stats = latencies.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sorted.length > 0 ? sorted[sorted.length / 2] : 0;
        long p99 = sorted.length > 0 ? sorted[(int) (sorted.length * 0.99)] : 0;

        executor.shutdown();

        return new Result(
                requestCount.get(),
                successCount.get(),
                actualDurationMs,
                (requestCount.get() * 1000.0) / actualDurationMs,
                stats.getAverage(),
                p50,
                p99,
                errorCount.get()
        );
    }

    public static void main(String[] args) {
        long durationMs = args.length > 0 ? Long.parseLong(args[0]) : 10_000;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        boolean postMode = args.length > 2 && "post".equalsIgnoreCase(args[2]);
        boolean useNetty = args.length > 3 && "netty".equalsIgnoreCase(args[3]);

        String serverType = useNetty ? "NETTY" : "NIO+VIRTUAL_THREADS";

        System.out.println("============================================================");
        System.out.println("HTTP SERVER BENCHMARK: " + serverType);
        System.out.println("============================================================");
        System.out.printf("Duration: %dms | Concurrency: %d | Mode: %s%n",
                durationMs, concurrency, postMode ? "POST" : "GET");
        System.out.println("============================================================");
        System.out.println();

        // Warmup
        System.out.println("Warming up...");
        run(2000, concurrency, postMode, useNetty);

        System.out.println("Running benchmark...");
        Result result = run(durationMs, concurrency, postMode, useNetty);

        System.out.println();
        System.out.println("============================================================");
        System.out.println("RESULT");
        System.out.println("============================================================");
        System.out.printf("Total Requests:  %,d%n", result.totalRequests());
        System.out.printf("Successful:      %,d%n", result.successfulRequests());
        System.out.printf("Duration:        %,d ms%n", result.durationMs());
        System.out.printf("Throughput:      %,.0f req/s%n", result.requestsPerSecond());
        System.out.printf("Avg Latency:     %.1f µs%n", result.avgLatencyMicros());
        System.out.printf("P50 Latency:     %.1f µs%n", result.p50LatencyMicros());
        System.out.printf("P99 Latency:     %.1f µs%n", result.p99LatencyMicros());
        System.out.printf("Errors:          %d%n", result.errors());
        System.out.println("============================================================");

        // Comparison with baselines
        System.out.println();
        System.out.println("COMPARISON:");
        System.out.printf("  vs Spring (10,623 req/s):  %.1fx %s%n",
                result.requestsPerSecond() / 10623,
                result.requestsPerSecond() > 10623 ? "FASTER" : "slower");
        if (!useNetty) {
            System.out.printf("  vs Netty (53,453 req/s):   %.1fx %s%n",
                    result.requestsPerSecond() / 53453,
                    result.requestsPerSecond() > 53453 ? "FASTER" : "slower");
        }
    }
}
