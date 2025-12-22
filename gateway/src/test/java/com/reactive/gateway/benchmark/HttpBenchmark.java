package com.reactive.gateway.benchmark;

import com.reactive.platform.benchmark.BaseBenchmark;
import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP Benchmark - tests gateway HTTP endpoint latency.
 *
 * Tests the health endpoint to measure pure HTTP overhead.
 */
public class HttpBenchmark extends BaseBenchmark {

    private final HttpClient client;

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static HttpBenchmark create() {
        return new HttpBenchmark();
    }

    private HttpBenchmark() {
        super(ComponentId.HTTP);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ========================================================================
    // Benchmark Implementation
    // ========================================================================

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String healthUrl = config.gatewayUrl() + "/health";
        Instant loopStart = Instant.now();

        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());
        AtomicInteger activeRequests = new AtomicInteger(0);

        try {
            while (shouldContinue(config, loopStart)) {
                // Wait if we have too many active requests
                while (activeRequests.get() >= config.concurrency() * 2) {
                    Thread.sleep(1);
                }

                activeRequests.incrementAndGet();
                executor.submit(() -> {
                    try {
                        long start = System.currentTimeMillis();

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(healthUrl))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();

                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long latency = System.currentTimeMillis() - start;

                        if (response.statusCode() == 200) {
                            recordSuccess();
                            recordLatency(latency);

                            // Sample successful events (rarely)
                            if (getOperationCount() % 1000 == 1) {
                                String requestId = "http-" + System.currentTimeMillis();
                                addSampleEvent(SampleEvent.success(requestId, null, null, latency));
                            }
                        } else {
                            recordFailure();
                            String requestId = "http-" + System.currentTimeMillis();
                            addSampleEvent(SampleEvent.error(requestId, null, null, latency,
                                    "Status: " + response.statusCode()));
                        }
                    } catch (Exception e) {
                        recordFailure();
                        String requestId = "http-" + System.currentTimeMillis();
                        addSampleEvent(SampleEvent.error(requestId, null, null, 0, e.getMessage()));
                    } finally {
                        activeRequests.decrementAndGet();
                    }
                });
            }

            // Wait for remaining requests
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);

        } finally {
            executor.shutdownNow();
        }
    }
}
