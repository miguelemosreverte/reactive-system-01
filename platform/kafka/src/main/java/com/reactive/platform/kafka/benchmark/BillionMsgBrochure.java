package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.InlineBatcher;
import com.reactive.platform.gateway.microbatch.MessageBatcher;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

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
 * BILLION MESSAGES PER SECOND BROCHURE
 *
 * Uses InlineBatcher with ONLY send(message) API.
 * No cheating with sendBatch() - pure per-message calls.
 *
 * 5-minute benchmark:
 * - 4 minutes: ramp-up from 1K to unlimited
 * - 1 minute: sustained maximum load
 */
public class BillionMsgBrochure {

    private static final int MESSAGE_SIZE = BenchmarkConstants.MESSAGE_SIZE;
    private static final int RAMP_PHASES = 10;
    private static final int PHASE_DURATION_SEC = 24;  // 4 min / 10 phases
    private static final int SUSTAIN_DURATION_SEC = 60;

    private static final int[] TARGETS = {
        1_000,        // 1K
        10_000,       // 10K
        100_000,      // 100K
        1_000_000,    // 1M
        10_000_000,   // 10M
        50_000_000,   // 50M
        100_000_000,  // 100M
        500_000_000,  // 500M
        1_000_000_000, // 1B
        0             // Unlimited
    };

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        String outputDir = args.length > 1 ? args[1] : "reports/billion-msg";

        Files.createDirectories(Path.of(outputDir));

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║           BILLION MESSAGES PER SECOND BROCHURE                              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Using ONLY send(message) - no sendBatch() cheating!                        ║");
        System.out.println("║  Duration: 5 minutes (4 min ramp + 1 min sustain)                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        runBenchmark(bootstrap, outputDir);
    }

    static void runBenchmark(String bootstrap, String outputDir) throws Exception {
        String topic = "billion-brochure-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder totalMessages = new LongAdder();
        LongAdder kafkaSends = new LongAdder();
        LongAdder kafkaBytes = new LongAdder();
        AtomicLong peakThroughput = new AtomicLong(0);

        MessageBatcher batcher = InlineBatcher.create(data -> {
            producer.send(new ProducerRecord<>(topic, data));
            kafkaSends.increment();
            kafkaBytes.add(data.length);
        });

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        List<PhaseResult> results = new ArrayList<>();
        Instant startTime = Instant.now();

        System.out.println("Phase      Target          Achieved         Time/msg    Status");
        System.out.println("─────────────────────────────────────────────────────────────────────────────");

        // Ramp phases
        for (int phase = 0; phase < RAMP_PHASES; phase++) {
            int target = TARGETS[phase];
            boolean unlimited = (target == 0);
            int duration = phase == RAMP_PHASES - 1 ? PHASE_DURATION_SEC + SUSTAIN_DURATION_SEC : PHASE_DURATION_SEC;

            PhaseResult result = runPhase(batcher, executor, threads, target, duration, totalMessages);
            results.add(result);

            // Update peak
            if (result.achieved > peakThroughput.get()) {
                peakThroughput.set(result.achieved);
            }

            String targetStr = unlimited ? "UNLIMITED" : String.format("%,d", target);
            String status = result.achieved >= 1_000_000_000 ? "★ BILLION!" :
                           result.achieved >= 100_000_000 ? "◆ 100M+" : "●";

            System.out.printf("Phase %2d   %12s   %,14d   %9.2f ns   %s%n",
                phase + 1, targetStr, result.achieved, result.nsPerMsg, status);
        }

        System.out.println("─────────────────────────────────────────────────────────────────────────────");

        // Cleanup
        batcher.close();
        producer.flush();
        producer.close();
        executor.shutdown();

        Instant endTime = Instant.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();

        // Summary
        long total = totalMessages.sum();
        double overall = total * 1000.0 / durationMs;
        long peak = peakThroughput.get();

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS:");
        System.out.printf("  Duration:           %,d ms%n", durationMs);
        System.out.printf("  Total Messages:     %,d%n", total);
        System.out.printf("  Total Data:         %,d GB%n", total * MESSAGE_SIZE / (1024L * 1024 * 1024));
        System.out.printf("  Kafka Sends:        %,d%n", kafkaSends.sum());
        System.out.printf("  Avg Batch:          %,d KB%n", kafkaSends.sum() > 0 ? kafkaBytes.sum() / kafkaSends.sum() / 1024 : 0);
        System.out.println("─────────────────────────────────────────────────────────────────────────────");
        System.out.printf("  PEAK THROUGHPUT:    %,d msg/s%n", peak);
        System.out.printf("  OVERALL THROUGHPUT: %,.0f msg/s%n", overall);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        boolean hitBillion = peak >= 1_000_000_000;
        if (hitBillion) {
            System.out.println();
            System.out.println("  ★★★ ACHIEVED BILLION MESSAGES PER SECOND! ★★★");
            System.out.println("  ★★★ Using only send(message) - no cheating! ★★★");
        }

        // Save results
        String jsonPath = Path.of(outputDir, "results.json").toString();
        saveJson(jsonPath, startTime, endTime, results, total, overall, peak, hitBillion);
        System.out.println();
        System.out.printf("Results saved to: %s%n", jsonPath);
    }

    static PhaseResult runPhase(
            MessageBatcher batcher,
            ExecutorService executor,
            int threads,
            int targetRate,
            int durationSec,
            LongAdder totalMessages
    ) throws Exception {

        boolean unlimited = (targetRate == 0);
        CountDownLatch latch = new CountDownLatch(threads);

        // Per-thread counters - NO atomics in hot path!
        long[] threadCounts = new long[threads];

        long phaseEndNanos = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    // Initialize with some data
                    for (int i = 0; i < MESSAGE_SIZE; i++) msg[i] = (byte) (threadId + i);

                    long localCount = 0;

                    if (unlimited) {
                        // TIGHT LOOP - minimize overhead
                        // Check time every 10000 messages instead of every message
                        long end = phaseEndNanos;
                        while (true) {
                            // Burst of 10000 sends without time check
                            for (int burst = 0; burst < 10000; burst++) {
                                batcher.send(msg);
                            }
                            localCount += 10000;

                            if (System.nanoTime() >= end) break;
                        }
                    } else {
                        // Rate-limited
                        long perThreadTarget = targetRate / threads;
                        long batchSize = Math.max(1, perThreadTarget / 100);
                        long nsPerBatch = 1_000_000_000L * batchSize / perThreadTarget;

                        long batchStart = System.nanoTime();
                        long sent = 0;

                        while (System.nanoTime() < phaseEndNanos) {
                            batcher.send(msg);
                            localCount++;
                            sent++;

                            if (sent % batchSize == 0) {
                                long elapsed = System.nanoTime() - batchStart;
                                long sleepNs = nsPerBatch - elapsed;
                                if (sleepNs > 1_000_000) {
                                    Thread.sleep(sleepNs / 1_000_000);
                                }
                                batchStart = System.nanoTime();
                            }
                        }
                    }

                    threadCounts[threadId] = localCount;
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // Sum thread counts
        long count = 0;
        for (long c : threadCounts) count += c;

        long achieved = count / durationSec;
        double nsPerMsg = count > 0 ? (double) durationSec * 1_000_000_000 / count : 0;

        totalMessages.add(count);

        return new PhaseResult(targetRate, achieved, nsPerMsg);
    }

    static void saveJson(String path, Instant start, Instant end, List<PhaseResult> phases,
                         long total, double overall, long peak, boolean hitBillion) throws Exception {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("{");
            w.println("  \"brochure\": \"billion-msg\",");
            w.println("  \"name\": \"InlineBatcher - Billion Messages Per Second\",");
            w.println("  \"interface\": \"send(message) only - no sendBatch()\",");
            w.printf("  \"startTime\": \"%s\",%n", start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"endTime\": \"%s\",%n", end.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            w.printf("  \"durationMs\": %d,%n", Duration.between(start, end).toMillis());
            w.printf("  \"totalMessages\": %d,%n", total);
            w.printf("  \"overallThroughput\": %.2f,%n", overall);
            w.printf("  \"peakThroughput\": %d,%n", peak);
            w.printf("  \"hitBillion\": %s,%n", hitBillion);
            w.println("  \"phases\": [");

            for (int i = 0; i < phases.size(); i++) {
                PhaseResult p = phases.get(i);
                w.printf("    {\"target\": %d, \"achieved\": %d, \"nsPerMsg\": %.2f}%s%n",
                    p.target, p.achieved, p.nsPerMsg, i < phases.size() - 1 ? "," : "");
            }

            w.println("  ]");
            w.println("}");
        }
    }

    static KafkaProducer<String, byte[]> createProducer(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 100);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16777216);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 268435456L);
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 104857600);
        return new KafkaProducer<>(props);
    }

    record PhaseResult(int target, long achieved, double nsPerMsg) {}
}
