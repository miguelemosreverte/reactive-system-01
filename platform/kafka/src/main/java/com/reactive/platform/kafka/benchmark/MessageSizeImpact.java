package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.DirectFlushBatcher;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * How does message size affect throughput?
 *
 * Theory: Smaller messages = faster arraycopy = higher msg/s
 */
public class MessageSizeImpact {

    private static final int DURATION_SECONDS = 3;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                   MESSAGE SIZE vs THROUGHPUT                                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("Threads: %d%n%n", threads);

        System.out.println("Size (bytes)    Throughput (msg/s)    Time/msg (ns)    Data Rate (GB/s)");
        System.out.println("────────────────────────────────────────────────────────────────────────");

        int[] sizes = {8, 16, 32, 64, 128, 256, 512, 1024};

        for (int size : sizes) {
            long throughput = runTest(size);
            double timePerMsg = 1_000_000_000.0 / throughput;
            double dataRate = (double) throughput * size / (1024 * 1024 * 1024);
            System.out.printf("%12d    %,18d    %13.1f    %15.2f%n",
                size, throughput, timePerMsg, dataRate);
        }

        System.out.println("────────────────────────────────────────────────────────────────────────");
        System.out.println();
        System.out.println("Key insight: Smaller messages = higher msg/s, but total data rate stays similar.");
        System.out.println("This is the MEMORY BANDWIDTH limit.");
    }

    static long runTest(int messageSize) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        LongAdder sink = new LongAdder();
        DirectFlushBatcher batcher = new DirectFlushBatcher(d -> sink.add(d.length));

        long[] counts = new long[threads];
        long endTime = System.nanoTime() + DURATION_SECONDS * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[messageSize];
                    for (int i = 0; i < messageSize; i++) msg[i] = (byte) (threadId + i);

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
        batcher.close();

        long total = 0;
        for (long c : counts) total += c;
        return total / DURATION_SECONDS;
    }
}
