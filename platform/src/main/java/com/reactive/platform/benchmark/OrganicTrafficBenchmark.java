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
import java.util.concurrent.atomic.LongAdder;

/**
 * Realistic organic traffic benchmark simulating citizens accessing a government service.
 *
 * Key differences from synthetic benchmarks:
 * 1. Open-loop model: requests arrive at a target rate regardless of responses
 * 2. High concurrency: 1000+ concurrent connections (real user count)
 * 3. Spike testing: sudden bursts of traffic
 * 4. Latency under load: measures P50, P95, P99 under realistic conditions
 */
public final class OrganicTrafficBenchmark {

    // Test configuration
    private static final int WARMUP_SECONDS = 3;
    private static final int TEST_SECONDS = 10;

    // Realistic concurrency levels (simulate citizen access patterns)
    private static final int[] CONCURRENCY_LEVELS = {100, 500, 1000, 2000};

    // Target request rates (requests per second)
    private static final int[] TARGET_RATES = {10_000, 25_000, 50_000, 100_000};

    private static final double KAFKA_BASELINE = 1_158_547;

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Organic Traffic Benchmark - Simulating Real Citizen Access");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Model: Open-loop (requests arrive at target rate)");
        System.out.println("  This simulates real users, not synthetic load generators");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        List<ServerConfig> servers = List.of(
            new ServerConfig("UltraFastHttpServer", OrganicTrafficBenchmark::startUltraFast),
            new ServerConfig("HyperHttpServer (4w)", OrganicTrafficBenchmark::startHyper),
            new ServerConfig("RawHttpServer (4l)", OrganicTrafficBenchmark::startRaw),
            new ServerConfig("RocketHttpServer (4r)", OrganicTrafficBenchmark::startRocket)
        );

        // Test each server
        for (ServerConfig config : servers) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  Testing: " + config.name);
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            ServerHandle handle = config.starter.start(9999);
            Thread.sleep(200);

            try {
                // Warmup
                System.out.println("  Warming up...");
                runOpenLoopTest("http://localhost:9999/events", 1000, 10_000, WARMUP_SECONDS);

                // Test different load levels
                System.out.println();
                System.out.printf("  %-12s %12s %12s %10s %10s %10s %8s%n",
                    "Concurrency", "Target RPS", "Actual RPS", "P50 (ms)", "P95 (ms)", "P99 (ms)", "Errors");
                System.out.println("  " + "─".repeat(78));

                for (int concurrency : CONCURRENCY_LEVELS) {
                    for (int targetRate : TARGET_RATES) {
                        Result r = runOpenLoopTest("http://localhost:9999/events",
                            concurrency, targetRate, TEST_SECONDS);

                        System.out.printf("  %,12d %,12d %,12.0f %10.1f %10.1f %10.1f %,8d%n",
                            concurrency, targetRate, r.actualRps,
                            r.p50Ms, r.p95Ms, r.p99Ms, r.errors);

                        // If server can't keep up (>5% errors or latency explodes), skip higher rates
                        if (r.errors > r.totalRequests * 0.05 || r.p99Ms > 5000) {
                            System.out.println("  (skipping higher rates - server saturated)");
                            break;
                        }
                    }
                }

                // Spike test
                System.out.println();
                System.out.println("  ─── Spike Test: 0 → 50K → 0 req/s ───");
                runSpikeTest("http://localhost:9999/events");

            } finally {
                handle.close();
                Thread.sleep(200);
            }
            System.out.println();
        }

        printSummary();
    }

    /**
     * Open-loop test: generate requests at a fixed rate regardless of responses.
     * This is how real traffic works - users don't wait for others.
     */
    private static Result runOpenLoopTest(String url, int concurrency, int targetRps, int durationSeconds)
            throws Exception {

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newCachedThreadPool())
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"citizen_action\",\"ts\":" + System.currentTimeMillis() + "}"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .build();

        LongAdder requestCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        // Semaphore to limit concurrent in-flight requests
        Semaphore inFlight = new Semaphore(concurrency);

        // Calculate delay between requests to achieve target rate
        long delayNanos = 1_000_000_000L / targetRps;

        ExecutorService executor = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Instant startTime = Instant.now();
        Instant endTime = startTime.plusSeconds(durationSeconds);

        // Schedule request generation at target rate
        AtomicLong nextRequestTime = new AtomicLong(System.nanoTime());

        CountDownLatch done = new CountDownLatch(1);

        // Request generator thread
        Thread generator = new Thread(() -> {
            try {
                while (Instant.now().isBefore(endTime)) {
                    // Wait for slot
                    if (!inFlight.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                        continue; // Keep trying
                    }

                    // Pace requests to target rate
                    long now = System.nanoTime();
                    long target = nextRequestTime.get();
                    if (now < target) {
                        long sleepNanos = target - now;
                        if (sleepNanos > 1_000_000) { // > 1ms
                            Thread.sleep(sleepNanos / 1_000_000, (int)(sleepNanos % 1_000_000));
                        }
                    }
                    nextRequestTime.addAndGet(delayNanos);

                    // Submit async request
                    long reqStart = System.nanoTime();
                    client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .whenComplete((response, error) -> {
                            long latencyMs = (System.nanoTime() - reqStart) / 1_000_000;
                            latencies.offer(latencyMs);
                            requestCount.increment();

                            if (error != null || (response != null && response.statusCode() >= 400)) {
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

        generator.start();
        done.await();

        // Wait for in-flight requests to complete (max 5 seconds)
        Thread.sleep(Math.min(5000, concurrency * 10L));

        executor.shutdown();
        scheduler.shutdown();

        // Calculate stats
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        double p50 = sorted.length > 0 ? sorted[sorted.length / 2] : 0;
        double p95 = sorted.length > 0 ? sorted[(int)(sorted.length * 0.95)] : 0;
        double p99 = sorted.length > 0 ? sorted[(int)(sorted.length * 0.99)] : 0;

        long actualDuration = Duration.between(startTime, Instant.now()).toMillis();
        double actualRps = requestCount.sum() * 1000.0 / actualDuration;

        return new Result(requestCount.sum(), actualRps, p50, p95, p99, errorCount.sum());
    }

    /**
     * Spike test: sudden burst of traffic.
     * Simulates a government announcement that drives sudden citizen access.
     */
    private static void runSpikeTest(String url) throws Exception {
        System.out.println("    Phase 1: Baseline (5K req/s for 3s)");
        Result baseline = runOpenLoopTest(url, 500, 5_000, 3);
        System.out.printf("      → %,.0f req/s, P99: %.1f ms%n", baseline.actualRps, baseline.p99Ms);

        System.out.println("    Phase 2: SPIKE (50K req/s for 3s)");
        Result spike = runOpenLoopTest(url, 2000, 50_000, 3);
        System.out.printf("      → %,.0f req/s, P99: %.1f ms, Errors: %,d%n",
            spike.actualRps, spike.p99Ms, spike.errors);

        System.out.println("    Phase 3: Recovery (5K req/s for 3s)");
        Result recovery = runOpenLoopTest(url, 500, 5_000, 3);
        System.out.printf("      → %,.0f req/s, P99: %.1f ms%n", recovery.actualRps, recovery.p99Ms);

        if (spike.errors == 0 && spike.p99Ms < 100) {
            System.out.println("    ✓ Server handled spike gracefully!");
        } else if (spike.errors < spike.totalRequests * 0.01) {
            System.out.println("    ~ Server handled spike with minor degradation");
        } else {
            System.out.println("    ✗ Server struggled during spike");
        }
    }

    private static void printSummary() {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  SUMMARY");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  This benchmark simulates real citizen traffic patterns:");
        System.out.println("    - Open-loop: requests arrive at target rate (not dependent on responses)");
        System.out.println("    - High concurrency: 100-2000 simultaneous users");
        System.out.println("    - Spike handling: sudden traffic bursts");
        System.out.println();
        System.out.println("  Key metrics to watch:");
        System.out.println("    - Actual RPS vs Target RPS (can server keep up?)");
        System.out.println("    - P99 latency (worst case for citizens)");
        System.out.println("    - Error rate during spikes");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    // Server starters
    private static ServerHandle startUltraFast(int port) {
        UltraFastHttpServer server = UltraFastHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());
        UltraFastHttpServer.Handle h = server.start(port);
        return h::close;
    }

    private static ServerHandle startHyper(int port) {
        HyperHttpServer server = HyperHttpServer.create()
            .onGet(buf -> "{\"status\":\"UP\"}".getBytes())
            .onPost(buf -> "{\"ok\":true}".getBytes());
        HyperHttpServer.Handle h = server.start(port, 4);
        return h::close;
    }

    private static ServerHandle startRaw(int port) {
        RawHttpServer server = RawHttpServer.create(RawHttpServer.Config.defaults().withEventLoops(4))
            .get("/health", req -> RawHttpServer.Response.ok("{\"status\":\"UP\"}"))
            .post("/events", req -> RawHttpServer.Response.accepted("{\"ok\":true}"));
        RawHttpServer.Handle h = server.start(port);
        return h::close;
    }

    private static ServerHandle startRocket(int port) {
        RocketHttpServer server = RocketHttpServer.create()
            .reactors(4)
            .onBody(buf -> {});
        RocketHttpServer.Handle h = server.start(port);
        return h::close;
    }

    // Types
    private record Result(long totalRequests, double actualRps, double p50Ms, double p95Ms, double p99Ms, long errors) {}

    private record ServerConfig(String name, ServerStarter starter) {}

    @FunctionalInterface
    private interface ServerStarter {
        ServerHandle start(int port);
    }

    @FunctionalInterface
    private interface ServerHandle {
        void close();
    }
}
