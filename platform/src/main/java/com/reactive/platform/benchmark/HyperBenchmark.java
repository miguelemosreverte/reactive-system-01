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

/**
 * Comprehensive HTTP server benchmark comparing all implementations.
 * Fair comparison: 1 HTTP request = 1 event (no batching tricks).
 */
public final class HyperBenchmark {

    private static final int WARMUP_MS = 3_000;
    private static final int BENCHMARK_MS = 10_000;
    private static final int[] CONCURRENCY_LEVELS = {64};
    private static final double KAFKA_BASELINE = 1_158_547;

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  HTTP Server Comprehensive Benchmark");
        System.out.println("  Fair comparison: 1 HTTP request = 1 Kafka message equivalent");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  Warmup:     %d ms%n", WARMUP_MS);
        System.out.printf("  Benchmark:  %d ms%n", BENCHMARK_MS);
        System.out.printf("  Kafka ref:  %,.0f msg/s%n", KAFKA_BASELINE);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        List<Result> results = new ArrayList<>();

        // Test UltraFastHttpServer (single-threaded, zero-alloc)
        results.add(benchmarkUltraFast());

        // Test HyperHttpServer (multi-core with pipelining)
        results.add(benchmarkHyper(1));
        results.add(benchmarkHyper(4));

        // Test RawHttpServer
        results.add(benchmarkRaw(4));

        // Test RocketHttpServer (multi-reactor)
        results.add(benchmarkRocket(1));
        results.add(benchmarkRocket(4));

        // Print final comparison
        printResults(results);
    }

    private static Result benchmarkUltraFast() throws Exception {
        System.out.println("Testing: UltraFastHttpServer (single-threaded)...");

        UltraFastHttpServer server = UltraFastHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());

        UltraFastHttpServer.Handle handle = server.start(9990);
        Thread.sleep(100);

        try {
            String url = "http://localhost:9990/health";

            // Warmup
            runBenchmark(url, WARMUP_MS, 64);

            // Benchmark
            long[] results = runBenchmark(url, BENCHMARK_MS, 64);
            double throughput = (results[0] * 1000.0) / BENCHMARK_MS;
            double latencyUs = results[2] / 1000.0;

            return new Result("UltraFastHttpServer (1 thread)", throughput, latencyUs, results[1]);
        } finally {
            handle.close();
            Thread.sleep(100);
        }
    }

    private static Result benchmarkHyper(int workers) throws Exception {
        String name = "HyperHttpServer (" + workers + " worker" + (workers > 1 ? "s" : "") + ")";
        System.out.println("Testing: " + name + "...");

        HyperHttpServer server = HyperHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());

        HyperHttpServer.Handle handle = server.start(9991, workers);
        Thread.sleep(100);

        try {
            String url = "http://localhost:9991/health";

            // Warmup
            runBenchmark(url, WARMUP_MS, 64);

            // Benchmark
            long[] results = runBenchmark(url, BENCHMARK_MS, 64);
            double throughput = (results[0] * 1000.0) / BENCHMARK_MS;
            double latencyUs = results[2] / 1000.0;

            return new Result(name, throughput, latencyUs, results[1]);
        } finally {
            handle.close();
            Thread.sleep(100);
        }
    }

    private static Result benchmarkRaw(int loops) throws Exception {
        String name = "RawHttpServer (" + loops + " loops)";
        System.out.println("Testing: " + name + "...");

        RawHttpServer server = RawHttpServer.create(RawHttpServer.Config.defaults().withEventLoops(loops))
            .get("/health", req -> RawHttpServer.Response.ok("{\"status\":\"UP\"}"))
            .post("/events", req -> RawHttpServer.Response.accepted("{\"ok\":true}"));

        RawHttpServer.Handle handle = server.start(9992);
        Thread.sleep(100);

        try {
            String url = "http://localhost:9992/health";

            // Warmup
            runBenchmark(url, WARMUP_MS, 64);

            // Benchmark
            long[] results = runBenchmark(url, BENCHMARK_MS, 64);
            double throughput = (results[0] * 1000.0) / BENCHMARK_MS;
            double latencyUs = results[2] / 1000.0;

            return new Result(name, throughput, latencyUs, results[1]);
        } finally {
            handle.close();
            Thread.sleep(100);
        }
    }

    private static Result benchmarkRocket(int reactors) throws Exception {
        String name = "RocketHttpServer (" + reactors + " reactor" + (reactors > 1 ? "s" : "") + ")";
        System.out.println("Testing: " + name + "...");

        RocketHttpServer server = RocketHttpServer.create()
            .reactors(reactors)
            .onBody(buf -> {}); // No-op handler

        RocketHttpServer.Handle handle = server.start(9993);
        Thread.sleep(200);

        try {
            String url = "http://localhost:9993/events";

            // Warmup
            runBenchmark(url, WARMUP_MS, 64);

            // Benchmark
            long[] results = runBenchmark(url, BENCHMARK_MS, 64);
            double throughput = (results[0] * 1000.0) / BENCHMARK_MS;
            double latencyUs = results[2] / 1000.0;

            return new Result(name, throughput, latencyUs, results[1]);
        } finally {
            handle.close();
            Thread.sleep(100);
        }
    }

    private static long[] runBenchmark(String url, long durationMs, int concurrency) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newCachedThreadPool())
            .build();

        AtomicLong requestCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong totalLatencyNs = new AtomicLong(0);

        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        Instant endTime = Instant.now().plus(Duration.ofMillis(durationMs));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (Instant.now().isBefore(endTime)) {
                        long start = System.nanoTime();
                        try {
                            HttpResponse<String> response = client.send(request,
                                HttpResponse.BodyHandlers.ofString());
                            long elapsed = System.nanoTime() - start;
                            requestCount.incrementAndGet();
                            totalLatencyNs.addAndGet(elapsed);
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
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
        doneLatch.await();
        executor.shutdown();

        long count = requestCount.get();
        long avgLatencyNs = count > 0 ? totalLatencyNs.get() / count : 0;

        return new long[]{count, errorCount.get(), avgLatencyNs};
    }

    private static void printResults(List<Result> results) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  FINAL RESULTS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  %-40s %12s %10s %8s %8s%n", "Server", "Throughput", "Latency", "Errors", "vs Kafka");
        System.out.println("───────────────────────────────────────────────────────────────────────────────");

        Result best = results.stream()
            .max((a, b) -> Double.compare(a.throughput, b.throughput))
            .orElse(null);

        for (Result r : results) {
            String marker = r == best ? "★" : " ";
            double vsKafka = (r.throughput / KAFKA_BASELINE) * 100;
            System.out.printf("%s %-39s %,12.0f %8.0f µs %8d %7.1f%%%n",
                marker, r.name, r.throughput, r.latencyUs, r.errors, vsKafka);
        }

        System.out.println("───────────────────────────────────────────────────────────────────────────────");
        System.out.printf("  Kafka Producer baseline: %,.0f msg/s%n", KAFKA_BASELINE);

        if (best != null) {
            double gap = KAFKA_BASELINE / best.throughput;
            System.out.printf("  Best HTTP throughput:    %,.0f req/s%n", best.throughput);
            System.out.printf("  Gap to Kafka:            %.1fx%n", gap);
        }

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    private record Result(String name, double throughput, double latencyUs, long errors) {}
}
