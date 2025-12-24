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
     */
    public static void main(String[] args) {
        String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        String topic = args.length > 1 ? args[1] : "benchmark-topic";
        long durationMs = args.length > 2 ? Long.parseLong(args[2]) : 10_000;
        int concurrency = args.length > 3 ? Integer.parseInt(args[3]) : 8;

        Result result = run(bootstrapServers, topic, durationMs, concurrency);

        System.out.println("\n" + "=".repeat(60));
        System.out.println("KAFKA PRODUCER BENCHMARK RESULT");
        System.out.println("=".repeat(60));
        System.out.printf("Messages:    %,d%n", result.totalMessages());
        System.out.printf("Duration:    %,d ms%n", result.durationMs());
        System.out.printf("Throughput:  %,.0f msg/s%n", result.messagesPerSecond());
        System.out.printf("Avg Latency: %.1f µs%n", result.avgLatencyMicros());
        System.out.printf("P99 Latency: %.1f µs%n", result.p99LatencyMicros());
        System.out.printf("Errors:      %d%n", result.errors());
        System.out.println("=".repeat(60));
    }
}
