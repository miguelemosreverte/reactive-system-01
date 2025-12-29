package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.DirectFlushBatcher;
import com.reactive.platform.gateway.microbatch.FastBatcher;
import com.reactive.platform.gateway.microbatch.InlineBatcher;
import com.reactive.platform.gateway.microbatch.StripedBatcher;
import com.reactive.platform.gateway.microbatch.UltraFastBatcher;
import com.reactive.platform.gateway.microbatch.MessageBatcher;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tight loop benchmark - minimize benchmark overhead.
 *
 * Instead of checking System.nanoTime() every message,
 * we send a fixed number of messages and time the whole batch.
 */
public class TightLoopBenchmark {

    private static final int MESSAGE_SIZE = BenchmarkConstants.MESSAGE_SIZE;
    private static final long MESSAGES_PER_THREAD = 100_000_000L;  // 100M per thread

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    TIGHT LOOP BENCHMARK                                     ║");
        System.out.println("║           No per-message timing, just count and measure total               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        int threads = Runtime.getRuntime().availableProcessors();
        long totalMessages = MESSAGES_PER_THREAD * threads;
        System.out.printf("Threads: %d, Messages per thread: %,d, Total: %,d%n%n",
            threads, MESSAGES_PER_THREAD, totalMessages);

        LongAdder sink = new LongAdder();

        // Warmup
        System.out.println("Warming up...");
        runBatcher(new FastBatcher(d -> sink.add(d.length)), 10_000_000);
        System.out.println();

        // Test 1: No-op baseline (just loop)
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("NO-OP - Just loop, no arraycopy");
        long noopResult = runNoop();
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / noopResult, (double) noopResult * 1_000_000 / totalMessages);
        System.out.println();

        // Test 2: Pure arraycopy baseline
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("BASELINE - Pure arraycopy, no batcher");
        long baselineResult = runBaseline();
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / baselineResult, (double) baselineResult * 1_000_000 / totalMessages);
        System.out.println();

        // Test 3: UltraFastBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("ULTRA FAST BATCHER - Thread ID hashing, 256 stripes");
        long ultraResult = runBatcher(new UltraFastBatcher(d -> sink.add(d.length)), MESSAGES_PER_THREAD * threads);
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / ultraResult, (double) ultraResult * 1_000_000 / totalMessages);
        System.out.println();

        // Test 4: StripedBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("STRIPED BATCHER - Original design");
        long stripedResult = runBatcher(new StripedBatcher(d -> sink.add(d.length), 16384, 100), MESSAGES_PER_THREAD * threads);
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / stripedResult, (double) stripedResult * 1_000_000 / totalMessages);
        System.out.println();

        // Test 5: FastBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("FAST BATCHER - ThreadLocal");
        long fastResult = runBatcher(new FastBatcher(d -> sink.add(d.length)), MESSAGES_PER_THREAD * threads);
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / fastResult, (double) fastResult * 1_000_000 / totalMessages);
        System.out.println();

        // Test 6: InlineBatcher (factory for PartitionedBatcher)
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("INLINE BATCHER - Factory for PartitionedBatcher");
        long inlineResult = runBatcher(InlineBatcher.create(d -> sink.add(d.length)), MESSAGES_PER_THREAD * threads);
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / inlineResult, (double) inlineResult * 1_000_000 / totalMessages);
        System.out.println();

        // Test 7: DirectFlushBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("DIRECT FLUSH BATCHER - No atomics, direct flush");
        long directResult = runBatcher(new DirectFlushBatcher(d -> sink.add(d.length)), MESSAGES_PER_THREAD * threads);
        System.out.printf("  Throughput:    %,d msg/s  (%.2f ns/msg)%n",
            totalMessages * 1000 / directResult, (double) directResult * 1_000_000 / totalMessages);
        System.out.println();

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY (sorted by throughput):");
        System.out.printf("  No-op loop:         %,12d msg/s%n", totalMessages * 1000 / noopResult);
        System.out.printf("  Arraycopy only:     %,12d msg/s%n", totalMessages * 1000 / baselineResult);
        System.out.printf("  DirectFlushBatcher: %,12d msg/s  (%.0f%% of baseline)%n",
            totalMessages * 1000 / directResult, 100.0 * baselineResult / directResult);
        System.out.printf("  FastBatcher:        %,12d msg/s  (%.0f%% of baseline)%n",
            totalMessages * 1000 / fastResult, 100.0 * baselineResult / fastResult);
        System.out.printf("  InlineBatcher:      %,12d msg/s  (%.0f%% of baseline)%n",
            totalMessages * 1000 / inlineResult, 100.0 * baselineResult / inlineResult);
        System.out.printf("  StripedBatcher:     %,12d msg/s  (%.0f%% of baseline)%n",
            totalMessages * 1000 / stripedResult, 100.0 * baselineResult / stripedResult);
        System.out.printf("  UltraFastBatcher:   %,12d msg/s  (%.0f%% of baseline)%n",
            totalMessages * 1000 / ultraResult, 100.0 * baselineResult / ultraResult);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runBatcher(MessageBatcher batcher, long totalMessages) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        long messagesPerThread = totalMessages / threads;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long start = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    for (int i = 0; i < MESSAGE_SIZE; i++) msg[i] = (byte) (threadId + i);

                    // Tight loop - no per-iteration timing
                    for (long i = 0; i < messagesPerThread; i++) {
                        batcher.send(msg);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        batcher.close();

        return System.currentTimeMillis() - start;
    }

    static long runBaseline() throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long start = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    byte[] buffer = new byte[1024 * 1024];
                    int pos = 0;

                    for (long i = 0; i < MESSAGES_PER_THREAD; i++) {
                        if (pos + MESSAGE_SIZE > buffer.length) pos = 0;
                        System.arraycopy(msg, 0, buffer, pos, MESSAGE_SIZE);
                        pos += MESSAGE_SIZE;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        return System.currentTimeMillis() - start;
    }

    static long runNoop() throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long start = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    long sum = 0;  // Prevent loop elimination
                    for (long i = 0; i < MESSAGES_PER_THREAD; i++) {
                        sum += i;
                    }
                    if (sum == 0) System.out.println("never");  // Use sum
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        return System.currentTimeMillis() - start;
    }
}
