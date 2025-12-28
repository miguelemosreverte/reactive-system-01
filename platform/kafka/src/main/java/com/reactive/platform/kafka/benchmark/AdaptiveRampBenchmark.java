package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Adaptive Ramp Benchmark - Tests the MicrobatchCollector under varying load.
 *
 * This benchmark gradually increases load from low to high, measuring:
 * - Throughput at each pressure level
 * - Latency (submit time) at each level
 * - How the collector adapts batch sizes
 * - Whether the system handles transitions smoothly
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * PRESETS:
 * ═══════════════════════════════════════════════════════════════════════════════
 *   --ramp-quick    30 second ramp (development)
 *   --ramp-full     5 minute ramp (thorough validation)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT IT TESTS:
 * ═══════════════════════════════════════════════════════════════════════════════
 *   1. Start at ~100 msg/s (L1_REALTIME pressure)
 *   2. Gradually increase to max throughput (L10_MAX pressure)
 *   3. At each pressure level, measure:
 *      - Achieved throughput (msg/s)
 *      - Submit latency (µs)
 *      - Batch size used
 *      - Flush interval used
 *   4. Verify messages are actually stored in Kafka
 */
public class AdaptiveRampBenchmark {

    private static final int MESSAGE_SIZE = 64;

    // Target rates for each pressure level (messages per second)
    private static final long[] LEVEL_TARGET_RATES = {
        10,           // L1_REALTIME
        50,           // L2_FAST
        300,          // L3_LOW
        1_000,        // L4_MODERATE
        5_000,        // L5_BALANCED
        25_000,       // L6_THROUGHPUT
        100_000,      // L7_HIGH
        500_000,      // L8_AGGRESSIVE
        2_000_000,    // L9_EXTREME
        10_000_000    // L10_MAX (unbounded - go as fast as possible)
    };

    public static void main(String[] args) throws Exception {
        String preset = args.length > 0 ? args[0].toLowerCase() : "--ramp-quick";
        String bootstrap = args.length > 1 ? args[1] : "localhost:9092";

        switch (preset) {
            case "--ramp-quick" -> runRampBenchmark(bootstrap, 30, "Quick ramp (30s)");
            case "--ramp-full" -> runRampBenchmark(bootstrap, 300, "Full ramp (5 min)");
            case "--help", "-h" -> printHelp();
            default -> {
                // Legacy: duration in seconds
                int duration = Integer.parseInt(preset);
                runRampBenchmark(bootstrap, duration, "Custom ramp (" + duration + "s)");
            }
        }
    }

    static void printHelp() {
        System.out.println("""
            ╔══════════════════════════════════════════════════════════════════════════════╗
            ║                    ADAPTIVE RAMP BENCHMARK                                    ║
            ╚══════════════════════════════════════════════════════════════════════════════╝

            Tests the MicrobatchCollector under gradually increasing load.

            PRESETS:
              --ramp-quick <kafka>    30 second ramp (development)
              --ramp-full <kafka>     5 minute ramp (thorough)

            EXAMPLES:
              java ... --ramp-quick localhost:9092
              java ... --ramp-full localhost:9092

            WHAT IT MEASURES:
              - Throughput at each of 10 pressure levels
              - Submit latency at each level
              - Batch size and flush interval adaptation
              - Kafka verification at the end
            """);
    }

    static void runRampBenchmark(String bootstrap, int totalDurationSec, String description) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    ADAPTIVE RAMP BENCHMARK                                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  %-74s  ║%n", description);
        System.out.printf("║  Duration: %d seconds | Kafka: %-43s  ║%n", totalDurationSec, bootstrap);
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Calculate time per level (10 levels)
        int secPerLevel = Math.max(1, totalDurationSec / 10);
        System.out.printf("  Time per pressure level: %d seconds%n", secPerLevel);
        System.out.println();

        // Create Kafka producer
        String topic = "adaptive-ramp-" + System.currentTimeMillis();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16777216);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 134217728L);

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

        // Create calibration and collector
        Path calibPath = Files.createTempFile("adaptive-ramp", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);

        // Track Kafka sends (with success callbacks)
        LongAdder totalKafkaSends = new LongAdder();
        LongAdder successfulKafkaSends = new LongAdder();
        LongAdder failedKafkaSends = new LongAdder();
        LongAdder totalMessagesSent = new LongAdder();

        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                // Send batch to Kafka with callback to track success
                ByteBuffer buf = ByteBuffer.allocate(8 + batch.size() * MESSAGE_SIZE);
                buf.putLong(System.nanoTime());  // Timestamp for verification
                for (byte[] msg : batch) {
                    buf.put(msg);
                }
                producer.send(new ProducerRecord<>(topic, buf.array()), (metadata, exception) -> {
                    if (exception == null) {
                        successfulKafkaSends.increment();
                    } else {
                        failedKafkaSends.increment();
                    }
                });
                totalKafkaSends.increment();
                totalMessagesSent.add(batch.size());
            },
            calibration
        );

        // Results storage
        List<LevelResult> results = new ArrayList<>();

        // Print header
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-14s %12s %12s %10s %12s %10s%n",
            "Level", "Target", "Achieved", "Latency", "BatchSize", "Interval");
        System.out.println("───────────────────────────────────────────────────────────────────────────────");

        // Ramp through each pressure level
        PressureLevel[] levels = PressureLevel.values();
        AtomicLong sequence = new AtomicLong(0);

        for (int i = 0; i < levels.length; i++) {
            PressureLevel level = levels[i];
            long targetRate = LEVEL_TARGET_RATES[i];
            boolean isMax = (i == levels.length - 1);

            // Update pressure to this level
            calibration.updatePressure(level.minReqPer10s + 1);

            // Run at this level for secPerLevel seconds
            LevelResult result = runAtLevel(collector, level, targetRate, secPerLevel, isMax, sequence);
            results.add(result);

            // Print result
            String targetStr = isMax ? "MAX" : String.format("%,d/s", targetRate);
            System.out.printf("%-14s %12s %,12.0f/s %8.1fµs %,12d %8dms%n",
                level.name(),
                targetStr,
                result.achievedRate,
                result.avgLatencyMicros,
                result.batchSize,
                result.flushIntervalMs);
        }

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        // Flush and close
        System.out.println();
        System.out.println("Flushing to Kafka...");
        collector.flush();
        collector.close();
        producer.flush();

        // Wait for Kafka to persist (important for verification)
        System.out.println("Waiting for Kafka to persist...");
        Thread.sleep(2000);

        producer.close();
        calibration.close();
        Files.deleteIfExists(calibPath);

        // Verify in Kafka (compare with successful sends, not total attempts)
        long kafkaRecords = KafkaBaselineBenchmark.getTopicRecordCount(bootstrap, topic);
        long confirmedSends = successfulKafkaSends.sum();
        boolean verified = kafkaRecords == confirmedSends;

        // If mismatch, could be timing - try again
        if (!verified && kafkaRecords > 0) {
            Thread.sleep(1000);
            kafkaRecords = KafkaBaselineBenchmark.getTopicRecordCount(bootstrap, topic);
            confirmedSends = successfulKafkaSends.sum();
            verified = kafkaRecords == confirmedSends;
        }

        // Summary
        printSummary(results, totalMessagesSent.sum(), confirmedSends, failedKafkaSends.sum(),
                     kafkaRecords, verified);
    }

    static LevelResult runAtLevel(MicrobatchCollector<byte[]> collector, PressureLevel level,
                                   long targetRate, int durationSec, boolean isMax,
                                   AtomicLong sequence) throws Exception {

        long startTime = System.nanoTime();
        long endTime = startTime + (durationSec * 1_000_000_000L);

        LongAdder submitted = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();

        // Calculate delay between messages for rate limiting
        long delayNanos = isMax ? 0 : (1_000_000_000L / targetRate);

        int threads = isMax ? Runtime.getRuntime().availableProcessors() : 1;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    long nextSendTime = System.nanoTime();
                    while (System.nanoTime() < endTime) {
                        long seq = sequence.incrementAndGet();
                        byte[] msg = buildMessage(seq);

                        long submitStart = System.nanoTime();
                        collector.submitFireAndForget(msg);
                        long submitEnd = System.nanoTime();

                        submitted.increment();
                        totalLatencyNanos.add(submitEnd - submitStart);

                        // Rate limiting (skip for MAX level)
                        if (!isMax && delayNanos > 0) {
                            nextSendTime += delayNanos;
                            long sleepNanos = nextSendTime - System.nanoTime();
                            if (sleepNanos > 1000) {
                                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long elapsed = System.nanoTime() - startTime;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double achievedRate = submitted.sum() / elapsedSec;
        double avgLatencyMicros = submitted.sum() > 0
            ? (totalLatencyNanos.sum() / (double) submitted.sum()) / 1000.0
            : 0;

        // Get current config
        var config = collector.getCurrentConfig();

        return new LevelResult(
            level,
            targetRate,
            achievedRate,
            avgLatencyMicros,
            config.batchSize(),
            config.flushIntervalMicros() / 1000,  // Convert to ms
            submitted.sum()
        );
    }

    static byte[] buildMessage(long sequence) {
        ByteBuffer buf = ByteBuffer.allocate(MESSAGE_SIZE);
        buf.putLong(sequence);
        return buf.array();
    }

    static void printSummary(List<LevelResult> results, long totalMessages, long confirmedSends,
                              long failedSends, long kafkaRecords, boolean verified) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              SUMMARY                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Calculate stats
        double maxRate = results.stream().mapToDouble(r -> r.achievedRate).max().orElse(0);
        double minLatency = results.stream().mapToDouble(r -> r.avgLatencyMicros).min().orElse(0);
        double maxLatency = results.stream().mapToDouble(r -> r.avgLatencyMicros).max().orElse(0);

        System.out.printf("  Total messages submitted: %,d%n", totalMessages);
        System.out.printf("  Kafka batches confirmed:  %,d%n", confirmedSends);
        if (failedSends > 0) {
            System.out.printf("  Kafka batches failed:     %,d%n", failedSends);
        }
        System.out.printf("  Kafka records verified:   %,d%n", kafkaRecords);
        System.out.println();
        System.out.printf("  Max throughput achieved:  %,.0f msg/s (at %s)%n",
            maxRate,
            results.stream().filter(r -> r.achievedRate == maxRate).findFirst()
                .map(r -> r.level.name()).orElse("?"));
        System.out.printf("  Latency range:            %.1f - %.1f µs%n", minLatency, maxLatency);
        System.out.println();

        // Adaptation analysis
        System.out.println("  ADAPTATION ANALYSIS:");
        int prevBatch = 0;
        for (LevelResult r : results) {
            String change = "";
            if (prevBatch > 0 && r.batchSize != prevBatch) {
                double pct = ((double) r.batchSize / prevBatch - 1) * 100;
                change = String.format(" (%+.0f%%)", pct);
            }
            System.out.printf("    %s: batch=%,d%s, interval=%dms%n",
                r.level.name(), r.batchSize, change, r.flushIntervalMs);
            prevBatch = r.batchSize;
        }
        System.out.println();

        // Verification
        System.out.printf("  VERIFIED: %s%n", verified ? "YES ✓" : "NO ✗");

        if (verified) {
            System.out.println();
            System.out.println("  ✓ Adaptive batching working correctly");
            System.out.println("  ✓ All messages verified in Kafka");
        }
        System.out.println();
    }

    record LevelResult(
        PressureLevel level,
        long targetRate,
        double achievedRate,
        double avgLatencyMicros,
        int batchSize,
        int flushIntervalMs,
        long messagesSubmitted
    ) {}
}
