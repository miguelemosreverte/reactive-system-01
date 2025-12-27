package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.Config;
import com.reactive.platform.gateway.microbatch.BatchCalibration.Observation;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Online learning benchmark that continuously calibrates the adaptive system.
 *
 * Goal: Let the system learn optimal configs without hardcoding.
 * Finds the real bottleneck through systematic experimentation.
 */
public class OnlineLearningBenchmark {

    private static final String TOPIC = "benchmark-learning";
    private static final int CALIBRATION_ROUNDS = 20;
    private static final int ROUND_DURATION_SEC = 10;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : CALIBRATION_ROUNDS;

        System.out.println("""
            ╔══════════════════════════════════════════════════════════════════════════════╗
            ║              ONLINE LEARNING BENCHMARK                                       ║
            ║  Learns optimal config through experimentation - no hardcoded values         ║
            ╚══════════════════════════════════════════════════════════════════════════════╝
            """);
        System.out.printf("  Kafka:       %s%n", bootstrap);
        System.out.printf("  Rounds:      %d (x %ds each)%n", rounds, ROUND_DURATION_SEC);
        System.out.println();

        // Use persistent calibration DB
        Path calibPath = Path.of(System.getProperty("user.home"), ".reactive", "learning-benchmark.db");
        Files.createDirectories(calibPath.getParent());

        try (BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0)) {

            // Show initial state
            System.out.println("Initial calibration state:");
            System.out.println(calibration.getStatsReport());

            // Run calibration rounds for each pressure level
            for (PressureLevel level : new PressureLevel[]{
                PressureLevel.HTTP_30S,
                PressureLevel.MEGA,
                PressureLevel.EXTREME
            }) {
                System.out.printf("%n=== Calibrating %s ===%n", level);
                runCalibrationRounds(bootstrap, calibration, level, rounds);
            }

            // Final results
            System.out.println("\n" + "═".repeat(80));
            System.out.println("FINAL CALIBRATION RESULTS:");
            System.out.println(calibration.getStatsReport());

            // Run bottleneck analysis
            runBottleneckAnalysis(bootstrap, calibration);
        }
    }

    static void runCalibrationRounds(String bootstrap, BatchCalibration calibration,
                                      PressureLevel level, int rounds) throws Exception {

        calibration.updatePressure(level.minReqPer10s + 1);
        Random rng = new Random();

        Properties props = createProducerProps(bootstrap);

        double bestThroughput = 0;
        Config bestConfig = null;

        for (int round = 1; round <= rounds; round++) {
            // Get config to test (either best known or exploration)
            Config testConfig = round <= 3
                ? calibration.getBestConfig()  // First few rounds: test current best
                : calibration.suggestNext(rng); // Then: explore

            System.out.printf("Round %d/%d: Testing batch=%d, interval=%dµs... ",
                round, rounds, testConfig.batchSize(), testConfig.flushIntervalMicros());

            try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
                Observation obs = runSingleTest(producer, calibration, testConfig, ROUND_DURATION_SEC);
                calibration.recordObservation(obs, level);

                double throughput = obs.throughputPerSec();
                String marker = throughput > bestThroughput ? " ★ NEW BEST" : "";
                System.out.printf("%,.0f msg/s (latency: %.1fµs)%s%n",
                    throughput, obs.avgLatencyMicros(), marker);

                if (throughput > bestThroughput) {
                    bestThroughput = throughput;
                    bestConfig = testConfig;
                }
            }
        }

        System.out.printf("Best for %s: %,.0f msg/s (batch=%d, interval=%dµs)%n",
            level, bestThroughput,
            bestConfig != null ? bestConfig.batchSize() : 0,
            bestConfig != null ? bestConfig.flushIntervalMicros() : 0);
    }

    static Observation runSingleTest(KafkaProducer<String, byte[]> producer,
                                      BatchCalibration calibration,
                                      Config testConfig,
                                      int durationSec) throws Exception {

        LongAdder kafkaSends = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();

        // Create collector with test config
        // Temporarily update calibration to use our test config
        Path tempDb = Files.createTempFile("calib-test", ".db");
        try (BatchCalibration tempCal = BatchCalibration.create(tempDb, 5000.0)) {
            // Force the config by recording a fake high-score observation
            tempCal.recordObservation(new Observation(
                testConfig.batchSize(),
                testConfig.flushIntervalMicros(),
                Long.MAX_VALUE, 0, 0, 1, Instant.now()
            ), calibration.getCurrentPressure());

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    long start = System.nanoTime();
                    byte[] combined = createBulkFromBatch(batch);
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                    totalLatencyNanos.add(System.nanoTime() - start);
                    kafkaSends.increment();
                },
                tempCal
            );

            long start = System.currentTimeMillis();
            long deadline = start + (durationSec * 1000L);

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

            var metrics = collector.getMetrics();
            double avgLatency = kafkaSends.sum() > 0
                ? totalLatencyNanos.sum() / (double) kafkaSends.sum() / 1000.0
                : 0;

            return new Observation(
                testConfig.batchSize(),
                testConfig.flushIntervalMicros(),
                (long) metrics.throughputPerSec(),
                avgLatency,
                avgLatency * 2,  // Approximate p99
                metrics.totalRequests(),
                Instant.now()
            );
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    /**
     * Analyze where the bottleneck is by isolating components.
     */
    static void runBottleneckAnalysis(String bootstrap, BatchCalibration calibration) throws Exception {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("BOTTLENECK ANALYSIS");
        System.out.println("═".repeat(80));

        Config best = calibration.getBestConfigForPressure(PressureLevel.HTTP_30S);
        Properties props = createProducerProps(bootstrap);

        // Test 1: Pure producer (no collection, no serialization)
        System.out.print("1. Raw Kafka producer (no overhead)... ");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            byte[] bulk = new byte[4 + 1000 * 100];
            long start = System.currentTimeMillis();
            long count = 0;
            while (System.currentTimeMillis() - start < 5000) {
                producer.send(new ProducerRecord<>(TOPIC, bulk));
                count += 1000;
            }
            producer.flush();
            double throughput = count * 1000.0 / (System.currentTimeMillis() - start);
            System.out.printf("%,.0f msg/s%n", throughput);
        }

        // Test 2: Collector only (no Kafka)
        System.out.print("2. Collector only (no Kafka)... ");
        Path tempDb = Files.createTempFile("bottle", ".db");
        try (BatchCalibration tempCal = BatchCalibration.create(tempDb, 5000.0)) {
            tempCal.updatePressure(PressureLevel.HTTP_30S.minReqPer10s + 1);
            LongAdder collected = new LongAdder();

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> collected.add(batch.size()),
                tempCal
            );

            long start = System.currentTimeMillis();
            long deadline = start + 5000;
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
            exec.awaitTermination(10, TimeUnit.SECONDS);
            collector.close();

            double throughput = collected.sum() * 1000.0 / (System.currentTimeMillis() - start);
            System.out.printf("%,.0f msg/s%n", throughput);
        }
        Files.deleteIfExists(tempDb);

        // Test 3: Serialization only
        System.out.print("3. Serialization speed... ");
        {
            List<byte[]> batch = new ArrayList<>();
            for (int i = 0; i < best.batchSize(); i++) {
                batch.add(new byte[100]);
            }

            long start = System.nanoTime();
            int iterations = 10000;
            for (int i = 0; i < iterations; i++) {
                createBulkFromBatch(batch);
            }
            long elapsed = System.nanoTime() - start;
            long msgsPerSec = (long) (iterations * batch.size() * 1_000_000_000.0 / elapsed);
            System.out.printf("%,d msg/s (batch=%d)%n", msgsPerSec, best.batchSize());
        }

        // Test 4: Full adaptive with learned config
        System.out.printf("4. Full adaptive (batch=%d, interval=%dµs)... ",
            best.batchSize(), best.flushIntervalMicros());
        tempDb = Files.createTempFile("bottle", ".db");
        try (BatchCalibration tempCal = BatchCalibration.create(tempDb, 5000.0);
             KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {

            tempCal.updatePressure(PressureLevel.HTTP_30S.minReqPer10s + 1);

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    byte[] combined = createBulkFromBatch(batch);
                    producer.send(new ProducerRecord<>(TOPIC, combined));
                },
                tempCal
            );

            long start = System.currentTimeMillis();
            long deadline = start + 5000;
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
            exec.awaitTermination(10, TimeUnit.SECONDS);
            producer.flush();
            collector.close();

            double throughput = collector.getMetrics().throughputPerSec();
            System.out.printf("%,.0f msg/s%n", throughput);
        }
        Files.deleteIfExists(tempDb);

        System.out.println("\nBottleneck identified: Compare numbers above to see where time is spent.");
    }

    static Properties createProducerProps(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        return props;
    }

    // Buffer pool for zero-allocation serialization
    private static final int MAX_BATCH_ITEMS = 4_096;
    private static final int MAX_BATCH_BYTES = 4 + MAX_BATCH_ITEMS * 100;
    private static final int SER_POOL_SIZE = 64;
    private static final ThreadLocal<byte[][]> SER_BUFFER_POOL = ThreadLocal.withInitial(() -> {
        byte[][] pool = new byte[SER_POOL_SIZE][];
        for (int i = 0; i < SER_POOL_SIZE; i++) {
            pool[i] = new byte[MAX_BATCH_BYTES];
        }
        return pool;
    });
    private static final ThreadLocal<int[]> SER_POOL_INDEX = ThreadLocal.withInitial(() -> new int[1]);

    static byte[] createBulkFromBatch(List<byte[]> batch) {
        byte[][] pool = SER_BUFFER_POOL.get();
        int[] idx = SER_POOL_INDEX.get();
        byte[] buf = pool[idx[0] & (SER_POOL_SIZE - 1)];
        idx[0]++;

        int count = Math.min(batch.size(), MAX_BATCH_ITEMS);
        buf[0] = (byte) (count >> 24);
        buf[1] = (byte) (count >> 16);
        buf[2] = (byte) (count >> 8);
        buf[3] = (byte) count;

        int pos = 4;
        for (int i = 0; i < count; i++) {
            byte[] item = batch.get(i);
            System.arraycopy(item, 0, buf, pos, item.length);
            pos += item.length;
        }

        return buf;
    }
}
