package com.reactive.integration.benchmark;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * End-to-End Validation Benchmark with detailed diagnostics.
 *
 * Validates that ALL messages sent are processed through the full pipeline:
 * Gateway → Kafka → Flink → Kafka (results)
 *
 * Validation strategy:
 * - Send N messages with value=1, action="increment" to a unique session
 * - After sending, consume results and verify final counter = N
 * - Track message loss, latency distribution, and throughput stability
 *
 * Usage:
 *   java EndToEndBenchmark [duration_minutes] [target_rate_per_sec]
 */
public class EndToEndBenchmark {

    // Diagnostic counters
    private static final LongAdder totalSent = new LongAdder();
    private static final LongAdder totalAcked = new LongAdder();
    private static final LongAdder totalErrors = new LongAdder();
    private static final LongAdder totalReceived = new LongAdder();

    // Latency tracking (in microseconds)
    private static final LongAdder latencySum = new LongAdder();
    private static final AtomicLong maxLatency = new AtomicLong(0);
    private static final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);

    // Throughput tracking (per second buckets)
    private static final ConcurrentHashMap<Long, LongAdder> sendRateBuckets = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, LongAdder> receiveRateBuckets = new ConcurrentHashMap<>();

    // Validation
    private static final AtomicInteger lastObservedCounter = new AtomicInteger(0);
    private static final String SESSION_ID = "benchmark-" + System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        int durationMinutes = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        int targetRatePerSec = args.length > 1 ? Integer.parseInt(args[1]) : 10000;

        printHeader(durationMinutes, targetRatePerSec);

        // Start consumer first to not miss any messages
        Thread consumerThread = startResultConsumer();
        Thread.sleep(2000); // Let consumer connect

        // Start diagnostic reporter
        Thread reporterThread = startDiagnosticReporter();

        // Run the benchmark
        long startTime = System.currentTimeMillis();
        runBenchmark(durationMinutes, targetRatePerSec);
        long endTime = System.currentTimeMillis();

        // Wait for pipeline to drain
        System.out.println("\n⏳ Waiting for pipeline to drain (30 seconds)...");
        Thread.sleep(30_000);

        // Stop threads
        consumerThread.interrupt();
        reporterThread.interrupt();

        // Final validation
        printValidationResults(startTime, endTime);
    }

    private static void printHeader(int durationMinutes, int targetRate) {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    END-TO-END VALIDATION BENCHMARK                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  Session ID:      %s%n", SESSION_ID);
        System.out.printf("  Duration:        %d minutes%n", durationMinutes);
        System.out.printf("  Target Rate:     %,d msg/sec%n", targetRate);
        System.out.printf("  Expected Total:  ~%,d messages%n", (long)durationMinutes * 60 * targetRate);
        System.out.println();
        System.out.println("  Pipeline: HTTP → Gateway → Kafka → Flink → Kafka (results)");
        System.out.println("  Validation: Final counter value must equal total sent");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    private static void runBenchmark(int durationMinutes, int targetRatePerSec) throws Exception {
        String gatewayUrl = System.getenv().getOrDefault("GATEWAY_URL", "http://localhost:3002/api/counter");
        System.out.printf("Using gateway: %s%n", gatewayUrl);

        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

        // Multi-threaded sending for high throughput
        int senderThreads = Math.min(targetRatePerSec / 100, 100); // 1 thread per 100 msg/sec
        senderThreads = Math.max(senderThreads, 10);

        long endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L);
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(senderThreads);

        System.out.printf("🚀 Starting benchmark with %d sender threads...%n%n", senderThreads);

        ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        for (int t = 0; t < senderThreads; t++) {
            executor.submit(() -> {
                try {
                    while (System.currentTimeMillis() < endTime && running.get()) {
                        String body = String.format(
                            "{\"sessionId\":\"%s\",\"action\":\"increment\",\"value\":1}",
                            SESSION_ID
                        );

                        final long sendTimeNanos = System.nanoTime();
                        try {
                            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(gatewayUrl))
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                                .header("Content-Type", "application/json")
                                .timeout(java.time.Duration.ofSeconds(30))
                                .build();

                            java.net.http.HttpResponse<Void> resp = client.send(req,
                                java.net.http.HttpResponse.BodyHandlers.discarding());

                            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                                totalAcked.increment();
                                long latencyMicros = (System.nanoTime() - sendTimeNanos) / 1000;
                                recordLatency(latencyMicros);
                            } else {
                                totalErrors.increment();
                            }
                        } catch (Exception e) {
                            totalErrors.increment();
                        }

                        totalSent.increment();
                        recordSendRate();
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        executor.shutdown();

        System.out.println("\n✅ Sending complete!");
    }

    private static Thread startResultConsumer() {
        Thread t = new Thread(() -> {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "benchmark-validator-" + System.currentTimeMillis());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                consumer.subscribe(List.of("counter-results"));

                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        if (record.key() != null && record.key().equals(SESSION_ID)) {
                            totalReceived.increment();
                            recordReceiveRate();

                            // Extract counter value from result
                            String value = record.value();
                            if (value.contains("\"value\":")) {
                                try {
                                    int idx = value.indexOf("\"value\":") + 8;
                                    int endIdx = value.indexOf(",", idx);
                                    if (endIdx == -1) endIdx = value.indexOf("}", idx);
                                    int counterValue = Integer.parseInt(value.substring(idx, endIdx).trim());

                                    // Update max observed counter
                                    int prev;
                                    do {
                                        prev = lastObservedCounter.get();
                                    } while (counterValue > prev && !lastObservedCounter.compareAndSet(prev, counterValue));
                                } catch (Exception e) {
                                    // Ignore parse errors
                                }
                            }
                        }
                    }
                }
            }
        }, "result-consumer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Thread startDiagnosticReporter() {
        Thread t = new Thread(() -> {
            long lastSent = 0;
            long lastReceived = 0;
            long lastTime = System.currentTimeMillis();
            int reportCount = 0;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }

                long now = System.currentTimeMillis();
                long currentSent = totalSent.sum();
                long currentReceived = totalReceived.sum();
                double elapsed = (now - lastTime) / 1000.0;

                long sendRate = (long)((currentSent - lastSent) / elapsed);
                long receiveRate = (long)((currentReceived - lastReceived) / elapsed);

                long acked = totalAcked.sum();
                long errors = totalErrors.sum();
                double ackRate = currentSent > 0 ? 100.0 * acked / currentSent : 0;

                // Calculate latency stats
                long count = acked;
                double avgLatency = count > 0 ? latencySum.sum() / (double)count : 0;
                long minLat = minLatency.get();
                long maxLat = maxLatency.get();

                // Calculate pipeline lag
                long lag = currentSent - currentReceived;
                double lagSeconds = receiveRate > 0 ? lag / (double)receiveRate : 0;

                reportCount++;
                System.out.printf(
                    "[%3d] Sent: %,12d (%,8d/s) | Received: %,12d (%,8d/s) | " +
                    "Ack: %.1f%% | Lag: %,d (%.1fs) | Counter: %,d | Latency: %.0f/%.0f/%.0f µs%n",
                    reportCount,
                    currentSent, sendRate,
                    currentReceived, receiveRate,
                    ackRate,
                    lag, lagSeconds,
                    lastObservedCounter.get(),
                    (double)minLat, avgLatency, (double)maxLat
                );

                lastSent = currentSent;
                lastReceived = currentReceived;
                lastTime = now;
            }
        }, "diagnostic-reporter");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void recordLatency(long latencyMicros) {
        latencySum.add(latencyMicros);

        long current;
        do {
            current = maxLatency.get();
        } while (latencyMicros > current && !maxLatency.compareAndSet(current, latencyMicros));

        do {
            current = minLatency.get();
        } while (latencyMicros < current && !minLatency.compareAndSet(current, latencyMicros));
    }

    private static void recordSendRate() {
        long bucket = System.currentTimeMillis() / 1000;
        sendRateBuckets.computeIfAbsent(bucket, k -> new LongAdder()).increment();
    }

    private static void recordReceiveRate() {
        long bucket = System.currentTimeMillis() / 1000;
        receiveRateBuckets.computeIfAbsent(bucket, k -> new LongAdder()).increment();
    }

    private static void printValidationResults(long startTime, long endTime) {
        long sent = totalSent.sum();
        long acked = totalAcked.sum();
        long errors = totalErrors.sum();
        long received = totalReceived.sum();
        int finalCounter = lastObservedCounter.get();

        double durationSec = (endTime - startTime) / 1000.0;
        double avgSendRate = sent / durationSec;
        double avgReceiveRate = received / durationSec;

        long count = acked;
        double avgLatency = count > 0 ? latencySum.sum() / (double)count : 0;

        // Calculate throughput stability (coefficient of variation)
        List<Long> rates = new ArrayList<>();
        sendRateBuckets.values().forEach(a -> rates.add(a.sum()));
        double mean = rates.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = rates.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double cv = mean > 0 ? stdDev / mean * 100 : 0;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                           VALIDATION RESULTS                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Throughput
        System.out.println("┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│ THROUGHPUT                                                                   │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│  Duration:           %.1f seconds%n", durationSec);
        System.out.printf("│  Total Sent:         %,d messages%n", sent);
        System.out.printf("│  Send Rate:          %,.0f msg/sec (avg)%n", avgSendRate);
        System.out.printf("│  Stability (CV):     %.1f%% %s%n", cv, cv < 10 ? "✓ STABLE" : "⚠ UNSTABLE");
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");

        // Reliability
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│ RELIABILITY                                                                  │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│  Acked:              %,d (%.2f%%)%n", acked, 100.0 * acked / sent);
        System.out.printf("│  Errors:             %,d (%.2f%%)%n", errors, 100.0 * errors / sent);
        System.out.printf("│  Received Results:   %,d%n", received);
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");

        // Latency
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│ LATENCY (Kafka Ack)                                                          │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│  Min:                %,d µs%n", minLatency.get() == Long.MAX_VALUE ? 0 : minLatency.get());
        System.out.printf("│  Avg:                %.0f µs%n", avgLatency);
        System.out.printf("│  Max:                %,d µs%n", maxLatency.get());
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");

        // Validation
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.println("│ VALIDATION                                                                   │");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│  Expected Counter:   %,d (messages sent)%n", sent);
        System.out.printf("│  Observed Counter:   %,d (from Flink)%n", finalCounter);

        if (acked == sent && errors == 0) {
            System.out.println("│  Gateway Status:     ✅ PASSED - All messages accepted by gateway");
        } else {
            System.out.printf("│  Gateway Status:     ⚠ %.2f%% accepted, %,d errors%n",
                100.0 * acked / sent, errors);
        }
        if (finalCounter == sent) {
            System.out.println("│  Flink Status:       ✅ PASSED - All messages processed correctly");
        } else if (finalCounter > 0) {
            double processed = 100.0 * finalCounter / sent;
            System.out.printf("│  Flink Status:       ⚠ PARTIAL - %.2f%% processed%n", processed);
        } else {
            System.out.println("│  Flink Status:       ⏳ Pending (check counter-results topic)");
        }
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }
}
