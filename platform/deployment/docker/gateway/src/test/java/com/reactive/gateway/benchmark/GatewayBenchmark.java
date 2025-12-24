package com.reactive.gateway.benchmark;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway Benchmark - tests HTTP + Kafka publish (fire-and-forget).
 *
 * Uses /api/counter/fast which publishes to Kafka without waiting for result.
 * This measures gateway HTTP handling + Kafka producer latency only.
 */
public class GatewayBenchmark extends BaseBenchmark {

    private static final Logger log = LoggerFactory.getLogger(GatewayBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HttpClient client;

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static GatewayBenchmark create() {
        return new GatewayBenchmark();
    }

    private GatewayBenchmark() {
        super(ComponentId.GATEWAY);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ========================================================================
    // Benchmark Implementation
    // ========================================================================

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String url = config.gatewayUrl() + "/api/counter/fast";
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
        // Each worker sends a debug request every N operations to capture traces
        // This ensures we get full traces without impacting overall benchmark performance
        // Using 500 to ensure we capture traces even in short benchmarks
        final int DEBUG_REQUEST_INTERVAL = 500;
        int operationCount = 0;

        while (isRunning()) {
            long start = System.currentTimeMillis();
            String requestId = "gateway_" + workerId + "_" + start;
            operationCount++;

            // Send X-Debug header periodically after warmup to capture full traces
            boolean isDebugRequest = Instant.now().isAfter(warmupEnd)
                    && operationCount % DEBUG_REQUEST_INTERVAL == 0;

            try {
                String payload = """
                    {"action": "INCREMENT", "value": 1, "sessionId": "gateway-bench-%d"}
                    """.formatted(workerId);

                var requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");

                // Add X-Debug header for sample requests to get full traces
                if (isDebugRequest) {
                    requestBuilder.header("X-Debug", "true");
                }

                HttpRequest request = requestBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = System.currentTimeMillis() - start;

                recordLatency(latencyMs);

                if (response.statusCode() == 200) {
                    recordSuccess();

                    // Only capture sample events for debug requests (which have full traces)
                    if (isDebugRequest) {
                        try {
                            JsonNode result = mapper.readTree(response.body());
                            String appRequestId = result.path("requestId").asText(null);
                            // X-Debug requests always have full traces - capture the trace ID
                            String otelTraceId = result.path("otelTraceId").asText(null);

                            ComponentTiming timing = new ComponentTiming(latencyMs, 0, 0, 0);
                            addSampleEvent(SampleEvent.success(requestId, appRequestId, otelTraceId, latencyMs)
                                    .withTiming(timing));
                            log.debug("Captured debug trace: {}", otelTraceId);
                        } catch (Exception e) {
                            addSampleEvent(SampleEvent.success(requestId, null, null, latencyMs));
                        }
                    }
                } else {
                    recordFailure();
                    if (isDebugRequest) {
                        addSampleEvent(SampleEvent.error(requestId, null, null, latencyMs,
                                "HTTP " + response.statusCode()));
                    }
                }

            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                recordLatency(latencyMs);
                recordFailure();
                if (isDebugRequest) {
                    addSampleEvent(SampleEvent.error(requestId, null, null, latencyMs, e.getMessage()));
                }
            }
        }
    }

    // ========================================================================
    // Main Entry Point
    // ========================================================================

    public static void main(String[] args) throws IOException {
        if (args.length < 6) {
            System.err.println("Usage: GatewayBenchmark <durationMs> <concurrency> <gatewayUrl> <droolsUrl> <reportsDir> <skipEnrichment>");
            System.exit(1);
        }

        long durationMs = Long.parseLong(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        String gatewayUrl = args[2];
        String droolsUrl = args[3];
        String reportsDir = args[4];
        boolean skipEnrichment = Boolean.parseBoolean(args[5]);

        log.info("Starting Gateway Benchmark: duration={}ms, concurrency={}, url={}",
                durationMs, concurrency, gatewayUrl);

        Config config = Config.builder()
                .durationMs(durationMs)
                .concurrency(concurrency)
                .gatewayUrl(gatewayUrl)
                .droolsUrl(droolsUrl)
                .skipEnrichment(skipEnrichment)
                .build();

        GatewayBenchmark benchmark = GatewayBenchmark.create();
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
