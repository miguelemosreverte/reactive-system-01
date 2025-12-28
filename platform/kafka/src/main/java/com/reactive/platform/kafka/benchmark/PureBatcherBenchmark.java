package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.FastBatcher;
import com.reactive.platform.gateway.microbatch.StripedBatcher;
import com.reactive.platform.gateway.microbatch.MessageBatcher;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pure batcher benchmark - no Kafka, just measure send() overhead.
 *
 * This tells us the TRUE performance ceiling of each batcher.
 */
public class PureBatcherBenchmark {

    private static final int MESSAGE_SIZE = 64;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 PURE BATCHER BENCHMARK (no Kafka)                           ║");
        System.out.println("║              Measuring only send(message) overhead                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("Threads: %d, Message size: %d bytes, Duration: %d seconds%n%n",
            threads, MESSAGE_SIZE, DURATION_SECONDS);

        // Warmup
        System.out.println("Warming up...");
        LongAdder sink = new LongAdder();
        runTest("Warmup", new FastBatcher(d -> sink.add(d.length)), 2);
        runTest("Warmup", new StripedBatcher(d -> sink.add(d.length), 16384, 100), 2);
        System.out.println();

        // FastBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        LongAdder fastFlushes = new LongAdder();
        LongAdder fastBytes = new LongAdder();
        FastBatcher fast = new FastBatcher(d -> { fastFlushes.increment(); fastBytes.add(d.length); });
        long fastThroughput = runTest("FAST BATCHER", fast, DURATION_SECONDS);
        fast.close();
        System.out.printf("  Flushes: %,d, Total bytes: %,d MB%n", fastFlushes.sum(), fastBytes.sum() / (1024*1024));
        System.out.printf("  Throughput: %,d msg/s%n", fastThroughput);
        System.out.printf("  Per-message: %.1f ns%n", 1_000_000_000.0 / fastThroughput);
        System.out.println();

        // StripedBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        LongAdder stripedFlushes = new LongAdder();
        LongAdder stripedBytes = new LongAdder();
        StripedBatcher striped = new StripedBatcher(d -> { stripedFlushes.increment(); stripedBytes.add(d.length); }, 16384, 100);
        long stripedThroughput = runTest("STRIPED BATCHER", striped, DURATION_SECONDS);
        striped.close();
        System.out.printf("  Flushes: %,d, Total bytes: %,d MB%n", stripedFlushes.sum(), stripedBytes.sum() / (1024*1024));
        System.out.printf("  Throughput: %,d msg/s%n", stripedThroughput);
        System.out.printf("  Per-message: %.1f ns%n", 1_000_000_000.0 / stripedThroughput);
        System.out.println();

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  FastBatcher:    %,12d msg/s  (%.1f ns/msg)%n",
            fastThroughput, 1_000_000_000.0 / fastThroughput);
        System.out.printf("  StripedBatcher: %,12d msg/s  (%.1f ns/msg)%n",
            stripedThroughput, 1_000_000_000.0 / stripedThroughput);
        System.out.printf("  Winner:         %s (%.2fx)%n",
            fastThroughput > stripedThroughput ? "FastBatcher" : "StripedBatcher",
            Math.max((double) fastThroughput / stripedThroughput, (double) stripedThroughput / fastThroughput));
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runTest(String name, MessageBatcher batcher, int durationSec) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong sequence = new AtomicLong(0);

        long[] counts = new long[threads];
        long endTime = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    long count = 0;
                    while (System.nanoTime() < endTime) {
                        long seq = sequence.incrementAndGet();
                        ByteBuffer.wrap(msg).putLong(seq);
                        batcher.send(msg);
                        count++;
                    }
                    counts[threadId] = count;
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long total = 0;
        for (long c : counts) total += c;
        return total / durationSec;
    }
}
