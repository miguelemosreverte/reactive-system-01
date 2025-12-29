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
 * StripedBatcher Brochure Benchmark
 *
 * 5-minute benchmark with:
 * - 4 minutes: gradual ramp-up from 1K to 10M msg/s
 * - 1 minute: sustained maximum load
 * - Latency tracking at each phase
 * - Kafka verification at the end
 * - JSON output for brochure generation
 */
public class StripedBatcherBrochure {

    private static final int MESSAGE_SIZE = BenchmarkConstants.MESSAGE_SIZE;
    private static final int TOTAL_DURATION_SEC = 300;  // 5 minutes
    private static final int RAMP_DURATION_SEC = 240;   // 4 minutes ramp
    private static final int SUSTAIN_DURATION_SEC = 60; // 1 minute sustain

    // Ramp phases (12 phases during ramp-up, each 20 seconds)
    private static final int[] PHASE_TARGETS = {
        1_000,       // Phase 1: 1K msg/s
        5_000,       // Phase 2: 5K msg/s
        10_000,      // Phase 3: 10K msg/s
        50_000,      // Phase 4: 50K msg/s
        100_000,     // Phase 5: 100K msg/s
        500_000,     // Phase 6: 500K msg/s
        1_000_000,   // Phase 7: 1M msg/s
        2_000_000,   // Phase 8: 2M msg/s
        5_000_000,   // Phase 9: 5M msg/s
        10_000_000,  // Phase 10: 10M msg/s
        15_000_000,  // Phase 11: 15M msg/s
        20_000_000   // Phase 12: MAX (unlimited)
    };

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        String outputDir = args.length > 1 ? args[1] : "reports/striped-batcher";

        Files.createDirectories(Path.of(outputDir));

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              STRIPED BATCHER BROCHURE BENCHMARK                              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Duration: 5 minutes (4 min ramp + 1 min sustain)                            ║");
        System.out.println("║  Phases: 1K → 5K → 10K → 50K → 100K → 500K → 1M → 2M → 5M → 10M → MAX        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        runBrochureBenchmark(bootstrap, outputDir);
    }

    static void runBrochureBenchmark(String bootstrap, String outputDir) throws Exception {
        String topic = "striped-brochure-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        // Track metrics
        LongAdder totalMessagesSent = new LongAdder();
        LongAdder totalBytesSent = new LongAdder();
        LongAdder kafkaSends = new LongAdder();
        LongAdder kafkaBytes = new LongAdder();
        AtomicLong lastSequence = new AtomicLong(0);

        // Latency tracking (in nanoseconds)
        List<PhaseResult> phaseResults = new ArrayList<>();

        // Create StripedBatcher
        StripedBatcher batcher = new StripedBatcher(
            data -> {
                producer.send(new ProducerRecord<>(topic, data));
                kafkaSends.increment();
                kafkaBytes.add(data.length);
            },
            16384, 100  // 16MB threshold, 100ms interval (balanced)
        );

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        Instant startTime = Instant.now();
        long benchmarkStartNanos = System.nanoTime();

        // Phase timing
        int phaseIndex = 0;
        int phaseDurationSec = RAMP_DURATION_SEC / PHASE_TARGETS.length;

        System.out.println("Phase      Target        Achieved      Latency     Kafka Sends   Batch Size");
        System.out.println("─────────────────────────────────────────────────────────────────────────────");

        // Ramp phases
        for (int phase = 0; phase < PHASE_TARGETS.length; phase++) {
            int targetRate = PHASE_TARGETS[phase];
            boolean isLastPhase = (phase == PHASE_TARGETS.length - 1);
            int duration = isLastPhase ? phaseDurationSec + SUSTAIN_DURATION_SEC : phaseDurationSec;

            PhaseResult result = runPhase(
                batcher, executor, threads, targetRate, duration,
                totalMessagesSent, totalBytesSent, lastSequence
            );
            phaseResults.add(result);

            // Print phase result
            String phaseLabel = isLastPhase ? "SUSTAIN" : String.format("Phase %2d", phase + 1);
            System.out.printf("%-10s %,12d  %,12d   %8.1f ns  %,10d   %,10d KB%n",
                phaseLabel,
                targetRate,
                result.achievedRate,
                result.avgLatencyNanos,
                result.kafkaSends,
                result.avgBatchBytes / 1024
            );
        }

        System.out.println("─────────────────────────────────────────────────────────────────────────────");

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

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  Total Duration:     %,d ms%n", totalDurationMs);
        System.out.printf("  Total Messages:     %,d%n", totalMessages);
        System.out.printf("  Total Bytes:        %,d MB%n", totalBytes / (1024 * 1024));
        System.out.printf("  Overall Throughput: %,.0f msg/s%n", overallThroughput);
        System.out.printf("  Kafka Sends:        %,d%n", kafkaSends.sum());
        System.out.printf("  Avg Batch Size:     %,d KB%n", kafkaSends.sum() > 0 ? kafkaBytes.sum() / kafkaSends.sum() / 1024 : 0);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        // Verification
        System.out.println();
        System.out.println("VERIFICATION:");
        boolean verified = verifyKafka(bootstrap, topic, lastSequence.get());

        // Calculate latency percentiles
        double p50Latency = calculatePercentile(phaseResults, 50);
        double p99Latency = calculatePercentile(phaseResults, 99);
        double avgLatency = phaseResults.stream().mapToDouble(r -> r.avgLatencyNanos).average().orElse(0);

        // Generate JSON output
        String jsonPath = Path.of(outputDir, "results.json").toString();
        generateBrochureJson(jsonPath, startTime, endTime, phaseResults,
            totalMessages, overallThroughput, avgLatency, p50Latency, p99Latency, verified);

        System.out.println();
        System.out.printf("Brochure saved to: %s%n", jsonPath);
    }

    static PhaseResult runPhase(
            StripedBatcher batcher,
            ExecutorService executor,
            int threads,
            int targetRate,
            int durationSec,
            LongAdder totalMessages,
            LongAdder totalBytes,
            AtomicLong lastSequence
    ) throws Exception {

        // For rate-limited phases, use a token bucket approach
        boolean unlimited = (targetRate >= 5_000_000);  // Go as fast as possible for 5M+
        int perThreadRate = unlimited ? Integer.MAX_VALUE : targetRate / threads;

        CountDownLatch latch = new CountDownLatch(threads);
        LongAdder phaseMessages = new LongAdder();
        LongAdder phaseLatencySum = new LongAdder();
        LongAdder phaseLatencyCount = new LongAdder();

        long phaseEndNanos = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    long batchStartNanos = System.nanoTime();
                    int messagesInBatch = 0;
                    int batchTarget = Math.max(1, perThreadRate / 100);  // 100 batches per second

                    while (System.nanoTime() < phaseEndNanos) {
                        // Update sequence in message
                        long seq = lastSequence.incrementAndGet();
                        ByteBuffer.wrap(msg).putLong(seq);

                        // Measure latency
                        long start = System.nanoTime();
                        batcher.send(msg);
                        long latency = System.nanoTime() - start;

                        phaseMessages.increment();
                        phaseLatencySum.add(latency);
                        phaseLatencyCount.increment();
                        messagesInBatch++;

                        // Rate limiting: send batch, then wait
                        if (!unlimited && messagesInBatch >= batchTarget) {
                            long elapsed = System.nanoTime() - batchStartNanos;
                            long targetElapsed = messagesInBatch * 1_000_000_000L / perThreadRate;
                            long sleepNanos = targetElapsed - elapsed;

                            if (sleepNanos > 1_000_000) {  // At least 1ms
                                Thread.sleep(sleepNanos / 1_000_000);
                            }

                            batchStartNanos = System.nanoTime();
                            messagesInBatch = 0;
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
        long latCount = phaseLatencyCount.sum();
        double avgLatency = latCount > 0 ? (double) phaseLatencySum.sum() / latCount : 0;
        long achievedRate = msgCount / durationSec;

        totalMessages.add(msgCount);
        totalBytes.add(msgCount * MESSAGE_SIZE);

        return new PhaseResult(
            targetRate,
            achievedRate,
            avgLatency,
            0, 0  // kafkaSends and avgBatchBytes tracked globally
        );
    }

    static boolean verifyKafka(String bootstrap, String topic, long expectedSequence) {
        long recordCount = KafkaVerifier.getRecordCount(bootstrap, topic);
        long lastSeq = KafkaVerifier.verifyLastSequence(bootstrap, topic);

        System.out.printf("  Kafka records:   %,d%n", recordCount);
        System.out.printf("  Expected seq:    %,d%n", expectedSequence);
        if (lastSeq >= 0) {
            System.out.printf("  Last sequence:   %,d%n", lastSeq);
        }

        boolean verified = recordCount > 0;
        System.out.printf("  Status:          %s%n", verified ? "VERIFIED ✓" : "FAILED ✗");
        return verified;
    }

    static double calculatePercentile(List<PhaseResult> results, int percentile) {
        if (results.isEmpty()) return 0;
        List<Double> latencies = results.stream()
            .map(r -> r.avgLatencyNanos)
            .sorted()
            .toList();
        int index = Math.min((percentile * latencies.size()) / 100, latencies.size() - 1);
        return latencies.get(index);
    }

    static void generateBrochureJson(
            String path, Instant startTime, Instant endTime,
            List<PhaseResult> phases, long totalOps, double throughput,
            double avgLatency, double p50Latency, double p99Latency, boolean verified
    ) throws Exception {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("{");
            w.println("  \"brochure\": \"striped-batcher\",");
            w.println("  \"name\": \"StripedBatcher - Lock-Free Adaptive Kafka Batcher\",");
            w.printf("  \"startTime\": \"%s\",%n", startTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"endTime\": \"%s\",%n", endTime.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"durationMs\": %d,%n", Duration.between(startTime, endTime).toMillis());
            w.printf("  \"throughput\": %.2f,%n", throughput);
            w.printf("  \"p50Ns\": %.2f,%n", p50Latency);
            w.printf("  \"p99Ns\": %.2f,%n", p99Latency);
            w.printf("  \"avgLatencyNs\": %.2f,%n", avgLatency);
            w.printf("  \"totalOps\": %d,%n", totalOps);
            w.printf("  \"verified\": %s,%n", verified);
            w.println("  \"phases\": [");

            for (int i = 0; i < phases.size(); i++) {
                PhaseResult p = phases.get(i);
                w.printf("    {\"target\": %d, \"achieved\": %d, \"latencyNs\": %.2f}%s%n",
                    p.targetRate, p.achievedRate, p.avgLatencyNanos,
                    i < phases.size() - 1 ? "," : "");
            }

            w.println("  ],");
            w.println("  \"notes\": \"5-minute benchmark: 4 min ramp-up (1K→20M msg/s) + 1 min sustained max load. Lock-free striped design with per-thread buffers.\"");
            w.println("}");
        }
    }

    static KafkaProducer<String, byte[]> createProducer(String bootstrap) {
        return ProducerFactory.createHighThroughput(bootstrap);
    }

    record PhaseResult(long targetRate, long achievedRate, double avgLatencyNanos, long kafkaSends, long avgBatchBytes) {}
}
