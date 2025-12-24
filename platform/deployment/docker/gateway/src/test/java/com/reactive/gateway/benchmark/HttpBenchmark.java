package com.reactive.gateway.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reactive.platform.benchmark.BaseBenchmark;
import com.reactive.platform.benchmark.BenchmarkResult;
import com.reactive.platform.benchmark.BenchmarkTypes.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final Logger log = LoggerFactory.getLogger(HttpBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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

    // ========================================================================
    // Main Entry Point
    // ========================================================================

    public static void main(String[] args) throws IOException {
        if (args.length < 6) {
            System.err.println("Usage: HttpBenchmark <durationMs> <concurrency> <gatewayUrl> <droolsUrl> <reportsDir> <skipEnrichment>");
            System.exit(1);
        }

        long durationMs = Long.parseLong(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        String gatewayUrl = args[2];
        String droolsUrl = args[3];
        String reportsDir = args[4];
        boolean skipEnrichment = Boolean.parseBoolean(args[5]);

        log.info("Starting HTTP Benchmark: duration={}ms, concurrency={}, url={}",
                durationMs, concurrency, gatewayUrl);

        Config config = Config.builder()
                .durationMs(durationMs)
                .concurrency(concurrency)
                .gatewayUrl(gatewayUrl)
                .droolsUrl(droolsUrl)
                .skipEnrichment(skipEnrichment)
                .build();

        HttpBenchmark benchmark = HttpBenchmark.create();
        BenchmarkResult result = benchmark.run(config);

        // Write results
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Path resultsPath = Path.of(reportsDir, "results.json");
        Files.createDirectories(resultsPath.getParent());
        Files.writeString(resultsPath, json);

        log.info("Benchmark complete: throughput={}/s, latencyP50={}ms, ops={}",
                result.avgThroughput(), result.latency().p50(), result.totalOperations());
    }
}
