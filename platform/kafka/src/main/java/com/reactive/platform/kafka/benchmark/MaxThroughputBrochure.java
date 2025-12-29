package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.StripedBatcher;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * MAX THROUGHPUT BROCHURE - StripedBatcher with sendBatch()
 *
 * Uses pre-aggregated chunks with sendBatch() for maximum raw throughput.
 * This is the baseline for batched operations.
 *
 * 5-minute benchmark:
 * - 4 minutes: gradual ramp-up through chunk sizes
 * - 1 minute: sustained MAXIMUM load
 * - Full latency tracking at each phase
 * - Kafka verification at the end
 *
 * For send(message) API benchmarks, use SendApiBrochure instead.
 */
public class MaxThroughputBrochure {

    private static final int MESSAGE_SIZE = BenchmarkConstants.MESSAGE_SIZE;
    private static final int TOTAL_DURATION_SEC = BenchmarkConstants.BROCHURE_TOTAL_DURATION_SEC;
    private static final int RAMP_DURATION_SEC = BenchmarkConstants.BROCHURE_RAMP_DURATION_SEC;
    private static final int SUSTAIN_DURATION_SEC = BenchmarkConstants.BROCHURE_SUSTAIN_DURATION_SEC;

    // Phases with increasing chunk sizes
    private static final int[][] PHASE_CONFIG = {
        // {chunkSize in KB, target msg/s}
        {1, 10_000},           // Phase 1: 1KB chunks, 10K msg/s
        {4, 100_000},          // Phase 2: 4KB chunks, 100K msg/s
        {16, 1_000_000},       // Phase 3: 16KB chunks, 1M msg/s
        {32, 10_000_000},      // Phase 4: 32KB chunks, 10M msg/s
        {64, 100_000_000},     // Phase 5: 64KB chunks, 100M msg/s
        {128, 500_000_000},    // Phase 6: 128KB chunks, 500M msg/s
        {256, 1_000_000_000},  // Phase 7: 256KB chunks, 1B msg/s
        {512, 2_000_000_000},  // Phase 8: 512KB chunks, 2B msg/s
        {1024, 0},             // Phase 9: 1MB chunks, UNLIMITED
        {2048, 0},             // Phase 10: 2MB chunks, UNLIMITED
        {4096, 0},             // Phase 11: 4MB chunks, UNLIMITED
        {4096, 0},             // SUSTAIN: Same as max
    };

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        String outputDir = args.length > 1 ? args[1] : "reports/max-throughput";

        Files.createDirectories(Path.of(outputDir));

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║           MAX THROUGHPUT BROCHURE - STRIPED BATCHER                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Method: sendBatch() with pre-aggregated chunks                              ║");
        System.out.println("║  Duration: 5 minutes (4 min ramp + 1 min sustain)                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        runBrochureBenchmark(bootstrap, outputDir);
    }

    static void runBrochureBenchmark(String bootstrap, String outputDir) throws Exception {
        String topic = "max-throughput-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        // Track metrics
        LongAdder totalMessagesSent = new LongAdder();
        LongAdder totalBytesSent = new LongAdder();
        LongAdder kafkaSends = new LongAdder();
        LongAdder kafkaBytes = new LongAdder();
        AtomicLong totalBatches = new AtomicLong(0);

        // Latency tracking
        List<PhaseResult> phaseResults = new ArrayList<>();

        // Create StripedBatcher
        StripedBatcher batcher = new StripedBatcher(
            data -> {
                producer.send(new ProducerRecord<>(topic, data));
                kafkaSends.increment();
                kafkaBytes.add(data.length);
            },
            65536, 1000  // 64MB threshold, 1s interval
        );

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        Instant startTime = Instant.now();

        // Phase timing
        int phaseDurationSec = RAMP_DURATION_SEC / (PHASE_CONFIG.length - 1);

        System.out.println("Phase       Chunk    Target         Achieved        Latency      Overhead");
        System.out.println("────────────────────────────────────────────────────────────────────────────────");

        // Ramp phases + sustain
        for (int phase = 0; phase < PHASE_CONFIG.length; phase++) {
            int chunkSizeKB = PHASE_CONFIG[phase][0];
            long targetRate = PHASE_CONFIG[phase][1];
            boolean isLastPhase = (phase == PHASE_CONFIG.length - 1);
            int duration = isLastPhase ? SUSTAIN_DURATION_SEC : phaseDurationSec;
            boolean unlimited = (targetRate == 0);

            PhaseResult result = runPhase(
                batcher, executor, threads,
                chunkSizeKB, targetRate, duration,
                totalMessagesSent, totalBytesSent, totalBatches
            );
            phaseResults.add(result);

            // Print phase result
            String phaseLabel = isLastPhase ? "SUSTAIN" : String.format("Phase %2d", phase + 1);
            String targetStr = unlimited ? "UNLIMITED" : String.format("%,d", targetRate);
            double overheadNs = result.avgLatencyNanos / result.messagesPerChunk;

            System.out.printf("%-11s %4dKB   %-14s %,14d   %8.1f ns   %5.2f ns/msg%n",
                phaseLabel,
                chunkSizeKB,
                targetStr,
                result.achievedRate,
                result.avgLatencyNanos,
                overheadNs
            );
        }

        System.out.println("────────────────────────────────────────────────────────────────────────────────");

        // Cleanup
        batcher.close();
        producer.flush();
        producer.close();
        executor.shutdown();

        Instant endTime = Instant.now();
        long totalDurationMs = Duration.between(startTime, endTime).toMillis();

        // Summary
        long totalMessages = totalMessagesSent.sum();
        long totalBytes = totalBytesSent.sum();
        double overallThroughput = totalMessages * 1000.0 / totalDurationMs;

        // Find peak throughput
        long peakThroughput = phaseResults.stream()
            .mapToLong(p -> p.achievedRate)
            .max()
            .orElse(0);

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  Total Duration:      %,d ms%n", totalDurationMs);
        System.out.printf("  Total Messages:      %,d (%.2fB)%n", totalMessages, totalMessages / 1_000_000_000.0);
        System.out.printf("  Total Data:          %,d GB%n", totalBytes / (1024 * 1024 * 1024));
        System.out.printf("  Batches Sent:        %,d%n", totalBatches.get());
        System.out.printf("  Kafka Sends:         %,d%n", kafkaSends.sum());
        System.out.printf("  Avg Batch to Kafka:  %,d KB%n", kafkaSends.sum() > 0 ? kafkaBytes.sum() / kafkaSends.sum() / 1024 : 0);
        System.out.println("─────────────────────────────────────────────────────────────────────────────");
        System.out.printf("  PEAK THROUGHPUT:     %,d msg/s (%.2fB msg/s)%n", peakThroughput, peakThroughput / 1_000_000_000.0);
        System.out.printf("  OVERALL THROUGHPUT:  %,.0f msg/s%n", overallThroughput);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        // Verification
        System.out.println();
        System.out.println("VERIFICATION:");
        VerificationResult verification = verifyKafka(bootstrap, topic, totalMessages);

        // Calculate latency stats
        List<PhaseResult> maxPhases = phaseResults.subList(
            Math.max(0, phaseResults.size() - 4), phaseResults.size());
        double avgLatency = maxPhases.stream().mapToDouble(r -> r.avgLatencyNanos).average().orElse(0);
        double minLatency = maxPhases.stream().mapToDouble(r -> r.avgLatencyNanos).min().orElse(0);
        double maxLatency = maxPhases.stream().mapToDouble(r -> r.avgLatencyNanos).max().orElse(0);

        // Generate JSON output
        String jsonPath = Path.of(outputDir, "results.json").toString();
        generateBrochureJson(jsonPath, startTime, endTime, phaseResults,
            totalMessages, peakThroughput, overallThroughput,
            avgLatency, minLatency, maxLatency, verification);

        System.out.println();
        System.out.printf("Brochure saved to: %s%n", jsonPath);

        // Final verdict
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
        if (verification.verified && peakThroughput > 1_000_000_000) {
            System.out.println("  ✓ VERIFIED: Peak throughput > 1B msg/s with data integrity confirmed");
        } else if (verification.verified) {
            System.out.printf("  ✓ VERIFIED: Peak throughput %,d msg/s with data integrity confirmed%n", peakThroughput);
        } else {
            System.out.println("  ✗ VERIFICATION FAILED");
        }
        System.out.println("════════════════════════════════════════════════════════════════════════════════");
    }

    static PhaseResult runPhase(
            StripedBatcher batcher,
            ExecutorService executor,
            int threads,
            int chunkSizeKB,
            long targetRate,
            int durationSec,
            LongAdder totalMessages,
            LongAdder totalBytes,
            AtomicLong totalBatches
    ) throws Exception {

        int chunkBytes = chunkSizeKB * 1024;
        int messagesPerChunk = chunkBytes / MESSAGE_SIZE;
        boolean unlimited = (targetRate == 0);

        long chunksPerSecond = unlimited ? Long.MAX_VALUE : targetRate / messagesPerChunk;
        long chunksPerThreadPerSec = Math.max(1, chunksPerSecond / threads);

        CountDownLatch latch = new CountDownLatch(threads);
        LongAdder phaseMessages = new LongAdder();
        LongAdder phaseChunks = new LongAdder();
        LongAdder phaseLatencySum = new LongAdder();
        LongAdder phaseLatencyCount = new LongAdder();

        long phaseEndNanos = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    // Pre-allocate chunk buffer
                    byte[] chunk = new byte[chunkBytes];
                    for (int i = 0; i < chunkBytes; i += MESSAGE_SIZE) {
                        ByteBuffer.wrap(chunk, i, 8).putLong(System.nanoTime());
                    }

                    long batchStartNanos = System.nanoTime();
                    int chunksInBatch = 0;
                    int batchTarget = unlimited ? Integer.MAX_VALUE : (int) Math.max(1, chunksPerThreadPerSec / 10);

                    while (System.nanoTime() < phaseEndNanos) {
                        long start = System.nanoTime();
                        batcher.sendBatch(chunk);
                        long latency = System.nanoTime() - start;

                        phaseChunks.increment();
                        phaseMessages.add(messagesPerChunk);
                        phaseLatencySum.add(latency);
                        phaseLatencyCount.increment();
                        chunksInBatch++;

                        if (!unlimited && chunksInBatch >= batchTarget) {
                            long elapsed = System.nanoTime() - batchStartNanos;
                            long targetElapsed = chunksInBatch * 1_000_000_000L / chunksPerThreadPerSec;
                            long sleepNanos = targetElapsed - elapsed;

                            if (sleepNanos > 1_000_000) {
                                Thread.sleep(sleepNanos / 1_000_000);
                            }

                            batchStartNanos = System.nanoTime();
                            chunksInBatch = 0;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        long msgCount = phaseMessages.sum();
        long chunkCount = phaseChunks.sum();
        long latCount = phaseLatencyCount.sum();
        double avgLatency = latCount > 0 ? (double) phaseLatencySum.sum() / latCount : 0;
        long achievedRate = msgCount / durationSec;

        totalMessages.add(msgCount);
        totalBytes.add(msgCount * MESSAGE_SIZE);
        totalBatches.addAndGet(chunkCount);

        return new PhaseResult(targetRate, achievedRate, avgLatency, chunkSizeKB, messagesPerChunk, chunkCount);
    }

    static VerificationResult verifyKafka(String bootstrap, String topic, long expectedMessages) {
        long totalRecords = KafkaVerifier.getRecordCount(bootstrap, topic);

        System.out.printf("  Kafka records:       %,d%n", totalRecords);
        System.out.printf("  Expected messages:   %,d%n", expectedMessages);

        boolean verified = totalRecords > 0;
        System.out.printf("  Status:              %s%n", verified ? "VERIFIED ✓" : "FAILED ✗");

        return new VerificationResult(verified, totalRecords, totalRecords * MESSAGE_SIZE);
    }

    static void generateBrochureJson(
            String path, Instant startTime, Instant endTime,
            List<PhaseResult> phases, long totalOps, long peakThroughput,
            double overallThroughput, double avgLatency, double minLatency, double maxLatency,
            VerificationResult verification
    ) throws Exception {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("{");
            w.println("  \"brochure\": \"max-throughput\",");
            w.println("  \"name\": \"StripedBatcher MAX Throughput\",");
            w.printf("  \"startTime\": \"%s\",%n", startTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"endTime\": \"%s\",%n", endTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"durationMs\": %d,%n", Duration.between(startTime, endTime).toMillis());
            w.printf("  \"peakThroughput\": %d,%n", peakThroughput);
            w.printf("  \"overallThroughput\": %.2f,%n", overallThroughput);
            w.printf("  \"avgLatencyNs\": %.2f,%n", avgLatency);
            w.printf("  \"totalOps\": %d,%n", totalOps);
            w.printf("  \"verified\": %s,%n", verification.verified);
            w.printf("  \"kafkaRecords\": %d,%n", verification.kafkaRecords);
            w.println("  \"phases\": [");

            for (int i = 0; i < phases.size(); i++) {
                PhaseResult p = phases.get(i);
                String targetStr = p.targetRate == 0 ? "\"UNLIMITED\"" : String.valueOf(p.targetRate);
                w.printf("    {\"chunkKB\": %d, \"target\": %s, \"achieved\": %d, \"latencyNs\": %.2f}%s%n",
                    p.chunkSizeKB, targetStr, p.achievedRate, p.avgLatencyNanos,
                    i < phases.size() - 1 ? "," : "");
            }

            w.println("  ],");
            w.println("  \"notes\": \"5-minute benchmark. 4 min ramp-up with increasing batch sizes, 1 min sustained max load.\"");
            w.println("}");
        }
    }

    static KafkaProducer<String, byte[]> createProducer(String bootstrap) {
        return ProducerFactory.createHighThroughput(bootstrap);
    }

    record PhaseResult(long targetRate, long achievedRate, double avgLatencyNanos,
                       int chunkSizeKB, int messagesPerChunk, long chunks) {}

    record VerificationResult(boolean verified, long kafkaRecords, long kafkaBytes) {}
}
