package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Explores batch size impact on throughput.
 *
 * Hypothesis: Bigger batches = better Kafka throughput
 * Constraint: Max 30s latency (HTTP timeout)
 */
public class BatchSizeExplorer {

    private static final String TOPIC = "batch-explorer";
    private static final int TEST_DURATION_SEC = 10;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    BATCH SIZE EXPLORATION                                    ║");
        System.out.println("║  Testing hypothesis: Bigger batches = better Kafka throughput                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  Kafka: %s%n", bootstrap);
        System.out.printf("  Duration: %ds per test%n", TEST_DURATION_SEC);
        System.out.println();

        // Test exponentially increasing batch sizes
        int[] batchSizes = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

        // Also test different flush intervals
        int[] intervals = {1000, 10000, 100000, 1000000}; // 1ms, 10ms, 100ms, 1s

        Properties props = createProducerProps(bootstrap);

        System.out.println("Phase 1: Batch Size Impact (fixed 10ms interval)");
        System.out.println("─".repeat(70));
        System.out.printf("%-12s │ %-12s │ %-12s │ %-15s │ %s%n",
            "Batch Size", "Throughput", "Latency", "Batches/s", "Notes");
        System.out.println("─".repeat(70));

        long bestThroughput = 0;
        int bestBatchSize = 0;

        for (int batchSize : batchSizes) {
            Result result = runTest(bootstrap, props, batchSize, 10000, TEST_DURATION_SEC);

            String notes = "";
            if (result.throughput > bestThroughput) {
                bestThroughput = result.throughput;
                bestBatchSize = batchSize;
                notes = "★ NEW BEST";
            }

            System.out.printf("%-12d │ %10.1fM/s │ %10.1fms │ %13.0f/s │ %s%n",
                batchSize,
                result.throughput / 1_000_000.0,
                result.avgLatencyMs,
                result.batchesPerSec,
                notes);
        }

        System.out.println("─".repeat(70));
        System.out.printf("Best: batch=%d → %.1fM/s%n%n", bestBatchSize, bestThroughput / 1_000_000.0);

        // Phase 2: Test interval impact with best batch size
        System.out.println("Phase 2: Flush Interval Impact (batch=" + bestBatchSize + ")");
        System.out.println("─".repeat(70));
        System.out.printf("%-12s │ %-12s │ %-12s │ %-15s │ %s%n",
            "Interval", "Throughput", "Latency", "Batches/s", "Notes");
        System.out.println("─".repeat(70));

        long bestThroughput2 = 0;
        int bestInterval = 0;

        for (int interval : intervals) {
            Result result = runTest(bootstrap, props, bestBatchSize, interval, TEST_DURATION_SEC);

            String notes = "";
            if (result.throughput > bestThroughput2) {
                bestThroughput2 = result.throughput;
                bestInterval = interval;
                notes = "★ NEW BEST";
            }

            System.out.printf("%-12s │ %10.1fM/s │ %10.1fms │ %13.0f/s │ %s%n",
                formatInterval(interval),
                result.throughput / 1_000_000.0,
                result.avgLatencyMs,
                result.batchesPerSec,
                notes);
        }

        System.out.println("─".repeat(70));
        System.out.printf("Best: interval=%s → %.1fM/s%n%n", formatInterval(bestInterval), bestThroughput2 / 1_000_000.0);

        // Phase 3: Push to the limit - test very large batches
        System.out.println("Phase 3: Extreme Batch Sizes (pushing limits)");
        System.out.println("─".repeat(70));

        int[] extremeBatches = {65536, 131072, 262144, 524288};

        for (int batchSize : extremeBatches) {
            try {
                Result result = runTest(bootstrap, props, batchSize, bestInterval, TEST_DURATION_SEC);

                String notes = result.throughput > bestThroughput2 ? "★ NEW BEST" : "";
                if (result.throughput > bestThroughput2) {
                    bestThroughput2 = result.throughput;
                    bestBatchSize = batchSize;
                }

                System.out.printf("batch=%-8d interval=%-8s → %10.1fM/s (latency: %.1fms) %s%n",
                    batchSize, formatInterval(bestInterval),
                    result.throughput / 1_000_000.0,
                    result.avgLatencyMs,
                    notes);
            } catch (Exception e) {
                System.out.printf("batch=%-8d → FAILED: %s%n", batchSize, e.getMessage());
            }
        }

        System.out.println();
        System.out.println("═".repeat(70));
        System.out.println("CONCLUSION");
        System.out.println("═".repeat(70));
        System.out.printf("Optimal config: batch=%d, interval=%s%n", bestBatchSize, formatInterval(bestInterval));
        System.out.printf("Max throughput: %.1fM msg/s%n", bestThroughput2 / 1_000_000.0);
        System.out.println();
        System.out.println("Recommendation:");
        if (bestBatchSize > 4096) {
            System.out.println("  → Current hardcoded limits (4096-16384) are TOO LOW");
            System.out.println("  → Should allow batch sizes up to " + bestBatchSize + " or higher");
        } else {
            System.out.println("  → Current limits are appropriate for this hardware");
        }
    }

    static Result runTest(String bootstrap, Properties props, int batchSize, int intervalMicros, int durationSec) throws Exception {
        LongAdder totalItems = new LongAdder();
        LongAdder totalBatches = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();

        Path tempDb = Files.createTempFile("batch-explore", ".db");
        try (BatchCalibration cal = BatchCalibration.create(tempDb, 5000.0);
             KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {

            // Force specific config by recording a fake observation
            cal.updatePressure(BatchCalibration.PressureLevel.HTTP_30S.minReqPer10s + 1);
            cal.recordObservation(new BatchCalibration.Observation(
                batchSize, intervalMicros,
                Long.MAX_VALUE, 0, 0, 1, java.time.Instant.now()
            ), BatchCalibration.PressureLevel.HTTP_30S);

            // Create collector with large max batch size
            int maxBatch = Math.max(batchSize * 2, 131072);  // Allow very large batches
            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    long start = System.nanoTime();
                    byte[] combined = createBulk(batch);
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                    totalLatencyNanos.add(System.nanoTime() - start);
                    totalItems.add(batch.size());
                    totalBatches.increment();
                },
                cal,
                maxBatch,
                2  // 2 flush threads
            );

            long startTime = System.currentTimeMillis();
            long deadline = startTime + (durationSec * 1000L);

            int threads = Runtime.getRuntime().availableProcessors();
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            byte[] event = new byte[100];

            for (int t = 0; t < threads; t++) {
                exec.submit(() -> {
                    while (System.currentTimeMillis() < deadline) {
                        collector.submitFireAndForget(event);
                    }
                });
            }

            exec.shutdown();
            exec.awaitTermination(durationSec + 5, TimeUnit.SECONDS);
            producer.flush();
            collector.close();

            long elapsed = System.currentTimeMillis() - startTime;
            long items = totalItems.sum();
            long batches = totalBatches.sum();

            double throughput = items * 1000.0 / elapsed;
            double avgLatencyMs = batches > 0
                ? totalLatencyNanos.sum() / (double) batches / 1_000_000.0
                : 0;
            double batchesPerSec = batches * 1000.0 / elapsed;

            return new Result(
                (long) throughput,
                avgLatencyMs,
                batchesPerSec,
                items,
                batches
            );
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    static final class Result {
        public final long throughput;
        public final double avgLatencyMs;
        public final double batchesPerSec;
        public final long totalItems;
        public final long totalBatches;

        Result(long throughput, double avgLatencyMs, double batchesPerSec, long totalItems, long totalBatches) {
            this.throughput = throughput;
            this.avgLatencyMs = avgLatencyMs;
            this.batchesPerSec = batchesPerSec;
            this.totalItems = totalItems;
            this.totalBatches = totalBatches;
        }
    }

    static Properties createProducerProps(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "10");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(16 * 1024 * 1024)); // 16MB
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(512 * 1024 * 1024)); // 512MB
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(100 * 1024 * 1024)); // 100MB
        return props;
    }

    static String formatInterval(int micros) {
        if (micros >= 1_000_000) return String.format("%.0fs", micros / 1_000_000.0);
        if (micros >= 1_000) return String.format("%.0fms", micros / 1_000.0);
        return micros + "µs";
    }

    static byte[] createBulk(java.util.List<byte[]> batch) {
        int size = 4 + batch.size() * 100;
        byte[] buf = new byte[size];
        int count = batch.size();
        buf[0] = (byte) (count >> 24);
        buf[1] = (byte) (count >> 16);
        buf[2] = (byte) (count >> 8);
        buf[3] = (byte) count;

        int pos = 4;
        for (byte[] item : batch) {
            System.arraycopy(item, 0, buf, pos, item.length);
            pos += item.length;
        }
        return buf;
    }
}
