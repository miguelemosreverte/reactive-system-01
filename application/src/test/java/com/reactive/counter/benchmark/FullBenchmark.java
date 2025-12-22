package com.reactive.counter.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Full End-to-End Benchmark.
 *
 * Tests complete pipeline: HTTP → Kafka → Flink → Drools
 */
public class FullBenchmark extends BaseBenchmark {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient client;

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static FullBenchmark create() {
        return new FullBenchmark();
    }

    private FullBenchmark() {
        super(ComponentId.FULL);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ========================================================================
    // Benchmark Implementation
    // ========================================================================

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String url = config.gatewayUrl() + "/api/counter";
        Instant loopStart = Instant.now();
        Instant warmupEnd = loopStart.plusMillis(config.warmupMs());
        Instant sampleCutoff = loopStart.plusMillis(config.durationMs() - 5000); // Stop sampling 5s before end

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
                executor.submit(() -> runWorker(workerId, url, config, warmupEnd, sampleCutoff));
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

    private void runWorker(int workerId, String url, Config config, Instant warmupEnd, Instant sampleCutoff) {
        while (isRunning()) {
            long start = System.currentTimeMillis();
            String requestId = "full_" + workerId + "_" + start;

            try {
                String payload = """
                    {"action": "increment", "value": 1, "sessionId": "full-bench-%d"}
                    """.formatted(workerId);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long latencyMs = System.currentTimeMillis() - start;
                Instant now = Instant.now();

                boolean collectSamples = now.isAfter(warmupEnd) && now.isBefore(sampleCutoff);

                recordLatency(latencyMs);

                if (response.statusCode() == 200) {
                    recordSuccess();

                    if (collectSamples) {
                        try {
                            JsonNode result = mapper.readTree(response.body());
                            String traceId = result.path("requestId").asText(null);
                            String otelTraceId = result.path("otelTraceId").asText(null);
                            long processingTimeMs = result.path("result").path("processingTimeMs").asLong(0);

                            ComponentTiming timing = new ComponentTiming(
                                    latencyMs - processingTimeMs, // gateway
                                    0, // kafka (included in flink)
                                    processingTimeMs, // flink
                                    0  // drools (included in flink)
                            );

                            addSampleEvent(SampleEvent.success(requestId, traceId, otelTraceId, latencyMs)
                                    .withTiming(timing));
                        } catch (Exception e) {
                            addSampleEvent(SampleEvent.success(requestId, null, null, latencyMs));
                        }
                    }
                } else {
                    recordFailure();
                    if (collectSamples) {
                        addSampleEvent(SampleEvent.error(requestId, null, null, latencyMs,
                                "HTTP " + response.statusCode()));
                    }
                }

            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                recordLatency(latencyMs);
                recordFailure();

                Instant now = Instant.now();
                boolean collectSamples = now.isAfter(warmupEnd) && now.isBefore(sampleCutoff);
                if (collectSamples) {
                    addSampleEvent(SampleEvent.error(requestId, null, null, latencyMs, e.getMessage()));
                }
            }
        }
    }
}
