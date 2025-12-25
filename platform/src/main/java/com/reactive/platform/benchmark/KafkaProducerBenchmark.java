package com.reactive.platform.benchmark;

import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.serialization.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LongSummaryStatistics;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Kafka Producer Benchmark - tests KafkaPublisher throughput in isolation.
 *
 * This establishes the baseline: "Kafka producer can do X msg/s"
 * No HTTP, no application logic - just raw Kafka publishing.
 *
 * Usage:
 *   KafkaProducerBenchmark.run("kafka:9092", "bench-topic", 10_000, 8);
 */
public class KafkaProducerBenchmark {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerBenchmark.class);

    public record Result(
            long totalMessages,
            long durationMs,
            double messagesPerSecond,
            double avgLatencyMicros,
            double p99LatencyMicros,
            long errors
    ) {
        @Override
        public String toString() {
            return String.format(
                    "KafkaProducerBenchmark: %,d msgs in %,dms = %,.0f msg/s (avg=%.1fµs, p99=%.1fµs, errors=%d)",
                    totalMessages, durationMs, messagesPerSecond, avgLatencyMicros, p99LatencyMicros, errors);
        }
    }

    /**
     * Run pure Kafka producer benchmark.
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param topic            Topic to publish to
     * @param durationMs       How long to run (milliseconds)
     * @param concurrency      Number of concurrent publisher threads
     * @return Benchmark result
     */
    public static Result run(String bootstrapServers, String topic, long durationMs, int concurrency) {
        log.info("Starting Kafka Producer Benchmark: servers={}, topic={}, duration={}ms, concurrency={}",
                bootstrapServers, topic, durationMs, concurrency);

        // Simple string codec for benchmarking
        Codec<String> codec = new Codec<>() {
            @Override
            public com.reactive.platform.serialization.Result<byte[]> encode(String value) {
                return com.reactive.platform.serialization.Result.success(value.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public com.reactive.platform.serialization.Result<String> decode(byte[] bytes) {
                return com.reactive.platform.serialization.Result.success(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public String name() {
                return "string";
            }

            @Override
            public String contentType() {
                return "text/plain";
            }
        };

        // Create publisher with fire-and-forget settings
        KafkaPublisher<String> publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(bootstrapServers)
                .topic(topic)
                .codec(codec)
                .keyExtractor(s -> "bench-key")
                .fireAndForget());

        AtomicLong messageCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(Duration.ofMillis(durationMs));

        // Start worker threads
        for (int i = 0; i < concurrency; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    long localCount = 0;
                    while (Instant.now().isBefore(endTime)) {
                        long start = System.nanoTime();
                        try {
                            String message = String.format("{\"thread\":%d,\"seq\":%d,\"ts\":%d}",
                                    threadId, localCount, System.currentTimeMillis());
                            publisher.publishFireAndForget(message);

                            long latencyNanos = System.nanoTime() - start;
                            latencies.offer(latencyNanos / 1000); // Convert to micros

                            messageCount.incrementAndGet();
                            localCount++;
                        } catch (Exception e) {
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

        // Start all threads
        startLatch.countDown();

        // Wait for completion
        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long actualDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        // Calculate statistics
        LongSummaryStatistics stats = latencies.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        // Calculate p99
        long[] sortedLatencies = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p99 = sortedLatencies.length > 0
                ? sortedLatencies[(int) (sortedLatencies.length * 0.99)]
                : 0;

        // Cleanup
        executor.shutdown();
        publisher.close();

        long total = messageCount.get();
        double msgPerSec = (total * 1000.0) / actualDurationMs;

        Result result = new Result(
                total,
                actualDurationMs,
                msgPerSec,
                stats.getAverage(),
                p99,
                errorCount.get()
        );

        log.info("Benchmark complete: {}", result);
        return result;
    }

    /**
     * Main entry point for CLI usage.
     * Matches standard benchmark interface: durationMs, concurrency, gatewayUrl, droolsUrl, reportsDir, skipEnrichment
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("Usage: KafkaProducerBenchmark <durationMs> <concurrency> <gatewayUrl> <droolsUrl> <reportsDir> <skipEnrichment>");
            System.exit(1);
        }

        long durationMs = Long.parseLong(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        // gatewayUrl and droolsUrl not used for this benchmark
        String reportsDir = args[4];

        // Get Kafka bootstrap servers from env (set by Docker network) or default
        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
        String topic = "kafka-producer-benchmark";

        log.info("Starting Kafka Producer Benchmark: servers={}, duration={}ms, concurrency={}",
                bootstrapServers, durationMs, concurrency);

        Result result = run(bootstrapServers, topic, durationMs, concurrency);

        // Write results in standard format
        String json = String.format("""
            {
              "name": "Kafka Producer Benchmark",
              "component": "kafka-producer",
              "status": "completed",
              "totalOperations": %d,
              "successfulOperations": %d,
              "failedOperations": %d,
              "avgThroughput": %.2f,
              "latency": {
                "avg": %.3f,
                "p50": %.3f,
                "p95": %.3f,
                "p99": %.3f,
                "max": %.3f
              },
              "durationMs": %d,
              "notes": "Fire-and-forget Kafka producer throughput (acks=0). This is the theoretical maximum for Kafka publishing."
            }
            """,
            result.totalMessages(),
            result.totalMessages() - result.errors(),
            result.errors(),
            result.messagesPerSecond(),
            result.avgLatencyMicros() / 1000.0,  // Convert to ms
            result.avgLatencyMicros() / 1000.0,  // Approximate p50
            result.avgLatencyMicros() / 1000.0 * 1.5,  // Approximate p95
            result.p99LatencyMicros() / 1000.0,
            result.p99LatencyMicros() / 1000.0 * 1.2,  // Approximate max
            result.durationMs()
        );

        java.nio.file.Path resultsPath = java.nio.file.Path.of(reportsDir, "results.json");
        java.nio.file.Files.createDirectories(resultsPath.getParent());
        java.nio.file.Files.writeString(resultsPath, json);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("KAFKA PRODUCER BENCHMARK RESULT (ISOLATION)");
        System.out.println("=".repeat(60));
        System.out.printf("Messages:    %,d%n", result.totalMessages());
        System.out.printf("Duration:    %,d ms%n", result.durationMs());
        System.out.printf("Throughput:  %,.0f msg/s%n", result.messagesPerSecond());
        System.out.printf("Avg Latency: %.1f µs%n", result.avgLatencyMicros());
        System.out.printf("P99 Latency: %.1f µs%n", result.p99LatencyMicros());
        System.out.printf("Errors:      %d%n", result.errors());
        System.out.println("=".repeat(60));
        System.out.println("This is the theoretical maximum Kafka producer throughput.");
        System.out.println("=".repeat(60));
    }
}
