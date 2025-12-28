package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.FastBatcher;
import com.reactive.platform.gateway.microbatch.StripedBatcher;
import com.reactive.platform.gateway.microbatch.MessageBatcher;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Maximum send() throughput benchmark.
 *
 * No sequence numbers, no ByteBuffer, just pure send() calls.
 * This measures the absolute maximum possible throughput.
 */
public class MaxSendBenchmark {

    private static final int MESSAGE_SIZE = 64;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              MAXIMUM send() THROUGHPUT BENCHMARK                            ║");
        System.out.println("║           No sequence numbers, just pure send() calls                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("Threads: %d, Message size: %d bytes, Duration: %d seconds%n%n",
            threads, MESSAGE_SIZE, DURATION_SECONDS);

        LongAdder sink = new LongAdder();

        // Warmup
        System.out.println("Warming up...");
        runTest("Warmup", new FastBatcher(d -> sink.add(d.length)), 2);
        System.out.println();

        // Test 1: FastBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("FAST BATCHER (ThreadLocal)");
        LongAdder f1 = new LongAdder();
        FastBatcher fast = new FastBatcher(d -> f1.add(d.length));
        long fastResult = runTest("FastBatcher", fast, DURATION_SECONDS);
        fast.close();
        System.out.printf("  Throughput: %,d msg/s  (%.1f ns/msg)%n",
            fastResult, 1_000_000_000.0 / fastResult);
        System.out.printf("  Data sent:  %,d MB%n", f1.sum() / (1024*1024));
        System.out.println();

        // Test 2: StripedBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("STRIPED BATCHER (Atomic)");
        LongAdder s1 = new LongAdder();
        StripedBatcher striped = new StripedBatcher(d -> s1.add(d.length), 16384, 100);
        long stripedResult = runTest("StripedBatcher", striped, DURATION_SECONDS);
        striped.close();
        System.out.printf("  Throughput: %,d msg/s  (%.1f ns/msg)%n",
            stripedResult, 1_000_000_000.0 / stripedResult);
        System.out.printf("  Data sent:  %,d MB%n", s1.sum() / (1024*1024));
        System.out.println();

        // Test 3: No-op baseline (just to see overhead of arraycopy)
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("BASELINE: Just arraycopy to local buffer (no batcher)");
        long baseline = runBaseline(DURATION_SECONDS);
        System.out.printf("  Throughput: %,d msg/s  (%.1f ns/msg)%n",
            baseline, 1_000_000_000.0 / baseline);
        System.out.println();

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  Baseline (arraycopy only): %,12d msg/s%n", baseline);
        System.out.printf("  FastBatcher:               %,12d msg/s  (%.0f%% of baseline)%n",
            fastResult, 100.0 * fastResult / baseline);
        System.out.printf("  StripedBatcher:            %,12d msg/s  (%.0f%% of baseline)%n",
            stripedResult, 100.0 * stripedResult / baseline);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runTest(String name, MessageBatcher batcher, int durationSec) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long[] counts = new long[threads];
        long endTime = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    // Pre-allocated message - no allocation in hot loop
                    byte[] msg = new byte[MESSAGE_SIZE];
                    // Put some data in it
                    for (int i = 0; i < MESSAGE_SIZE; i++) msg[i] = (byte) (threadId + i);

                    long count = 0;
                    long end = endTime;
                    while (System.nanoTime() < end) {
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

    static long runBaseline(int durationSec) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long[] counts = new long[threads];
        long endTime = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    byte[] buffer = new byte[1024 * 1024];  // 1MB buffer
                    int pos = 0;

                    long count = 0;
                    long end = endTime;
                    while (System.nanoTime() < end) {
                        // Just arraycopy, nothing else
                        if (pos + MESSAGE_SIZE > buffer.length) pos = 0;
                        System.arraycopy(msg, 0, buffer, pos, MESSAGE_SIZE);
                        pos += MESSAGE_SIZE;
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
