package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collector-only benchmark - measures pure collection throughput without Kafka.
 * This isolates the collector overhead from Kafka I/O.
 */
public class CollectorOnlyBenchmark {

    public static void main(String[] args) throws Exception {
        int durationSec = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        String levelName = args.length > 1 ? args[1] : "L10_MAX";

        PressureLevel level = PressureLevel.valueOf(levelName);

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              COLLECTOR-ONLY BENCHMARK                                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Duration:  %d seconds%n", durationSec);
        System.out.printf("  Level:     %s (%s latency budget)%n", level, level.latencyDisplay());
        System.out.printf("  Threads:   %d%n", Runtime.getRuntime().availableProcessors());
        System.out.println();

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(level.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("  Config:    batch=%d, interval=%dµs%n", config.batchSize(), config.flushIntervalMicros());
        System.out.println();

        LongAdder batchedItems = new LongAdder();
        LongAdder batchCount = new LongAdder();

        // NO-OP flush - just count batches
        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> {
                batchedItems.add(batch.size());
                batchCount.increment();
            },
            calibration
        );

        byte[] event = new byte[64];

        System.out.println("Running...");
        long start = System.currentTimeMillis();
        long deadline = start + (durationSec * 1000L);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        LongAdder submitted = new LongAdder();
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    while (System.currentTimeMillis() < deadline) {
                        collector.submitFireAndForget(event);
                        submitted.increment();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long sendPhaseEnd = System.currentTimeMillis();

        collector.flush();
        collector.close();
        calibration.close();
        exec.shutdown();
        Files.deleteIfExists(calibPath);

        long totalElapsed = System.currentTimeMillis() - start;
        long sendPhaseDuration = sendPhaseEnd - start;

        double submitRate = submitted.sum() * 1000.0 / sendPhaseDuration;
        double batchedRate = batchedItems.sum() * 1000.0 / totalElapsed;
        double avgBatchSize = batchCount.sum() > 0 ? (double) batchedItems.sum() / batchCount.sum() : 0;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  Submitted:    %,d events%n", submitted.sum());
        System.out.printf("  Batched:      %,d events (%,d batches)%n", batchedItems.sum(), batchCount.sum());
        System.out.printf("  Avg batch:    %.1f events%n", avgBatchSize);
        System.out.printf("  Send phase:   %.2f seconds%n", sendPhaseDuration / 1000.0);
        System.out.printf("  Total:        %.2f seconds%n", totalElapsed / 1000.0);
        System.out.println("───────────────────────────────────────────────────────────────────────");
        System.out.printf("  SUBMIT RATE:  %,.0f msg/s  (producer perspective)%n", submitRate);
        System.out.printf("  BATCHED RATE: %,.0f msg/s  (consumer perspective)%n", batchedRate);
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        // Compare to BULK baseline
        System.out.println();
        System.out.printf("vs BULK SEND RATE (127M msg/s): %.1f%%%n", submitRate / 127_000_000 * 100);
    }
}
