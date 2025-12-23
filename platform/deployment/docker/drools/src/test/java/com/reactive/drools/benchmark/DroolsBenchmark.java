package com.reactive.drools.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drools Benchmark - tests direct Drools rule evaluation via HTTP.
 *
 * NOTE: In CQRS architecture, Drools processes global snapshots periodically
 * (decoupled from main event flow). This benchmark measures raw capacity,
 * not a bottleneck.
 */
public class DroolsBenchmark extends BaseBenchmark {

    private static final Logger log = LoggerFactory.getLogger(DroolsBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HttpClient client;

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static DroolsBenchmark create() {
        return new DroolsBenchmark();
    }

    private DroolsBenchmark() {
        super(ComponentId.DROOLS);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ========================================================================
    // Benchmark Implementation
    // ========================================================================

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String url = config.droolsUrl() + "/api/evaluate";
        Instant loopStart = Instant.now();
        Instant warmupEnd = loopStart.plusMillis(config.warmupMs());

        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency());
        AtomicBoolean warmupComplete = new AtomicBoolean(false);
        AtomicLong lastOpsCount = new AtomicLong(0);

        // Start throughput sampler
        Thread throughputSampler = new Thread(() -> {
            while (isRunning()) {
                try {
                    Thread.sleep(1000);
                    if (warmupComplete.get()) {
                        long currentOps = getOperationCount();
                        long throughput = currentOps - lastOpsCount.getAndSet(currentOps);
                        recordThroughputSample(throughput);
                        log.info("Progress: ops={}, throughput={}/s", currentOps, throughput);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        throughputSampler.setDaemon(true);
        throughputSampler.start();

        try {
            // Start workers
            for (int i = 0; i < config.concurrency(); i++) {
                int workerId = i;
                executor.submit(() -> runWorker(workerId, url, config, warmupEnd));
            }

            // Wait for warmup
            long warmupRemaining = warmupEnd.toEpochMilli() - System.currentTimeMillis();
            if (warmupRemaining > 0) {
                Thread.sleep(warmupRemaining);
            }
            warmupComplete.set(true);
            log.info("Warmup complete, starting measurements");

            // Wait for duration
            long durationRemaining = loopStart.plusMillis(config.durationMs()).toEpochMilli() - System.currentTimeMillis();
            if (durationRemaining > 0 && isRunning()) {
                Thread.sleep(durationRemaining);
            }

            // Stop
            stop();
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);

        } finally {
            throughputSampler.interrupt();
            executor.shutdownNow();
        }
    }

    private void runWorker(int workerId, String url, Config config, Instant warmupEnd) {
        while (isRunning()) {
            long start = System.currentTimeMillis();
            String requestId = "drools_" + workerId + "_" + start;

            try {
                String payload = """
                    {"sessionId": "drools-bench-%d", "currentValue": %d}
                    """.formatted(workerId, 100 + workerId);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = System.currentTimeMillis() - start;

                recordLatency(latencyMs);

                if (response.statusCode() == 200) {
                    recordSuccess();

                    if (Instant.now().isAfter(warmupEnd)) {
                        try {
                            JsonNode result = mapper.readTree(response.body());
                            // traceId from Drools is the OpenTelemetry trace ID
                            String otelTraceId = result.path("traceId").asText(null);

                            ComponentTiming timing = new ComponentTiming(0, 0, 0, latencyMs);
                            // Store as otelTraceId for ObservabilityFetcher to query Jaeger
                            addSampleEvent(SampleEvent.success(requestId, null, otelTraceId, latencyMs)
                                    .withTiming(timing));
                        } catch (Exception e) {
                            addSampleEvent(SampleEvent.success(requestId, null, null, latencyMs));
                        }
                    }
                } else {
                    recordFailure();
                    addSampleEvent(SampleEvent.error(requestId, null, null, latencyMs,
                            "HTTP " + response.statusCode()));
                }

            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                recordLatency(latencyMs);
                recordFailure();
                addSampleEvent(SampleEvent.error(requestId, null, null, latencyMs, e.getMessage()));
            }
        }
    }

    // ========================================================================
    // CLI Entry Point
    // ========================================================================

    public static void main(String[] args) throws IOException {
        if (args.length < 6) {
            System.err.println("Usage: DroolsBenchmark <durationMs> <concurrency> <gatewayUrl> <droolsUrl> <reportsDir> <skipEnrichment>");
            System.exit(1);
        }

        long durationMs = Long.parseLong(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        String gatewayUrl = args[2];
        String droolsUrl = args[3];
        String reportsDir = args[4];
        boolean skipEnrichment = Boolean.parseBoolean(args[5]);

        log.info("Starting Drools Benchmark: duration={}ms, concurrency={}, url={}",
                durationMs, concurrency, droolsUrl);

        Config config = Config.builder()
                .durationMs(durationMs)
                .concurrency(concurrency)
                .gatewayUrl(gatewayUrl)
                .droolsUrl(droolsUrl)
                .skipEnrichment(skipEnrichment)
                .build();

        DroolsBenchmark benchmark = create();
        BenchmarkResult result = benchmark.run(config);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Path resultsPath = Path.of(reportsDir, "results.json");
        Files.createDirectories(resultsPath.getParent());
        Files.writeString(resultsPath, json);

        log.info("Benchmark complete: {} ops, {} success, {} failed",
                result.totalOperations(), result.successfulOperations(), result.failedOperations());
    }
}
