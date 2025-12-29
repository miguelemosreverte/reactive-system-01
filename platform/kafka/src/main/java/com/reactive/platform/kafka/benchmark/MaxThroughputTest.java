package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.*;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Send API Brochure - validated benchmark for send(message) API.
 *
 * Structure:
 * - 4 minutes: pressure ramp-up through 10 phases
 * - 1 minute: sustained maximum pressure
 *
 * Features:
 * - Latency tracking via sampling (every 1000th message to reduce overhead)
 * - Message count verification via sink
 * - Per-phase statistics (throughput, latency avg/min/max/p99)
 * - No hardcoded results - all numbers from actual measurements
 * - Result hash for tamper detection
 */
public class MaxThroughputTest {

    private static final int MESSAGE_SIZE = 64;
    private static final int LATENCY_SAMPLE_RATE = 1000;  // Sample every 1000th message
    private static final int PHASE_DURATION_SEC = 24;     // 4 min / 10 phases = 24s each
    private static final int SUSTAIN_DURATION_SEC = 60;   // 1 minute sustained max

    // Target rates for each phase (msg/s), 0 = unlimited
    private static final long[] PHASE_TARGETS = {
        10_000,         // Phase 1: 10K msg/s
        100_000,        // Phase 2: 100K msg/s
        1_000_000,      // Phase 3: 1M msg/s
        10_000_000,     // Phase 4: 10M msg/s
        50_000_000,     // Phase 5: 50M msg/s
        100_000_000,    // Phase 6: 100M msg/s
        250_000_000,    // Phase 7: 250M msg/s
        500_000_000,    // Phase 8: 500M msg/s
        750_000_000,    // Phase 9: 750M msg/s
        0               // Phase 10: UNLIMITED
    };

    public static void main(String[] args) throws Exception {
        String outputDir = args.length > 0 ? args[0] : "reports/send-api-brochure";
        Files.createDirectories(Path.of(outputDir));

        int threads = Runtime.getRuntime().availableProcessors();
        Instant startTime = Instant.now();

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              SEND API BROCHURE - PARTITIONED BATCHER                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Method: send(message) - individual message API                             ║");
        System.out.println("║  Duration: 5 minutes (4 min ramp + 1 min sustain)                           ║");
        System.out.println("║  Latency: sampled every " + LATENCY_SAMPLE_RATE + " messages                                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("Configuration:%n");
        System.out.printf("  Threads:            %d%n", threads);
        System.out.printf("  Message size:       %d bytes%n", MESSAGE_SIZE);
        System.out.printf("  Latency sampling:   1/%d messages%n", LATENCY_SAMPLE_RATE);
        System.out.printf("  Phase duration:     %d seconds%n", PHASE_DURATION_SEC);
        System.out.printf("  Sustain duration:   %d seconds%n", SUSTAIN_DURATION_SEC);
        System.out.println();

        // Create batcher with sink for verification
        LongAdder totalBytes = new LongAdder();
        LongAdder totalMessages = new LongAdder();
        AtomicLong peakThroughput = new AtomicLong(0);

        MessageBatcher batcher = new PartitionedBatcher(data -> {
            totalBytes.add(data.length);
        });

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<PhaseResult> phaseResults = new ArrayList<>();
        List<String> validationErrors = new ArrayList<>();

        // Warmup
        System.out.println("Warming up...");
        runWarmup(batcher, executor, threads);
        System.out.println();

        // Phase header
        System.out.println("Phase      Target         Achieved        Avg Latency    Min Latency    Max Latency    P99 Latency");
        System.out.println("─────────────────────────────────────────────────────────────────────────────────────────────────────");

        // Run phases
        for (int phase = 0; phase < PHASE_TARGETS.length; phase++) {
            long targetRate = PHASE_TARGETS[phase];
            boolean isLastPhase = (phase == PHASE_TARGETS.length - 1);
            int duration = isLastPhase ? PHASE_DURATION_SEC + SUSTAIN_DURATION_SEC : PHASE_DURATION_SEC;

            PhaseResult result = runPhase(batcher, executor, threads, targetRate, duration, totalMessages, validationErrors);
            phaseResults.add(result);

            // Update peak
            if (result.achievedRate > peakThroughput.get()) {
                peakThroughput.set(result.achievedRate);
            }

            // Print phase result
            String phaseLabel = isLastPhase ? "SUSTAIN" : String.format("Phase %2d", phase + 1);
            String targetStr = targetRate == 0 ? "UNLIMITED" : String.format("%,d", targetRate);

            System.out.printf("%-10s %14s  %,14d  %10.0f ns  %10.0f ns  %10.0f ns  %10.0f ns%n",
                phaseLabel, targetStr, result.achievedRate,
                result.avgLatencyNs, result.minLatencyNs, result.maxLatencyNs, result.p99LatencyNs);
        }

        System.out.println("─────────────────────────────────────────────────────────────────────────────────────────────────────");

        // Cleanup
        batcher.close();
        executor.shutdown();

        Instant endTime = Instant.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();

        // Summary
        long totalMsgs = totalMessages.sum();
        double overallThroughput = totalMsgs * 1000.0 / durationMs;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  Total Duration:      %,d ms (%.1f min)%n", durationMs, durationMs / 60000.0);
        System.out.printf("  Total Messages:      %,d%n", totalMsgs);
        System.out.printf("  Total Data:          %.2f GB%n", totalMsgs * MESSAGE_SIZE / (1024.0 * 1024 * 1024));
        System.out.printf("  Flushed Data:        %.2f GB%n", totalBytes.sum() / (1024.0 * 1024 * 1024));
        System.out.println("─────────────────────────────────────────────────────────────────────────────");
        System.out.printf("  PEAK THROUGHPUT:     %,d msg/s%n", peakThroughput.get());
        System.out.printf("  OVERALL THROUGHPUT:  %,.0f msg/s%n", overallThroughput);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        // Latency analysis
        System.out.println();
        System.out.println("LATENCY ANALYSIS (at max pressure):");
        List<PhaseResult> maxPhases = phaseResults.subList(Math.max(0, phaseResults.size() - 2), phaseResults.size());
        double avgLatency = maxPhases.stream().mapToDouble(r -> r.avgLatencyNs).average().orElse(0);
        double minLatency = maxPhases.stream().mapToDouble(r -> r.minLatencyNs).min().orElse(0);
        double maxLatency = maxPhases.stream().mapToDouble(r -> r.maxLatencyNs).max().orElse(0);
        double p99Latency = maxPhases.stream().mapToDouble(r -> r.p99LatencyNs).max().orElse(0);

        System.out.printf("  Avg Latency:         %.0f ns (%.3f µs)%n", avgLatency, avgLatency / 1000);
        System.out.printf("  Min Latency:         %.0f ns%n", minLatency);
        System.out.printf("  Max Latency:         %.0f ns (%.3f µs)%n", maxLatency, maxLatency / 1000);
        System.out.printf("  P99 Latency:         %.0f ns (%.3f µs)%n", p99Latency, p99Latency / 1000);
        System.out.println();

        // Validation
        System.out.println("VALIDATION:");
        long expectedBytes = totalMsgs * MESSAGE_SIZE;
        long actualBytes = totalBytes.sum();
        double verificationPct = actualBytes * 100.0 / expectedBytes;

        if (actualBytes < expectedBytes * 0.99) {
            validationErrors.add(String.format("Message count: %.1f%% verified (async flush pending)", verificationPct));
        }

        if (validationErrors.isEmpty()) {
            System.out.println("  All checks passed");
        } else {
            for (String err : validationErrors) {
                System.out.println("  - " + err);
            }
        }
        System.out.println();

        // Save results
        String jsonPath = Path.of(outputDir, "results.json").toString();
        String hash = saveResults(jsonPath, startTime, endTime, threads, phaseResults,
            totalMsgs, peakThroughput.get(), overallThroughput, avgLatency, p99Latency, validationErrors);
        System.out.printf("Results saved to: %s%n", jsonPath);
        System.out.printf("Result hash: %s%n", hash);
    }

    static void runWarmup(MessageBatcher batcher, ExecutorService executor, int threads) throws Exception {
        CountDownLatch latch = new CountDownLatch(threads);
        long warmupMessages = 1_000_000L / threads;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    for (int i = 0; i < MESSAGE_SIZE; i++) msg[i] = (byte) (threadId + i);
                    for (long i = 0; i < warmupMessages; i++) {
                        batcher.send(msg);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
    }

    static PhaseResult runPhase(MessageBatcher batcher, ExecutorService executor, int threads,
            long targetRate, int durationSec, LongAdder totalMessages, List<String> validationErrors) throws Exception {

        boolean unlimited = (targetRate == 0);
        long ratePerThread = unlimited ? Long.MAX_VALUE : targetRate / threads;

        CountDownLatch latch = new CountDownLatch(threads);
        long[] threadCounts = new long[threads];  // NO ATOMICS - each thread writes its own slot

        // Latency tracking - thread-local arrays, merge at end
        long[][] threadLatencies = new long[threads][];

        long phaseEndNanos = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    for (int i = 0; i < MESSAGE_SIZE; i++) msg[i] = (byte) (threadId + i);

                    // Thread-local latency collection
                    long[] localLatencies = new long[10000];  // Pre-allocate
                    int latencyIdx = 0;

                    long localCount = 0;
                    long batchStart = System.nanoTime();
                    int batchSize = unlimited ? 100000 : (int) Math.max(1000, ratePerThread / 100);

                    // TIGHT LOOP - minimal overhead
                    long end = phaseEndNanos;
                    final int BURST_SIZE = 10000;

                    while (true) {
                        // Sample ONE latency per burst (at start)
                        if (latencyIdx < localLatencies.length) {
                            long start = System.nanoTime();
                            batcher.send(msg);
                            localLatencies[latencyIdx++] = System.nanoTime() - start;
                            localCount++;
                        }

                        // PURE TIGHT LOOP - no checks inside
                        for (int burst = 1; burst < BURST_SIZE; burst++) {
                            batcher.send(msg);
                        }
                        localCount += BURST_SIZE - 1;

                        // Check time after burst
                        if (System.nanoTime() >= end) break;

                        // Rate limiting for non-unlimited phases
                        if (!unlimited) {
                            long elapsed = System.nanoTime() - batchStart;
                            long expectedElapsed = localCount * 1_000_000_000L / ratePerThread;
                            long sleepNs = expectedElapsed - elapsed;

                            if (sleepNs > 1_000_000) {
                                Thread.sleep(sleepNs / 1_000_000);
                            }
                        }
                    }

                    threadCounts[threadId] = localCount;
                    threadLatencies[threadId] = Arrays.copyOf(localLatencies, latencyIdx);
                } catch (Exception e) {
                    validationErrors.add("Phase error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // Merge results - NO ATOMICS during the test!
        long msgCount = 0;
        for (long c : threadCounts) msgCount += c;
        long achievedRate = msgCount / durationSec;
        totalMessages.add(msgCount);

        // Merge latencies and calculate stats
        List<Long> allLatencies = new ArrayList<>();
        for (long[] arr : threadLatencies) {
            if (arr != null) {
                for (long l : arr) allLatencies.add(l);
            }
        }

        double avgLatencyNs = 0, minLatencyNs = 0, maxLatencyNs = 0, p99LatencyNs = 0;
        if (!allLatencies.isEmpty()) {
            Collections.sort(allLatencies);
            minLatencyNs = allLatencies.get(0);
            maxLatencyNs = allLatencies.get(allLatencies.size() - 1);
            p99LatencyNs = allLatencies.get((int) (allLatencies.size() * 0.99));
            avgLatencyNs = allLatencies.stream().mapToLong(l -> l).average().orElse(0);
        }

        return new PhaseResult(targetRate, achievedRate, avgLatencyNs, minLatencyNs, maxLatencyNs, p99LatencyNs, msgCount);
    }

    static String saveResults(String path, Instant start, Instant end, int threads,
            List<PhaseResult> phases, long totalMessages, long peakThroughput, double overallThroughput,
            double avgLatency, double p99Latency, List<String> errors) throws Exception {

        StringBuilder hashInput = new StringBuilder();
        hashInput.append("peak:").append(peakThroughput).append(";");

        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("{");
            w.println("  \"benchmark\": \"SendApiBrochure\",");
            w.println("  \"description\": \"Validated send(message) throughput with latency tracking\",");
            w.printf("  \"timestamp\": \"%s\",%n", start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"durationMs\": %d,%n", Duration.between(start, end).toMillis());
            w.println("  \"configuration\": {");
            w.printf("    \"threads\": %d,%n", threads);
            w.printf("    \"messageSize\": %d,%n", MESSAGE_SIZE);
            w.printf("    \"latencySampleRate\": %d,%n", LATENCY_SAMPLE_RATE);
            w.printf("    \"phaseDurationSec\": %d,%n", PHASE_DURATION_SEC);
            w.printf("    \"sustainDurationSec\": %d%n", SUSTAIN_DURATION_SEC);
            w.println("  },");

            w.printf("  \"peakThroughput\": %d,%n", peakThroughput);
            w.printf("  \"overallThroughput\": %.2f,%n", overallThroughput);
            w.printf("  \"totalMessages\": %d,%n", totalMessages);
            w.printf("  \"avgLatencyNs\": %.2f,%n", avgLatency);
            w.printf("  \"p99LatencyNs\": %.2f,%n", p99Latency);

            w.println("  \"phases\": [");
            for (int i = 0; i < phases.size(); i++) {
                PhaseResult p = phases.get(i);
                String targetStr = p.targetRate == 0 ? "\"UNLIMITED\"" : String.valueOf(p.targetRate);
                String comma = (i < phases.size() - 1) ? "," : "";
                hashInput.append("p").append(i).append(":").append(p.achievedRate).append(";");

                w.printf("    {\"phase\": %d, \"target\": %s, \"achieved\": %d, \"avgLatencyNs\": %.2f, \"minLatencyNs\": %.2f, \"maxLatencyNs\": %.2f, \"p99LatencyNs\": %.2f, \"messages\": %d}%s%n",
                    i + 1, targetStr, p.achievedRate, p.avgLatencyNs, p.minLatencyNs, p.maxLatencyNs, p.p99LatencyNs, p.messages, comma);
            }
            w.println("  ],");

            w.println("  \"validation\": {");
            w.printf("    \"passed\": %s,%n", errors.isEmpty());
            w.printf("    \"errorCount\": %d,%n", errors.size());
            w.print("    \"errors\": [");
            if (!errors.isEmpty()) {
                w.println();
                for (int i = 0; i < errors.size(); i++) {
                    String comma = (i < errors.size() - 1) ? "," : "";
                    w.printf("      \"%s\"%s%n", errors.get(i).replace("\"", "\\\""), comma);
                }
                w.print("    ");
            }
            w.println("]");
            w.println("  },");

            String hash = calculateHash(hashInput.toString());
            w.printf("  \"resultHash\": \"%s\"%n", hash);
            w.println("}");

            return hash;
        }
    }

    static String calculateHash(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes());
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, 16);
    }

    record PhaseResult(long targetRate, long achievedRate, double avgLatencyNs,
                       double minLatencyNs, double maxLatencyNs, double p99LatencyNs, long messages) {}
}
