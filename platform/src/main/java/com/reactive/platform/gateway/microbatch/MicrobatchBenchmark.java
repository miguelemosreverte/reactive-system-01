package com.reactive.platform.gateway.microbatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark for MicrobatchingGateway and related components.
 *
 * Tests:
 * 1. MicrobatchCollector throughput (no network)
 * 2. Full gateway throughput (HTTP + Kafka batch)
 * 3. Calibration effectiveness over time
 *
 * Usage:
 *   java MicrobatchBenchmark [mode] [duration_seconds] [concurrency]
 *
 * Modes:
 *   collector - Benchmark MicrobatchCollector only
 *   gateway   - Benchmark full HTTP gateway
 *   compare   - Compare with non-batched gateway
 */
public class MicrobatchBenchmark {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "collector";
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 100;

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    MICROBATCH BENCHMARK                                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Mode:        %s%n", mode);
        System.out.printf("  Duration:    %d seconds%n", durationSec);
        System.out.printf("  Concurrency: %d%n", concurrency);
        System.out.println();

        switch (mode.toLowerCase()) {
            case "collector" -> benchmarkCollector(durationSec, concurrency);
            case "gateway" -> benchmarkGateway(durationSec, concurrency);
            case "calibration" -> benchmarkCalibration(durationSec);
            default -> {
                System.out.println("Unknown mode: " + mode);
                System.out.println("Valid modes: collector, gateway, calibration");
            }
        }
    }

    /**
     * Benchmark MicrobatchCollector throughput (no network overhead).
     * This tests the raw batching overhead without Kafka.
     */
    static void benchmarkCollector(int durationSec, int concurrency) throws Exception {
        System.out.println("=== Collector Benchmark (no network) ===");
        System.out.println();
        System.out.println("Testing raw batching overhead (batch → single callback)");
        System.out.println();

        // Create mock calibration (in-memory)
        Path tempDb = Path.of(System.getProperty("java.io.tmpdir"), "bench-calibration.db");
        BatchCalibration calibration = BatchCalibration.create(tempDb, 5000.0);

        // Track batches received
        LongAdder batchCount = new LongAdder();
        LongAdder itemCount = new LongAdder();
        LongAdder batchSizeSum = new LongAdder();

        // Create collector with mock consumer that simulates batch processing
        // The key insight: ONE callback per batch, not per item
        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                // This is called ONCE per batch
                batchCount.increment();
                int size = batch.size();
                itemCount.add(size);
                batchSizeSum.add(size);
                // Simulate minimal work (what a batch Kafka send would do)
                // In production: publisher.publishBatchFireAndForget(batch)
            },
            calibration
        );

        // Benchmark data
        byte[] testEvent = "{\"test\":true,\"value\":12345}".getBytes();

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < 100_000; i++) {
            collector.submitFireAndForget(testEvent);
        }
        Thread.sleep(1000);
        collector.flush();

        // Reset counters
        batchCount.reset();
        itemCount.reset();

        // Run benchmark
        System.out.printf("Running for %d seconds with %d threads...%n", durationSec, concurrency);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong submitted = new AtomicLong(0);
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSec);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (Instant.now().isBefore(end)) {
                        collector.submitFireAndForget(testEvent);
                        submitted.incrementAndGet();
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
        collector.flush();

        // Calculate results
        Duration elapsed = Duration.between(start, Instant.now());
        double elapsedSec = elapsed.toMillis() / 1000.0;
        long totalSubmitted = submitted.get();
        long totalItems = itemCount.sum();
        long totalBatches = batchCount.sum();

        double throughput = totalSubmitted / elapsedSec;
        double avgBatchSize = totalBatches > 0 ? (double) totalItems / totalBatches : 0;

        MicrobatchCollector.Metrics metrics = collector.getMetrics();
        BatchCalibration.Config config = calibration.getBestConfig();

        long dropped = collector.droppedCount();
        long actuallyQueued = totalSubmitted - dropped;
        double acceptRate = totalSubmitted > 0 ? (actuallyQueued * 100.0 / totalSubmitted) : 0;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  Submitted:         %,d events%n", totalSubmitted);
        System.out.printf("  Queued:            %,d events (%.1f%% accepted)%n", actuallyQueued, acceptRate);
        System.out.printf("  Dropped:           %,d events (backpressure)%n", dropped);
        System.out.printf("  Duration:          %.2f seconds%n", elapsedSec);
        System.out.printf("  Submit rate:       %,.0f events/sec%n", throughput);
        System.out.printf("  Effective rate:    %,.0f events/sec%n", actuallyQueued / elapsedSec);
        System.out.println();
        System.out.printf("  Total batches:     %,d%n", totalBatches);
        System.out.printf("  Flushed items:     %,d%n", totalItems);
        System.out.printf("  Avg batch size:    %.1f%n", avgBatchSize);
        System.out.printf("  Avg flush time:    %.1f µs%n", metrics.avgFlushTimeMicros());
        System.out.printf("  Flush threads:     %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("  Pressure level:    %s%n", metrics.pressureLevel());
        System.out.println();
        System.out.println("Calibration:");
        System.out.printf("  Best batch size:   %d%n", config.batchSize());
        System.out.printf("  Best interval:     %d µs%n", config.flushIntervalMicros());
        System.out.printf("  Best score:        %.4f%n", config.score());
        System.out.println();
        System.out.println(calibration.getStatsReport());
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        collector.close();
        calibration.close();
        executor.shutdown();
    }

    /**
     * Benchmark full gateway with HTTP and Kafka.
     */
    static void benchmarkGateway(int durationSec, int concurrency) throws Exception {
        System.out.println("=== Gateway Benchmark (HTTP + Kafka) ===");
        System.out.println();
        System.out.println("NOTE: Requires running Kafka at localhost:9092");
        System.out.println();

        // Check if gateway is running or start one
        String gatewayUrl = System.getenv().getOrDefault("GATEWAY_URL", "http://localhost:8080");
        String endpoint = System.getenv().getOrDefault("GATEWAY_ENDPOINT", "/events");
        String fullUrl = gatewayUrl + endpoint;
        System.out.printf("Gateway URL: %s%n", fullUrl);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        byte[] body = "{\"test\":true,\"value\":12345}".getBytes();
        HttpRequest.BodyPublisher bodyPub = HttpRequest.BodyPublishers.ofByteArray(body);

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < 1000; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .header("Content-Type", "application/json")
                    .build();
                client.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }

        // Run benchmark
        System.out.printf("Running for %d seconds with %d threads...%n", durationSec, concurrency);

        LongAdder requestCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        LongAdder latencySum = new LongAdder();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSec);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (Instant.now().isBefore(end)) {
                        long reqStart = System.nanoTime();
                        try {
                            HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(fullUrl))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .build();
                            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                                requestCount.increment();
                                latencySum.add(System.nanoTime() - reqStart);
                            } else {
                                errorCount.increment();
                            }
                        } catch (Exception e) {
                            errorCount.increment();
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

        // Calculate results
        Duration elapsed = Duration.between(start, Instant.now());
        double elapsedSec = elapsed.toMillis() / 1000.0;
        long total = requestCount.sum();
        long errors = errorCount.sum();

        double throughput = total / elapsedSec;
        double avgLatencyMicros = total > 0 ? (latencySum.sum() / total) / 1000.0 : 0;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  Successful:        %,d requests%n", total);
        System.out.printf("  Errors:            %,d%n", errors);
        System.out.printf("  Duration:          %.2f seconds%n", elapsedSec);
        System.out.printf("  Throughput:        %,.0f req/sec%n", throughput);
        System.out.printf("  Avg latency:       %.1f µs%n", avgLatencyMicros);
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        executor.shutdown();
    }

    /**
     * Benchmark calibration convergence.
     */
    static void benchmarkCalibration(int durationSec) throws Exception {
        System.out.println("=== Calibration Convergence Test ===");
        System.out.println();

        Path tempDb = Path.of(System.getProperty("java.io.tmpdir"), "bench-calibration-convergence.db");
        BatchCalibration calibration = BatchCalibration.create(tempDb, 5000.0);

        // Simulate varying load patterns
        java.util.random.RandomGenerator rng = ThreadLocalRandom.current();

        System.out.println("Simulating calibration over time...");
        System.out.println();
        System.out.printf("%-10s %-12s %-12s %-12s %-12s%n",
            "Iteration", "Batch Size", "Interval", "Throughput", "Score");
        System.out.println("─".repeat(60));

        for (int i = 0; i < 50; i++) {
            BatchCalibration.Config config = calibration.suggestNext(rng);

            // Simulate throughput based on batch size (with noise)
            // Optimal around batch=128, interval=2000
            double optimalBatch = 128;
            double optimalInterval = 2000;

            double batchFactor = 1.0 - Math.abs(config.batchSize() - optimalBatch) / optimalBatch * 0.5;
            double intervalFactor = 1.0 - Math.abs(config.flushIntervalMicros() - optimalInterval) / optimalInterval * 0.3;
            double noise = 0.9 + rng.nextDouble() * 0.2;

            long throughput = (long) (200_000 * batchFactor * intervalFactor * noise);
            double latency = 5000 / (batchFactor * intervalFactor);

            BatchCalibration.Observation obs = new BatchCalibration.Observation(
                config.batchSize(),
                config.flushIntervalMicros(),
                throughput,
                latency,
                latency * 1.5,
                10000,
                Instant.now()
            );
            calibration.recordObservation(obs);

            BatchCalibration.Config best = calibration.getBestConfig();
            System.out.printf("%-10d %-12d %-12d %-12d %-12.2f%n",
                i + 1, best.batchSize(), best.flushIntervalMicros(),
                best.throughputPerSec(), best.score());

            Thread.sleep(100);
        }

        System.out.println();
        System.out.println("Final calibration:");
        BatchCalibration.Config best = calibration.getBestConfig();
        System.out.printf("  Batch size:   %d%n", best.batchSize());
        System.out.printf("  Interval:     %d µs%n", best.flushIntervalMicros());
        System.out.printf("  Throughput:   %,d/sec%n", best.throughputPerSec());
        System.out.printf("  Score:        %.4f%n", best.score());

        calibration.close();
    }
}
