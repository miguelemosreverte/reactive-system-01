package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive comparison of all HTTP server implementations.
 *
 * Tests:
 * - FastHttpServer (NIO + Virtual Threads)
 * - NettyHttpServer (Netty event loop)
 * - UltraFastHttpServer (Single-threaded, zero-alloc)
 * - RawHttpServer (Multi-threaded, pure Java)
 */
public final class HttpServerComparison {

    private static final byte[] HEALTH_BODY = "{\"status\":\"UP\"}".getBytes();
    private static final byte[] ACCEPTED_BODY = "{\"accepted\":true}".getBytes();

    public record Result(
            String name,
            long requests,
            double throughput,
            double avgLatencyUs,
            double p50LatencyUs,
            double p99LatencyUs,
            long errors
    ) {}

    public static void main(String[] args) {
        long durationMs = args.length > 0 ? Long.parseLong(args[0]) : 10_000;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        boolean postMode = args.length > 2 && "post".equalsIgnoreCase(args[2]);

        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║        HTTP SERVER IMPLEMENTATION COMPARISON                  ║");
        System.out.println("║                Pure Java vs Third-Party                       ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Duration:    %-48d ║%n", durationMs);
        System.out.printf("║  Concurrency: %-48d ║%n", concurrency);
        System.out.printf("║  Mode:        %-48s ║%n", postMode ? "POST" : "GET");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();

        List<Result> results = new ArrayList<>();

        // 1. FastHttpServer (NIO + Virtual Threads)
        System.out.println("━━━ Testing: FastHttpServer (NIO + Virtual Threads) ━━━");
        results.add(benchmarkFastHttpServer(durationMs, concurrency, postMode));

        // 2. NettyHttpServer
        System.out.println("\n━━━ Testing: NettyHttpServer (Netty Event Loop) ━━━");
        results.add(benchmarkNettyHttpServer(durationMs, concurrency, postMode));

        // 3. UltraFastHttpServer (single-threaded)
        System.out.println("\n━━━ Testing: UltraFastHttpServer (Single-Thread, Zero-Alloc) ━━━");
        results.add(benchmarkUltraFastHttpServer(durationMs, concurrency, postMode));

        // 4. RawHttpServer (multi-threaded pure Java)
        System.out.println("\n━━━ Testing: RawHttpServer (Multi-Thread, Pure Java) ━━━");
        results.add(benchmarkRawHttpServer(durationMs, concurrency, postMode, 4));

        // Sort by throughput
        results.sort((a, b) -> Double.compare(b.throughput, a.throughput));

        // Print results
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              RESULTS                                          ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Server                                  Throughput    Avg(µs)  P99(µs)  Err  ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════╣");

        Result best = results.get(0);
        for (Result r : results) {
            String marker = r == best ? "★" : " ";
            System.out.printf("║ %s %-37s %,10.0f    %6.0f   %6.0f   %3d  ║%n",
                    marker, r.name, r.throughput, r.avgLatencyUs, r.p99LatencyUs, r.errors);
        }

        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");

        // Comparison with baselines
        double springBaseline = 10_623;
        double kafkaBaseline = 1_158_547;

        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║                        ANALYSIS                               ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Best:     %-50s ║%n", best.name);
        System.out.printf("║  Throughput: %,10.0f req/s                                  ║%n", best.throughput);
        System.out.printf("║  vs Spring:  %.1fx faster (baseline: %,.0f req/s)            ║%n",
                best.throughput / springBaseline, springBaseline);
        System.out.printf("║  vs Kafka:   %.1f%% of target (%,.0f msg/s)               ║%n",
                (best.throughput / kafkaBaseline) * 100, kafkaBaseline);
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Note: HTTP protocol overhead limits theoretical max.         ║");
        System.out.println("║  For higher throughput, consider:                             ║");
        System.out.println("║    - Batch API (multiple events per request)                  ║");
        System.out.println("║    - HTTP/2 multiplexing                                      ║");
        System.out.println("║    - Direct Kafka (bypass HTTP for internal services)         ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
    }

    private static Result benchmarkFastHttpServer(long durationMs, int concurrency, boolean postMode) {
        HttpServer server = HttpServer.create()
                .get("/health", HttpServer.Handler.sync(req -> HttpServer.Response.ok("{\"status\":\"UP\"}")))
                .post("/events", HttpServer.Handler.sync(req -> HttpServer.Response.accepted("{\"ok\":true}")));

        HttpServer.Handle handle = server.start(9991);
        try {
            Thread.sleep(100);
            return runBenchmark("FastHttpServer (NIO)", "http://localhost:9991", durationMs, concurrency, postMode);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            handle.close();
        }
    }

    private static Result benchmarkNettyHttpServer(long durationMs, int concurrency, boolean postMode) {
        HttpServer server = NettyHttpServer.createNetty()
                .get("/health", HttpServer.Handler.sync(req -> HttpServer.Response.ok("{\"status\":\"UP\"}")))
                .post("/events", HttpServer.Handler.sync(req -> HttpServer.Response.accepted("{\"ok\":true}")));

        HttpServer.Handle handle = server.start(9992);
        try {
            Thread.sleep(100);
            return runBenchmark("NettyHttpServer", "http://localhost:9992", durationMs, concurrency, postMode);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            handle.close();
        }
    }

    private static Result benchmarkUltraFastHttpServer(long durationMs, int concurrency, boolean postMode) {
        UltraFastHttpServer server = UltraFastHttpServer.create()
                .onGet(buf -> HEALTH_BODY)
                .onPost(buf -> ACCEPTED_BODY);

        UltraFastHttpServer.Handle handle = server.start(9993);
        try {
            Thread.sleep(100);
            return runBenchmark("UltraFastHttpServer", "http://localhost:9993", durationMs, concurrency, postMode);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            handle.close();
        }
    }

    private static Result benchmarkRawHttpServer(long durationMs, int concurrency, boolean postMode, int eventLoops) {
        RawHttpServer.Config config = RawHttpServer.Config.defaults().withEventLoops(eventLoops);
        RawHttpServer server = RawHttpServer.create(config)
                .get("/health", req -> RawHttpServer.Response.ok(HEALTH_BODY))
                .post("/events", req -> RawHttpServer.Response.accepted(ACCEPTED_BODY));

        RawHttpServer.Handle handle = server.start(9994);
        try {
            Thread.sleep(100);
            return runBenchmark("RawHttpServer (" + eventLoops + " loops)", "http://localhost:9994", durationMs, concurrency, postMode);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            handle.close();
        }
    }

    private static Result runBenchmark(String name, String baseUrl, long durationMs, int concurrency, boolean postMode) {
        String url = baseUrl + (postMode ? "/events" : "/health");
        String body = postMode ? "{\"action\":\"test\"}" : null;

        // Warmup
        System.out.println("  Warming up...");
        doBenchmark(url, body, 2000, concurrency);

        // Actual benchmark
        System.out.println("  Running...");
        return doBenchmark(url, body, durationMs, concurrency).withName(name);
    }

    private static BenchResult doBenchmark(String url, String body, long durationMs, int concurrency) {
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

        startLatch.countDown();
        try { doneLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        long actualDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        double avg = sorted.length > 0 ? Arrays.stream(sorted).average().orElse(0) : 0;
        long p50 = sorted.length > 0 ? sorted[sorted.length / 2] : 0;
        long p99 = sorted.length > 0 ? sorted[(int) (sorted.length * 0.99)] : 0;

        executor.shutdown();

        return new BenchResult(
                requestCount.get(),
                (requestCount.get() * 1000.0) / actualDurationMs,
                avg, p50, p99,
                errorCount.get()
        );
    }

    private record BenchResult(long requests, double throughput, double avgUs, long p50Us, long p99Us, long errors) {
        Result withName(String name) {
            return new Result(name, requests, throughput, avgUs, p50Us, p99Us, errors);
        }
    }
}
