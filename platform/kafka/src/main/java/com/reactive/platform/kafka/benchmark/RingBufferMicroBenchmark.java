package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.*;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Micro-benchmark comparing queue implementations.
 */
public class RingBufferMicroBenchmark {

    private static final int DURATION_SEC = 5;

    public static void main(String[] args) throws Exception {
        System.out.println("Ring Buffer Micro-Benchmark");
        System.out.println("═".repeat(60));

        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("Threads: %d, Duration: %ds per test%n%n", threads, DURATION_SEC);

        // Test 1: ConcurrentLinkedQueue baseline
        System.out.print("1. ConcurrentLinkedQueue (baseline)... ");
        double clqThroughput = testCLQ(threads);
        System.out.printf("%,.0f msg/s%n", clqThroughput);

        // Test 2: FastRingBuffer
        System.out.print("2. FastRingBuffer... ");
        double frbThroughput = testFastRingBuffer(threads);
        System.out.printf("%,.0f msg/s (%.1fx)%n", frbThroughput, frbThroughput / clqThroughput);

        // Test 3: MicrobatchCollector with FastRingBuffer
        System.out.print("3. MicrobatchCollector (no Kafka)... ");
        double collectorThroughput = testCollector(threads);
        System.out.printf("%,.0f msg/s%n", collectorThroughput);

        System.out.println("\n" + "═".repeat(60));
        System.out.printf("FastRingBuffer improvement: %.1fx over CLQ%n", frbThroughput / clqThroughput);
    }

    static double testCLQ(int threads) throws Exception {
        ConcurrentLinkedQueue<byte[]> queue = new ConcurrentLinkedQueue<>();
        LongAdder produced = new LongAdder();
        LongAdder consumed = new LongAdder();

        ExecutorService exec = Executors.newFixedThreadPool(threads + 1);
        long deadline = System.currentTimeMillis() + DURATION_SEC * 1000L;
        byte[] item = new byte[100];

        // Consumer
        exec.submit(() -> {
            while (System.currentTimeMillis() < deadline) {
                if (queue.poll() != null) consumed.increment();
                else Thread.onSpinWait();
            }
            // Drain remaining
            while (queue.poll() != null) consumed.increment();
        });

        // Producers
        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                while (System.currentTimeMillis() < deadline) {
                    queue.offer(item);
                    produced.increment();
                }
            });
        }

        exec.shutdown();
        exec.awaitTermination(DURATION_SEC + 5, TimeUnit.SECONDS);

        return consumed.sum() * 1000.0 / (DURATION_SEC * 1000);
    }

    static double testFastRingBuffer(int threads) throws Exception {
        // Use partitioned buffers like MicrobatchCollector (SPSC pattern per partition)
        int partitions = threads;
        @SuppressWarnings("unchecked")
        FastRingBuffer<byte[]>[] buffers = new FastRingBuffer[partitions];
        for (int i = 0; i < partitions; i++) {
            buffers[i] = new FastRingBuffer<>(65536);
        }

        LongAdder consumed = new LongAdder();
        ExecutorService exec = Executors.newFixedThreadPool(threads + 1);
        long deadline = System.currentTimeMillis() + DURATION_SEC * 1000L;
        byte[] item = new byte[100];

        // Single consumer drains all partitions (like collector)
        exec.submit(() -> {
            byte[][] drain = new byte[8192][];
            while (System.currentTimeMillis() < deadline) {
                for (FastRingBuffer<byte[]> buf : buffers) {
                    int count = buf.drain(drain, 1024);
                    if (count > 0) consumed.add(count);
                }
                Thread.onSpinWait();
            }
            // Drain remaining
            for (FastRingBuffer<byte[]> buf : buffers) {
                int count;
                while ((count = buf.drain(drain, 8192)) > 0) {
                    consumed.add(count);
                }
            }
        });

        // Each producer writes to own partition (SPSC)
        for (int t = 0; t < threads; t++) {
            final int partition = t;
            exec.submit(() -> {
                FastRingBuffer<byte[]> buf = buffers[partition];
                while (System.currentTimeMillis() < deadline) {
                    buf.offerSpin(item);
                }
            });
        }

        exec.shutdown();
        exec.awaitTermination(DURATION_SEC + 5, TimeUnit.SECONDS);

        return consumed.sum() * 1000.0 / (DURATION_SEC * 1000);
    }

    static double testCollector(int threads) throws Exception {
        Path tempDb = Files.createTempFile("bench", ".db");
        try (BatchCalibration cal = BatchCalibration.create(tempDb, 5000.0)) {
            cal.updatePressure(BatchCalibration.PressureLevel.L7_HIGH.minReqPer10s + 1);

            LongAdder consumed = new LongAdder();

            // Use 2 flush threads for less contention
            MicrobatchCollector<byte[]> collector = MicrobatchCollector.create(
                batch -> consumed.add(batch.size()),
                cal,
                2  // Fewer flush threads
            );

            long start = System.currentTimeMillis();
            long deadline = start + DURATION_SEC * 1000L;

            ExecutorService exec = Executors.newFixedThreadPool(threads);
            byte[] item = new byte[100];

            for (int t = 0; t < threads; t++) {
                exec.submit(() -> {
                    while (System.currentTimeMillis() < deadline) {
                        collector.submitFireAndForget(item);
                    }
                });
            }

            exec.shutdown();
            exec.awaitTermination(DURATION_SEC + 5, TimeUnit.SECONDS);
            collector.close();

            long elapsed = System.currentTimeMillis() - start;
            return consumed.sum() * 1000.0 / elapsed;
        } finally {
            Files.deleteIfExists(tempDb);
        }
    }
}
