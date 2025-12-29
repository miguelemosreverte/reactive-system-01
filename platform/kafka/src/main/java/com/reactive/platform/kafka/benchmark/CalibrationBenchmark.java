package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.BatchCalibration;
import com.reactive.platform.gateway.microbatch.BatchCalibration.Config;
import com.reactive.platform.gateway.microbatch.BatchCalibration.Observation;
import com.reactive.platform.gateway.microbatch.BatchCalibration.PressureLevel;
import com.reactive.platform.gateway.microbatch.MicrobatchCollector;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Calibration benchmark for pressure level buckets.
 *
 * Designed for use with the brochure system:
 * - Tests each bucket to learn optimal configurations
 * - Detects regressions against expected baselines
 * - Outputs results in JSON format for reporting
 *
 * Usage:
 *   java CalibrationBenchmark [bootstrap] [buckets...] [--rounds N] [--duration S]
 *
 * Examples:
 *   java CalibrationBenchmark localhost:9092 EXTREME MEGA HTTP_30S
 *   java CalibrationBenchmark localhost:9092 --all --rounds 5 --duration 30
 */
public class CalibrationBenchmark {

    private static final String TOPIC = "calibration-benchmark";
    private static final int DEFAULT_ROUNDS = 5;
    private static final int DEFAULT_DURATION_SEC = 30;

    public static void main(String[] args) throws Exception {
        // Parse arguments
        String bootstrap = "localhost:9092";
        List<PressureLevel> buckets = new ArrayList<>();
        int rounds = DEFAULT_ROUNDS;
        int durationSec = DEFAULT_DURATION_SEC;
        boolean showStatus = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--all")) {
                buckets.addAll(Arrays.asList(PressureLevel.L9_EXTREME, PressureLevel.L7_HIGH, PressureLevel.L10_MAX));
            } else if (arg.equals("--rounds") && i + 1 < args.length) {
                rounds = Integer.parseInt(args[++i]);
            } else if (arg.equals("--duration") && i + 1 < args.length) {
                durationSec = Integer.parseInt(args[++i]);
            } else if (arg.equals("--status")) {
                showStatus = true;
            } else if (arg.contains(":")) {
                bootstrap = arg;
            } else {
                try {
                    buckets.add(PressureLevel.valueOf(arg.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    // Ignore invalid bucket names
                }
            }
        }

        if (buckets.isEmpty() && !showStatus) {
            buckets.addAll(Arrays.asList(PressureLevel.L9_EXTREME, PressureLevel.L7_HIGH, PressureLevel.L10_MAX));
        }

        // Load calibration database
        Path calibPath = Path.of(System.getProperty("user.home"), ".reactive", "calibration.db");
        Files.createDirectories(calibPath.getParent());

        try (BatchCalibration calibration = BatchCalibration.create(calibPath, 5000.0)) {

            if (showStatus) {
                System.out.println(calibration.getStatsReport());
                return;
            }

            printHeader(bootstrap, buckets, rounds, durationSec);

            // Results for JSON output
            List<BucketResult> results = new ArrayList<>();

            for (PressureLevel bucket : buckets) {
                BucketResult result = benchmarkBucket(bootstrap, calibration, bucket, rounds, durationSec);
                results.add(result);
                printBucketResult(result);
            }

            // Print summary
            printSummary(calibration, results);

            // Output JSON for brochure system
            outputJson(results, calibPath);
        }
    }

    static BucketResult benchmarkBucket(String bootstrap, BatchCalibration calibration,
                                         PressureLevel bucket, int rounds, int durationSec) throws Exception {

        Config previousBest = calibration.getBestConfigForPressure(bucket);
        long previousThroughput = previousBest.throughputPerSec();

        calibration.updatePressure(bucket.minReqPer10s + 1);
        Properties props = createProducerProps(bootstrap);
        Random rng = new Random();

        double bestThroughput = 0;
        Optional<Config> bestConfig = Optional.empty();
        List<Double> throughputs = new ArrayList<>();

        System.out.printf("%n  ▶ Benchmarking %s (%s)%n", bucket.name(), bucket.rateRange);

        for (int round = 1; round <= rounds; round++) {
            Config testConfig = round <= 2
                ? calibration.getBestConfigForPressure(bucket)
                : calibration.suggestNext(rng);

            System.out.printf("    Round %d/%d: batch=%d, interval=%s... ",
                round, rounds, testConfig.batchSize(),
                formatInterval(testConfig.flushIntervalMicros()));

            try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
                Observation obs = runTest(producer, calibration, testConfig, bucket, durationSec);
                calibration.recordObservation(obs, bucket);

                double throughput = obs.throughputPerSec();
                throughputs.add(throughput);

                String marker = throughput > bestThroughput ? " ★" : "";
                System.out.printf("%,.1fM/s%s%n", throughput / 1_000_000.0, marker);

                if (throughput > bestThroughput) {
                    bestThroughput = throughput;
                    bestConfig = Optional.of(testConfig);
                }
            }
        }

        // Calculate statistics
        double avgThroughput = throughputs.stream().mapToDouble(d -> d).average().orElse(0);
        double stdDev = Math.sqrt(throughputs.stream()
            .mapToDouble(d -> Math.pow(d - avgThroughput, 2))
            .average().orElse(0));

        // Detect regression
        boolean regression = previousThroughput > 0 && bestThroughput < previousThroughput * 0.9;
        double changePercent = previousThroughput > 0
            ? ((bestThroughput - previousThroughput) / previousThroughput) * 100
            : 0;

        return new BucketResult(
            bucket,
            bestConfig.orElse(previousBest),
            (long) bestThroughput,
            (long) avgThroughput,
            stdDev,
            previousThroughput,
            regression,
            changePercent
        );
    }

    static Observation runTest(KafkaProducer<String, byte[]> producer,
                                BatchCalibration calibration,
                                Config testConfig,
                                PressureLevel bucket,
                                int durationSec) throws Exception {

        LongAdder kafkaSends = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();

        Path tempDb = Files.createTempFile("calib-bench", ".db");
        try (BatchCalibration tempCal = BatchCalibration.create(tempDb, 5000.0)) {
            tempCal.updatePressure(bucket.minReqPer10s + 1);
            tempCal.recordObservation(new Observation(
                testConfig.batchSize(),
                testConfig.flushIntervalMicros(),
                Long.MAX_VALUE, 0, 0, 1, Instant.now()
            ), bucket);

            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> {
                    long start = System.nanoTime();
                    byte[] combined = createBulk(batch);
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
                avgLatency * 2,
                metrics.totalRequests(),
                Instant.now()
            );
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }

    static void printHeader(String bootstrap, List<PressureLevel> buckets, int rounds, int durationSec) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      CALIBRATION BENCHMARK                                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  Kafka:     %s%n", bootstrap);
        System.out.printf("  Buckets:   %s%n", buckets.stream().map(Enum::name).toList());
        System.out.printf("  Rounds:    %d per bucket%n", rounds);
        System.out.printf("  Duration:  %ds per round%n", durationSec);
        System.out.println();
    }

    static void printBucketResult(BucketResult result) {
        String trend = result.regression
            ? String.format("⚠ REGRESSION %.1f%%", result.changePercent)
            : result.changePercent > 0
                ? String.format("↑ +%.1f%%", result.changePercent)
                : "→ stable";

        System.out.printf("    Result: %,.1fM/s (batch=%d, interval=%s) %s%n",
            result.bestThroughput / 1_000_000.0,
            result.config.batchSize(),
            formatInterval(result.config.flushIntervalMicros()),
            trend);
    }

    static void printSummary(BatchCalibration calibration, List<BucketResult> results) {
        System.out.println();
        System.out.println("═".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("═".repeat(80));

        int regressions = (int) results.stream().filter(r -> r.regression).count();
        if (regressions > 0) {
            System.out.printf("⚠ WARNING: %d regression(s) detected!%n%n", regressions);
        }

        System.out.println(calibration.getStatsReport());
    }

    static void outputJson(List<BucketResult> results, Path calibPath) throws IOException {
        Path jsonPath = calibPath.getParent().resolve("calibration-results.json");

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"buckets\": [\n");

        for (int i = 0; i < results.size(); i++) {
            BucketResult r = results.get(i);
            json.append("    {\n");
            json.append("      \"name\": \"").append(r.bucket.name()).append("\",\n");
            json.append("      \"rateRange\": \"").append(r.bucket.rateRange).append("\",\n");
            json.append("      \"targetLatencyMs\": ").append(r.bucket.targetLatencyMs).append(",\n");
            json.append("      \"batchSize\": ").append(r.config.batchSize()).append(",\n");
            json.append("      \"flushIntervalMicros\": ").append(r.config.flushIntervalMicros()).append(",\n");
            json.append("      \"throughput\": ").append(r.bestThroughput).append(",\n");
            json.append("      \"avgThroughput\": ").append(r.avgThroughput).append(",\n");
            json.append("      \"stdDev\": ").append(String.format("%.0f", r.stdDev)).append(",\n");
            json.append("      \"previousThroughput\": ").append(r.previousThroughput).append(",\n");
            json.append("      \"changePercent\": ").append(String.format("%.1f", r.changePercent)).append(",\n");
            json.append("      \"regression\": ").append(r.regression).append("\n");
            json.append("    }").append(i < results.size() - 1 ? "," : "").append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        Files.writeString(jsonPath, json.toString());
        System.out.printf("%nResults saved to: %s%n", jsonPath);
    }

    // TODO 1.4: Extract to shared BenchmarkResult interface (7 similar records across benchmark files)
    record BucketResult(
        PressureLevel bucket,
        Config config,
        long bestThroughput,
        long avgThroughput,
        double stdDev,
        long previousThroughput,
        boolean regression,
        double changePercent
    ) {}

    static Properties createProducerProps(String bootstrap) {
        Properties props = ProducerFactory.highThroughputProps(bootstrap);
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");  // Override for calibration
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(1024 * 1024));
        return props;
    }

    static String formatInterval(int micros) {
        return FormattingUtils.formatInterval(micros);
    }

    static byte[] createBulk(List<byte[]> batch) {
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
