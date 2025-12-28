package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tests adaptive throughput across the full range:
 * - L2_FAST: 10 msg/s → expect 5ms latency
 * - L10_MAX: max throughput → expect up to 30s latency
 *
 * Goal: Prove the system scales from responsive to high-throughput.
 */
public class AdaptiveRangeTest {

    private static final String TOPIC = "adaptive-range-test";

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              ADAPTIVE RANGE TEST: 10 msg/s → Max throughput                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("Kafka: %s%n", bootstrap);
        System.out.printf("BULK Baseline: %s msg/s%n", formatThroughput(BatchCalibration.BULK_BASELINE_THROUGHPUT));
        System.out.println();

        // Test 1: Collector only (no Kafka) - pure collection speed
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 1: Pure Collector Speed (no Kafka)");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        testCollectorOnly(PressureLevel.L10_MAX, 5);

        // Test 2: With Kafka - L10_MAX mode
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 2: L10_MAX Mode (30s latency budget) + Kafka");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        testWithKafka(bootstrap, PressureLevel.L10_MAX, 10);

        // Test 3: Low throughput mode
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("TEST 3: L2_FAST Mode (10-100 req/s) - responsive");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        testLowThroughput(bootstrap, 5);

        // Summary
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("PRESSURE LEVEL CONFIGURATIONS");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        printPressureLevels();
    }

    static void testCollectorOnly(PressureLevel level, int durationSec) throws Exception {
        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(level.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("Config: batch=%d, interval=%s, latency budget=%s%n",
            config.batchSize(), formatInterval(config.flushIntervalMicros()), level.latencyDisplay());

        LongAdder batchedItems = new LongAdder();
        LongAdder batchCount = new LongAdder();

        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                batchedItems.add(batch.size());
                batchCount.increment();
            },
            calibration
        );

        System.out.printf("Running for %d seconds (no Kafka)...%n", durationSec);
        long start = System.currentTimeMillis();
        long deadline = start + (durationSec * 1000L);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        LongAdder submitted = new LongAdder();
        byte[] event = new byte[100];

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                while (System.currentTimeMillis() < deadline) {
                    collector.submitFireAndForget(event);
                    submitted.increment();
                }
            });
        }

        exec.shutdown();
        exec.awaitTermination(durationSec + 5, TimeUnit.SECONDS);
        collector.flush();

        long elapsed = System.currentTimeMillis() - start;
        var metrics = collector.getMetrics();
        double throughput = metrics.throughputPerSec();
        double pctOfBaseline = (throughput * 100.0) / BatchCalibration.BULK_BASELINE_THROUGHPUT;

        System.out.printf("Submitted:   %s events%n", formatCount(submitted.sum()));
        System.out.printf("Batched:     %s events in %s batches%n",
            formatCount(batchedItems.sum()), formatCount(batchCount.sum()));
        System.out.printf("Avg batch:   %.0f events%n", metrics.avgBatchSize());
        System.out.printf("Throughput:  %s (%.1f%% of BULK baseline)%n",
            formatThroughput((long) throughput), pctOfBaseline);

        collector.close();
        calibration.close();
        Files.deleteIfExists(calibPath);
    }

    static void testWithKafka(String bootstrap, PressureLevel level, int durationSec) throws Exception {
        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(level.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("Config: batch=%d, interval=%s, latency budget=%s%n",
            config.batchSize(), formatInterval(config.flushIntervalMicros()), level.latencyDisplay());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "0"); // Fire and forget for max throughput
        props.put(ProducerConfig.LINGER_MS_CONFIG, "100");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(16 * 1024 * 1024)); // 16MB
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(512 * 1024 * 1024)); // 512MB
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            LongAdder kafkaSends = new LongAdder();

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    // Combine batch into single Kafka message
                    byte[] combined = new byte[4 + batch.size() * 100];
                    combined[0] = (byte) (batch.size() >> 24);
                    combined[1] = (byte) (batch.size() >> 16);
                    combined[2] = (byte) (batch.size() >> 8);
                    combined[3] = (byte) batch.size();
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                    kafkaSends.increment();
                },
                calibration
            );

            System.out.printf("Running for %d seconds with Kafka...%n", durationSec);
            long start = System.currentTimeMillis();
            long deadline = start + (durationSec * 1000L);

            int threads = Runtime.getRuntime().availableProcessors();
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            LongAdder submitted = new LongAdder();
            byte[] event = new byte[100];

            for (int t = 0; t < threads; t++) {
                exec.submit(() -> {
                    while (System.currentTimeMillis() < deadline) {
                        collector.submitFireAndForget(event);
                        submitted.increment();
                    }
                });
            }

            exec.shutdown();
            exec.awaitTermination(durationSec + 5, TimeUnit.SECONDS);
            producer.flush();

            long elapsed = System.currentTimeMillis() - start;
            var metrics = collector.getMetrics();
            double throughput = metrics.throughputPerSec();
            double pctOfBaseline = (throughput * 100.0) / BatchCalibration.BULK_BASELINE_THROUGHPUT;

            System.out.printf("Submitted:   %s events%n", formatCount(submitted.sum()));
            System.out.printf("Kafka sends: %s%n", formatCount(kafkaSends.sum()));
            System.out.printf("Avg batch:   %.0f events%n", metrics.avgBatchSize());
            System.out.printf("Throughput:  %s (%.1f%% of BULK baseline)%n",
                formatThroughput((long) throughput), pctOfBaseline);

            collector.close();
        }

        calibration.close();
        Files.deleteIfExists(calibPath);
    }

    static void testLowThroughput(String bootstrap, int durationSec) throws Exception {
        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        // Start at L2_FAST pressure
        calibration.updatePressure(PressureLevel.L2_FAST.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("Config: batch=%d, interval=%s, latency budget=%s%n",
            config.batchSize(), formatInterval(config.flushIntervalMicros()),
            PressureLevel.L2_FAST.latencyDisplay());

        LongAdder batchedItems = new LongAdder();
        LongAdder batchCount = new LongAdder();
        LongAdder maxLatencyNanos = new LongAdder();

        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                batchedItems.add(batch.size());
                batchCount.increment();
            },
            calibration
        );

        // Simulate 10 msg/s rate
        int targetRate = 10;
        long intervalMs = 1000 / targetRate;

        System.out.printf("Simulating %d msg/s for %d seconds...%n", targetRate, durationSec);
        long start = System.currentTimeMillis();
        long deadline = start + (durationSec * 1000L);
        byte[] event = new byte[100];
        int sent = 0;

        while (System.currentTimeMillis() < deadline) {
            long sendStart = System.nanoTime();
            collector.submitFireAndForget(event);
            sent++;

            // Rate limit
            long sleepMs = intervalMs - (System.nanoTime() - sendStart) / 1_000_000;
            if (sleepMs > 0) Thread.sleep(sleepMs);
        }

        collector.flush();
        long elapsed = System.currentTimeMillis() - start;
        var metrics = collector.getMetrics();

        System.out.printf("Sent:        %d events in %dms%n", sent, elapsed);
        System.out.printf("Actual rate: %.1f msg/s%n", sent * 1000.0 / elapsed);
        System.out.printf("Batches:     %d (avg size: %.1f)%n", batchCount.sum(), metrics.avgBatchSize());
        System.out.printf("Flush time:  %.2f µs avg%n", metrics.avgFlushTimeMicros());
        System.out.println();
        System.out.println("At low rates, batches should be small and latency minimal.");

        collector.close();
        calibration.close();
        Files.deleteIfExists(calibPath);
    }

    static void printPressureLevels() {
        System.out.printf("%-10s │ %-15s │ %-8s │ %-12s │ %s%n",
            "Level", "Activates At", "Latency", "Max Batch", "Description");
        System.out.println("─".repeat(75));

        for (PressureLevel level : PressureLevel.values()) {
            var config = level.bootstrapConfig();
            System.out.printf("%-10s │ %-15s │ %-8s │ %-12s │ %s%n",
                level.name(),
                level.rateRange,
                level.latencyDisplay(),
                formatCount(level.maxBatchSize()),
                level.description);
        }
        System.out.println("─".repeat(75));
        System.out.println();
        System.out.println("Key insight: Latency budget = how long requests can wait");
        System.out.println("  • L2_FAST (10 req/s):    5ms budget → small batches, fast response");
        System.out.println("  • L10_MAX (5M+ req/s):  30s budget → huge batches, max throughput");
    }

    static String formatThroughput(long t) {
        if (t >= 1_000_000_000) return String.format("%.1fB/s", t / 1e9);
        if (t >= 1_000_000) return String.format("%.1fM/s", t / 1e6);
        if (t >= 1_000) return String.format("%.1fK/s", t / 1e3);
        return t + "/s";
    }

    static String formatCount(long c) {
        if (c >= 1_000_000_000) return String.format("%.1fB", c / 1e9);
        if (c >= 1_000_000) return String.format("%.1fM", c / 1e6);
        if (c >= 1_000) return String.format("%.1fK", c / 1e3);
        return String.valueOf(c);
    }

    static String formatInterval(int micros) {
        if (micros >= 1_000_000) return String.format("%.0fs", micros / 1e6);
        if (micros >= 1_000) return String.format("%.0fms", micros / 1e3);
        return micros + "µs";
    }
}
