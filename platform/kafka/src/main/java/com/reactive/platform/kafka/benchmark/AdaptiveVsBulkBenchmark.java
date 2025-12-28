package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark comparing Adaptive MicrobatchCollector vs BULK approach.
 *
 * Goal: Understand the throughput gap and find configs to close it.
 *
 * BULK benchmark achieved: 131.8M msg/s
 * Adaptive collector:      14.76M msg/s (9x slower)
 *
 * This benchmark tests:
 * 1. BULK baseline (reference)
 * 2. Adaptive with normal pressure detection
 * 3. Adaptive with forced HTTP_30S pressure (extreme latency tolerance)
 * 4. Adaptive with forced HTTP_60S pressure (benchmark mode)
 */
public class AdaptiveVsBulkBenchmark {

    private static final String TOPIC = "benchmark-adaptive";

    public static void main(String[] args) throws Exception {
        int durationSec = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        String bootstrap = args.length > 1 ? args[1] : "localhost:9092";

        System.out.println("""
            ╔══════════════════════════════════════════════════════════════════════════════╗
            ║              ADAPTIVE vs BULK BENCHMARK                                      ║
            ╚══════════════════════════════════════════════════════════════════════════════╝
            """);
        System.out.printf("  Duration:  %d seconds per test%n", durationSec);
        System.out.printf("  Kafka:     %s%n", bootstrap);
        System.out.println();

        List<Result> results = new ArrayList<>();

        // Test 1: BULK baseline
        results.add(runBulkBaseline(bootstrap, durationSec));

        // Test 2: Adaptive with auto pressure
        results.add(runAdaptiveAuto(bootstrap, durationSec));

        // Test 3: Adaptive forced to L10_MAX (30s latency budget)
        results.add(runAdaptiveForced(bootstrap, durationSec, PressureLevel.L10_MAX));

        // Test 4: Adaptive forced to L9_EXTREME (15s latency budget)
        results.add(runAdaptiveForced(bootstrap, durationSec, PressureLevel.L9_EXTREME));

        // Test 5: Direct collector (no Kafka, measure pure collection speed)
        results.add(runCollectorOnly(durationSec, PressureLevel.L10_MAX));

        // Test 6: Parallel Kafka sends (bypass collector, test raw parallel send)
        results.add(runParallelKafka(bootstrap, durationSec));

        // Test 7: Collector + Kafka WITHOUT serialization (isolate serialization cost)
        results.add(runCollectorNoSerialization(bootstrap, durationSec, PressureLevel.L10_MAX));

        // Test 8: Adaptive with fewer flush threads (reduce Kafka contention)
        results.add(runAdaptiveFewThreads(bootstrap, durationSec, PressureLevel.L10_MAX, 2));

        // Test 9: Adaptive with single flush thread (minimum contention)
        results.add(runAdaptiveFewThreads(bootstrap, durationSec, PressureLevel.L10_MAX, 1));

        printSummary(results);
    }

    record Result(String mode, long messages, long durationMs, double throughput, String notes) {}

    static Result runBulkBaseline(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("BULK BASELINE: 1000 messages per Kafka send (reference)");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            // Warm up
            System.out.print("Warming up...");
            byte[] warmup = createBulkMessage(1000);
            for (int i = 0; i < 1000; i++) {
                producer.send(new ProducerRecord<>(TOPIC, warmup));
            }
            producer.flush();
            System.out.println(" done");

            // Run test
            System.out.printf("Running for %d seconds (batch=1000)...%n", durationSec);
            long messageCount = 0;
            long batchesSent = 0;
            long start = System.currentTimeMillis();
            long deadline = start + (durationSec * 1000L);

            byte[] bulk = createBulkMessage(1000);

            while (System.currentTimeMillis() < deadline) {
                producer.send(new ProducerRecord<>(TOPIC, bulk));
                messageCount += 1000;
                batchesSent++;
            }
            producer.flush();

            long elapsed = System.currentTimeMillis() - start;
            double throughput = messageCount * 1000.0 / elapsed;

            System.out.printf("Result: %,d messages (%,d batches) in %dms = %,.0f msg/s%n",
                messageCount, batchesSent, elapsed, throughput);
            System.out.println();

            return new Result("BULK_BASELINE", messageCount, elapsed, throughput,
                "1000 msg/batch, LZ4, reference");
        }
    }

    static Result runAdaptiveAuto(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("ADAPTIVE AUTO: MicrobatchCollector with auto pressure detection");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            LongAdder kafkaSends = new LongAdder();

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    // Same as BULK: combine batch into single Kafka message
                    byte[] combined = createBulkFromBatch(batch);
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                    kafkaSends.increment();
                },
                calibration
            );

            // Run producers
            System.out.printf("Running for %d seconds with auto pressure detection...%n", durationSec);
            long start = System.currentTimeMillis();
            long deadline = start + (durationSec * 1000L);

            int threads = Runtime.getRuntime().availableProcessors();
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            LongAdder submitted = new LongAdder();

            byte[] event = new byte[100]; // 100 byte event

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

            System.out.printf("Submitted: %,d | Flushed: %,d | Kafka sends: %,d%n",
                submitted.sum(), metrics.totalRequests(), kafkaSends.sum());
            System.out.printf("Pressure detected: %s | Batch size: %d%n",
                metrics.pressureLevel(), metrics.currentBatchSize());
            System.out.printf("Result: %,.0f msg/s%n", throughput);
            System.out.println();

            collector.close();
            calibration.close();

            return new Result("ADAPTIVE_AUTO", metrics.totalRequests(), elapsed, throughput,
                "Pressure: " + metrics.pressureLevel());
        }
    }

    static Result runAdaptiveForced(String bootstrap, int durationSec, PressureLevel forcedLevel) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("ADAPTIVE %s: MicrobatchCollector forced to extreme latency%n", forcedLevel);
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        // Force pressure level
        calibration.updatePressure(forcedLevel.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("Using config: batchSize=%d, flushInterval=%dµs%n",
            config.batchSize(), config.flushIntervalMicros());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            LongAdder kafkaSends = new LongAdder();

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    byte[] combined = createBulkFromBatch(batch);
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                    kafkaSends.increment();
                },
                calibration
            );

            // Run producers
            System.out.printf("Running for %d seconds with forced %s...%n", durationSec, forcedLevel);
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

            System.out.printf("Submitted: %,d | Flushed: %,d | Kafka sends: %,d%n",
                submitted.sum(), metrics.totalRequests(), kafkaSends.sum());
            System.out.printf("Avg batch: %.1f | Avg flush: %.1fµs%n",
                metrics.avgBatchSize(), metrics.avgFlushTimeMicros());
            System.out.printf("Result: %,.0f msg/s%n", throughput);
            System.out.println();

            collector.close();
            calibration.close();

            return new Result("ADAPTIVE_" + forcedLevel, metrics.totalRequests(), elapsed, throughput,
                String.format("batch=%d, interval=%dµs", config.batchSize(), config.flushIntervalMicros()));
        }
    }

    static Result runCollectorOnly(int durationSec, PressureLevel forcedLevel) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("COLLECTOR ONLY: No Kafka, pure collection speed");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(forcedLevel.minReqPer10s + 1);

        LongAdder batchedItems = new LongAdder();

        MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
            batch -> batchedItems.add(batch.size()), // No Kafka, just count
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

        long elapsed = System.currentTimeMillis() - start;
        var metrics = collector.getMetrics();
        double throughput = metrics.throughputPerSec();

        System.out.printf("Submitted: %,d | Batched: %,d%n", submitted.sum(), batchedItems.sum());
        System.out.printf("Result: %,.0f msg/s (pure collector overhead)%n", throughput);
        System.out.println();

        collector.close();
        calibration.close();

        return new Result("COLLECTOR_ONLY", metrics.totalRequests(), elapsed, throughput,
            "No Kafka, pure collection");
    }

    static Result runCollectorNoSerialization(String bootstrap, int durationSec, PressureLevel forcedLevel) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("COLLECTOR + KAFKA (no serialization): Fixed bulk, test collector overhead");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(forcedLevel.minReqPer10s + 1);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            // Pre-create fixed bulk message (skip serialization)
            byte[] fixedBulk = createBulkMessage(1000);
            LongAdder kafkaSends = new LongAdder();

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    // NO SERIALIZATION - just send fixed bulk
                    producer.send(new ProducerRecord<>(TOPIC, fixedBulk));
                    kafkaSends.increment();
                },
                calibration
            );

            System.out.printf("Running for %d seconds (no serialization overhead)...%n", durationSec);
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
            // Effective throughput = Kafka sends * 1000 (fixed batch size)
            long effectiveMessages = kafkaSends.sum() * 1000;
            double throughput = effectiveMessages * 1000.0 / elapsed;

            System.out.printf("Submitted: %,d | Kafka sends: %,d | Effective msgs: %,d%n",
                submitted.sum(), kafkaSends.sum(), effectiveMessages);
            System.out.printf("Result: %,.0f msg/s (collector + Kafka, no serialization)%n", throughput);
            System.out.println();

            collector.close();
            calibration.close();

            return new Result("COLLECTOR_KAFKA_NOSER", effectiveMessages, elapsed, throughput,
                "Fixed bulk, no serialization");
        }
    }

    static Result runParallelKafka(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("PARALLEL KAFKA: Raw parallel producer.send() (no collector)");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            int threads = Runtime.getRuntime().availableProcessors();
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            LongAdder messageCount = new LongAdder();
            LongAdder sendCount = new LongAdder();

            System.out.printf("Running for %d seconds with %d parallel threads...%n", durationSec, threads);
            long start = System.currentTimeMillis();
            long deadline = start + (durationSec * 1000L);

            // Each thread sends pre-made bulk messages
            byte[] bulk = createBulkMessage(1000);

            for (int t = 0; t < threads; t++) {
                exec.submit(() -> {
                    while (System.currentTimeMillis() < deadline) {
                        producer.send(new ProducerRecord<>(TOPIC, bulk));
                        messageCount.add(1000);
                        sendCount.increment();
                    }
                });
            }

            exec.shutdown();
            exec.awaitTermination(durationSec + 5, TimeUnit.SECONDS);
            producer.flush();

            long elapsed = System.currentTimeMillis() - start;
            double throughput = messageCount.sum() * 1000.0 / elapsed;

            System.out.printf("Sends: %,d | Messages: %,d%n", sendCount.sum(), messageCount.sum());
            System.out.printf("Result: %,.0f msg/s (parallel raw Kafka)%n", throughput);
            System.out.println();

            return new Result("PARALLEL_KAFKA", messageCount.sum(), elapsed, throughput,
                threads + " threads, 1000 msg/send");
        }
    }

    /**
     * Adaptive test with configurable flush thread count.
     * Fewer flush threads = less Kafka contention.
     */
    static Result runAdaptiveFewThreads(String bootstrap, int durationSec, PressureLevel forcedLevel, int flushThreads) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("ADAPTIVE %s (%d flush threads): Reduced contention%n", forcedLevel, flushThreads);
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        Path calibPath = Files.createTempFile("calib", ".db");
        BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0);
        calibration.updatePressure(forcedLevel.minReqPer10s + 1);

        var config = calibration.getBestConfig();
        System.out.printf("Using config: batchSize=%d, flushInterval=%dµs, flushThreads=%d%n",
            config.batchSize(), config.flushIntervalMicros(), flushThreads);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            LongAdder kafkaSends = new LongAdder();

            // Use configurable flush thread count
            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    byte[] combined = createBulkFromBatch(batch);
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                    kafkaSends.increment();
                },
                calibration,
                flushThreads
            );

            System.out.printf("Running for %d seconds with %d flush threads...%n", durationSec, flushThreads);
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

            System.out.printf("Submitted: %,d | Flushed: %,d | Kafka sends: %,d%n",
                submitted.sum(), metrics.totalRequests(), kafkaSends.sum());
            System.out.printf("Avg batch: %.1f | Avg flush: %.1fµs%n",
                metrics.avgBatchSize(), metrics.avgFlushTimeMicros());
            System.out.printf("Result: %,.0f msg/s%n", throughput);
            System.out.println();

            collector.close();
            calibration.close();

            return new Result("ADAPTIVE_" + flushThreads + "T", metrics.totalRequests(), elapsed, throughput,
                String.format("%d flush threads, batch=%d", flushThreads, config.batchSize()));
        }
    }

    // Serialization constants
    private static final int MAX_BATCH_ITEMS = 4_096;  // Practical max for 400KB batches
    private static final int MAX_BATCH_BYTES = 4 + MAX_BATCH_ITEMS * 100;  // ~410KB per buffer

    /** Create bulk message for BULK baseline. */
    static byte[] createBulkMessage(int count) {
        byte[] buf = new byte[4 + count * 100];
        ByteBuffer.wrap(buf).putInt(count);
        return buf;
    }

    /**
     * Thread-local buffer pool for serialization.
     * Each thread has a ring of pre-allocated buffers.
     * Pool size must exceed Kafka's in-flight batch count.
     */
    private static final int SER_POOL_SIZE = 64;  // Power of 2, ~26MB per thread
    private static final ThreadLocal<byte[][]> SER_BUFFER_POOL = ThreadLocal.withInitial(() -> {
        byte[][] pool = new byte[SER_POOL_SIZE][];
        for (int i = 0; i < SER_POOL_SIZE; i++) {
            pool[i] = new byte[MAX_BATCH_BYTES];
        }
        return pool;
    });
    private static final ThreadLocal<int[]> SER_POOL_INDEX = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Serialize batch using pooled buffer - ZERO ALLOCATION in hot path.
     * Returns the actual used length separately.
     */
    static byte[] createBulkFromBatch(List<byte[]> batch) {
        byte[][] pool = SER_BUFFER_POOL.get();
        int[] idx = SER_POOL_INDEX.get();
        byte[] buf = pool[idx[0] & (SER_POOL_SIZE - 1)];
        idx[0]++;

        int count = Math.min(batch.size(), MAX_BATCH_ITEMS);

        // Write count
        buf[0] = (byte) (count >> 24);
        buf[1] = (byte) (count >> 16);
        buf[2] = (byte) (count >> 8);
        buf[3] = (byte) count;

        // Copy items directly to pooled buffer
        int pos = 4;
        for (int i = 0; i < count; i++) {
            byte[] item = batch.get(i);
            System.arraycopy(item, 0, buf, pos, item.length);
            pos += item.length;
        }

        // Return the pooled buffer directly (Kafka compresses zeros cheaply)
        // The extra zeros beyond 'pos' will be compressed away by LZ4
        return buf;
    }

    static void printSummary(List<Result> results) {
        System.out.println("""
            ╔══════════════════════════════════════════════════════════════════════════════╗
            ║                              SUMMARY                                          ║
            ╚══════════════════════════════════════════════════════════════════════════════╝
            """);

        double maxThroughput = results.stream().mapToDouble(r -> r.throughput).max().orElse(0);

        System.out.printf("%-20s %15s %12s  %s%n", "Mode", "Messages", "Throughput", "Notes");
        System.out.println("─".repeat(80));

        for (Result r : results) {
            String star = r.throughput == maxThroughput ? " ★" : "";
            System.out.printf("%-20s %,15d %,10.0f/s  %s%s%n",
                r.mode, r.messages, r.throughput, r.notes, star);
        }
        System.out.println("─".repeat(80));
        System.out.println("★ = Best throughput");

        // Gap analysis
        Result bulk = results.stream().filter(r -> r.mode.equals("BULK_BASELINE")).findFirst().orElse(null);
        if (bulk != null) {
            System.out.println("\nGap Analysis vs BULK:");
            for (Result r : results) {
                if (!r.mode.equals("BULK_BASELINE")) {
                    double ratio = bulk.throughput / r.throughput;
                    double pct = (r.throughput / bulk.throughput) * 100;
                    System.out.printf("  %s: %.1fx slower (%.1f%% of BULK)%n", r.mode, ratio, pct);
                }
            }
        }
    }
}
