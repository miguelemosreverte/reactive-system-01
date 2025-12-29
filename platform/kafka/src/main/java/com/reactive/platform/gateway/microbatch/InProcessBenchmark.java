package com.reactive.platform.gateway.microbatch;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.reactive.platform.serialization.Codec;
import com.reactive.platform.base.Result;

/**
 * In-process benchmark that starts both the gateway and client in the same JVM.
 * Uses high-concurrency virtual threads to maximize client throughput.
 *
 * This eliminates network latency between containers and provides a true measure
 * of the gateway's capacity.
 */
public class InProcessBenchmark {

    public static void main(String[] args) throws Exception {
        int durationSec = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 500; // Higher concurrency
        String kafkaBootstrap = args.length > 2 ? args[2] : "kafka:29092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║           IN-PROCESS GATEWAY BENCHMARK (Same JVM)                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Duration:    %d seconds%n", durationSec);
        System.out.printf("  Concurrency: %d virtual threads%n", concurrency);
        System.out.printf("  Kafka:       %s%n", kafkaBootstrap);
        System.out.println();

        // Start the gateway
        int port = 9999;
        String topic = "gateway-benchmark";

        Codec<byte[]> byteCodec = Codec.of(
            bytes -> Result.success(bytes),
            bytes -> Result.success(bytes),
            "application/octet-stream",
            "bytes"
        );

        System.out.println("Starting MicrobatchingGateway...");

        MicrobatchingGateway<byte[]> gateway = MicrobatchingGateway.<byte[]>builder()
            .kafka(kafkaBootstrap, topic)
            .codec(byteCodec)
            .eventFactory(InProcessBenchmark::bufferToBytes)
            .port(port)
            .reactors(Runtime.getRuntime().availableProcessors())
            .targetLatency(5000.0)
            .build();

        Thread.sleep(1000);

        // Create high-performance HTTP client with many connections
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        String url = "http://localhost:" + port + "/events";
        byte[] body = "{\"test\":true,\"value\":12345}".getBytes();

        // Warmup with high concurrency
        System.out.println("Warming up with 10,000 requests...");
        CountDownLatch warmupLatch = new CountDownLatch(10_000);
        ExecutorService warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 10_000; i++) {
            warmupExecutor.submit(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .header("Content-Type", "application/json")
                        .build();
                    client.send(req, HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    // Ignore warmup errors
                } finally {
                    warmupLatch.countDown();
                }
            });
        }
        warmupLatch.await();
        warmupExecutor.shutdown();
        Thread.sleep(500);

        // Run benchmark
        System.out.printf("Running for %d seconds with %d virtual threads...%n", durationSec, concurrency);
        System.out.println();

        LongAdder successCount = new LongAdder();
        LongAdder errorCount = new LongAdder();
        LongAdder latencySum = new LongAdder();
        AtomicLong maxLatency = new AtomicLong(0);

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
                                .uri(URI.create(url))
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .build();
                            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                                successCount.increment();
                                long latencyMicros = (System.nanoTime() - reqStart) / 1000;
                                latencySum.add(latencyMicros);

                                long current;
                                do {
                                    current = maxLatency.get();
                                } while (latencyMicros > current && !maxLatency.compareAndSet(current, latencyMicros));
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

        // Start all threads simultaneously
        startLatch.countDown();

        // Print progress
        long lastCount = 0;
        while (!doneLatch.await(1, TimeUnit.SECONDS)) {
            long currentCount = successCount.sum();
            long rate = currentCount - lastCount;
            System.out.printf("[Progress] %,d successful (%,d/s), %,d errors%n",
                currentCount, rate, errorCount.sum());
            lastCount = currentCount;
        }

        Duration elapsed = Duration.between(start, Instant.now());
        double elapsedSec = elapsed.toMillis() / 1000.0;
        long success = successCount.sum();
        long errors = errorCount.sum();

        double throughput = success / elapsedSec;
        double avgLatencyMs = success > 0 ? (latencySum.sum() / success) / 1000.0 : 0;
        double maxLatencyMs = maxLatency.get() / 1000.0;

        // Get gateway metrics
        MicrobatchingGateway.GatewayMetrics metrics = gateway.getMetrics();

        gateway.close();
        executor.shutdown();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS: In-Process Gateway Benchmark");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  Successful:        %,d requests%n", success);
        System.out.printf("  Errors:            %,d%n", errors);
        System.out.printf("  Duration:          %.2f seconds%n", elapsedSec);
        System.out.printf("  Throughput:        %,.0f ops/sec%n", throughput);
        System.out.printf("  Avg Latency:       %.2f ms%n", avgLatencyMs);
        System.out.printf("  Max Latency:       %.2f ms%n", maxLatencyMs);
        System.out.println();
        System.out.println("Gateway Internals:");
        System.out.printf("  Batch Size:        %d%n", metrics.collectorMetrics().currentBatchSize());
        System.out.printf("  Flush Interval:    %d µs%n", metrics.collectorMetrics().currentFlushIntervalMicros());
        System.out.printf("  Collector Rate:    %,.0f events/sec%n", metrics.collectorMetrics().throughputPerSec());
        System.out.println("═══════════════════════════════════════════════════════════════════════");
    }

    private static byte[] bufferToBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
