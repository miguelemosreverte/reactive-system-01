package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Quick test of adaptive batching at different pressure levels.
 * Uses MEGA (1s interval) for faster results than HTTP_30S (30s).
 */
public class QuickAdaptiveTest {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              QUICK ADAPTIVE TEST                                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Test collector only (no Kafka overhead) at different levels
        testLevel(PressureLevel.L7_HIGH, 5);     // 1s latency budget
        testLevel(PressureLevel.L9_EXTREME, 3);  // 15s latency budget
        testLevel(PressureLevel.L2_FAST, 3);     // 5ms latency budget

        // Print all levels
        System.out.println("\n═══ PRESSURE LEVELS ═══");
        System.out.printf("%-10s │ %-15s │ %-8s │ %-12s%n",
            "Level", "Activates At", "Latency", "Description");
        System.out.println("─".repeat(55));
        for (PressureLevel level : PressureLevel.values()) {
            System.out.printf("%-10s │ %-15s │ %-8s │ %s%n",
                level.name(), level.rateRange, level.latencyDisplay(), level.description);
        }
    }

    static void testLevel(PressureLevel level, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("TESTING: %s (%s, %s latency budget)%n",
            level.name(), level.rateRange, level.latencyDisplay());
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(level.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("Bootstrap: batch=%d, interval=%dµs%n",
            config.batchSize(), config.flushIntervalMicros());

        LongAdder batchedItems = new LongAdder();
        LongAdder batchCount = new LongAdder();

        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                batchedItems.add(batch.size());
                batchCount.increment();
            },
            calibration
        );

        System.out.printf("Running for %d seconds...%n", durationSec);
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

        System.out.printf("Submitted:   %.1fM events in %dms%n", submitted.sum() / 1e6, elapsed);
        System.out.printf("Batches:     %d (avg size: %.0f)%n", batchCount.sum(), metrics.avgBatchSize());
        System.out.printf("Throughput:  %.1fM/s (%.1f%% of BULK baseline)%n",
            throughput / 1e6, pctOfBaseline);
        System.out.println();

        collector.close();
        calibration.close();
        Files.deleteIfExists(calibPath);
    }
}
