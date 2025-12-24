package com.reactive.platform.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Pure HTTP Server Benchmark - tests HTTP endpoint throughput in isolation.
 *
 * This establishes the baseline: "HTTP server can do X req/s"
 * Tests a simple endpoint (like /health) with no business logic.
 *
 * Usage:
 *   HttpServerBenchmark.run("http://localhost:3000/health", 10_000, 8);
 */
public class HttpServerBenchmark {

    private static final Logger log = LoggerFactory.getLogger(HttpServerBenchmark.class);

    public record Result(
            long totalRequests,
            long successfulRequests,
            long durationMs,
            double requestsPerSecond,
            double avgLatencyMs,
            double p50LatencyMs,
            double p99LatencyMs,
            long errors
    ) {
        @Override
        public String toString() {
            return String.format(
                    "HttpServerBenchmark: %,d reqs in %,dms = %,.0f req/s (avg=%.1fms, p50=%.1fms, p99=%.1fms, errors=%d)",
                    totalRequests, durationMs, requestsPerSecond, avgLatencyMs, p50LatencyMs, p99LatencyMs, errors);
        }
    }

    /**
     * Run pure HTTP server benchmark.
     *
     * @param url         URL to benchmark (e.g., http://localhost:3000/health)
     * @param durationMs  How long to run (milliseconds)
     * @param concurrency Number of concurrent request threads
     * @return Benchmark result
     */
    public static Result run(String url, long durationMs, int concurrency) {
        log.info("Starting HTTP Server Benchmark: url={}, duration={}ms, concurrency={}",
                url, durationMs, concurrency);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(concurrency * 2))
                .build();

        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(Duration.ofMillis(durationMs));

        // Start worker threads
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    while (Instant.now().isBefore(endTime)) {
                        long start = System.currentTimeMillis();
                        try {
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build();

                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long latency = System.currentTimeMillis() - start;

                            requestCount.incrementAndGet();
                            latencies.offer(latency);

                            if (response.statusCode() == 200) {
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

        // Start all threads
        startLatch.countDown();

        // Wait for completion
        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long actualDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        // Calculate statistics
        LongSummaryStatistics stats = latencies.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        // Calculate percentiles
        long[] sortedLatencies = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sortedLatencies.length > 0
                ? sortedLatencies[sortedLatencies.length / 2]
                : 0;
        long p99 = sortedLatencies.length > 0
                ? sortedLatencies[(int) (sortedLatencies.length * 0.99)]
                : 0;

        // Cleanup
        executor.shutdown();

        long total = requestCount.get();
        double reqPerSec = (total * 1000.0) / actualDurationMs;

        Result result = new Result(
                total,
                successCount.get(),
                actualDurationMs,
                reqPerSec,
                stats.getAverage(),
                p50,
                p99,
                errorCount.get()
        );

        log.info("Benchmark complete: {}", result);
        return result;
    }

    /**
     * Run POST benchmark (for testing with body).
     */
    public static Result runPost(String url, String body, long durationMs, int concurrency) {
        log.info("Starting HTTP POST Benchmark: url={}, duration={}ms, concurrency={}",
                url, durationMs, concurrency);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(concurrency * 2))
                .build();

        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(Duration.ofMillis(durationMs));

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    while (Instant.now().isBefore(endTime)) {
                        long start = System.currentTimeMillis();
                        try {
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build();

                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long latency = System.currentTimeMillis() - start;

                            requestCount.incrementAndGet();
                            latencies.offer(latency);

                            if (response.statusCode() == 200 || response.statusCode() == 202) {
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

        startLatch.countDown();

        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long actualDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        LongSummaryStatistics stats = latencies.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        long[] sortedLatencies = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sortedLatencies.length > 0 ? sortedLatencies[sortedLatencies.length / 2] : 0;
        long p99 = sortedLatencies.length > 0 ? sortedLatencies[(int) (sortedLatencies.length * 0.99)] : 0;

        executor.shutdown();

        long total = requestCount.get();
        double reqPerSec = (total * 1000.0) / actualDurationMs;

        Result result = new Result(total, successCount.get(), actualDurationMs, reqPerSec,
                stats.getAverage(), p50, p99, errorCount.get());

        log.info("Benchmark complete: {}", result);
        return result;
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:3000/health";
        long durationMs = args.length > 1 ? Long.parseLong(args[1]) : 10_000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        String body = args.length > 3 ? args[3] : null; // Optional POST body

        Result result;
        if (body != null && !body.isEmpty()) {
            result = runPost(url, body, durationMs, concurrency);
        } else {
            result = run(url, durationMs, concurrency);
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("HTTP SERVER BENCHMARK RESULT");
        System.out.println("=".repeat(60));
        System.out.printf("Total Requests:  %,d%n", result.totalRequests());
        System.out.printf("Successful:      %,d%n", result.successfulRequests());
        System.out.printf("Duration:        %,d ms%n", result.durationMs());
        System.out.printf("Throughput:      %,.0f req/s%n", result.requestsPerSecond());
        System.out.printf("Avg Latency:     %.1f ms%n", result.avgLatencyMs());
        System.out.printf("P50 Latency:     %.1f ms%n", result.p50LatencyMs());
        System.out.printf("P99 Latency:     %.1f ms%n", result.p99LatencyMs());
        System.out.printf("Errors:          %d%n", result.errors());
        System.out.println("=".repeat(60));
    }
}
