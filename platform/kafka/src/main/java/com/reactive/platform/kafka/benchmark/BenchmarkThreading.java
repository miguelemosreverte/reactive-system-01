package com.reactive.platform.kafka.benchmark;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Threading utilities for benchmarks.
 *
 * Eliminates boilerplate ExecutorService + CountDownLatch patterns
 * that were duplicated across 23+ benchmark files.
 *
 * Usage:
 * <pre>
 *   long total = BenchmarkThreading.runTimed(durationSec, (threadId, endTimeNanos) -> {
 *       long count = 0;
 *       while (System.nanoTime() < endTimeNanos) {
 *           doWork();
 *           count++;
 *       }
 *       return count;
 *   });
 * </pre>
 */
public final class BenchmarkThreading {

    private BenchmarkThreading() {} // Utility class

    /**
     * Work function for timed benchmarks.
     * Returns the count of operations performed.
     */
    @FunctionalInterface
    public interface TimedWork {
        /**
         * Execute work until endTimeNanos is reached.
         *
         * @param threadId The thread index (0 to threads-1)
         * @param endTimeNanos System.nanoTime() deadline
         * @return Number of operations completed
         * @throws Exception if work fails
         */
        long run(int threadId, long endTimeNanos) throws Exception;
    }

    /**
     * Work function for fixed iteration benchmarks.
     */
    @FunctionalInterface
    public interface IterationWork {
        /**
         * Execute work for a fixed number of iterations.
         *
         * @param threadId The thread index (0 to threads-1)
         * @throws Exception if work fails
         */
        void run(int threadId) throws Exception;
    }

    /**
     * Run a timed benchmark across all available processors.
     *
     * @param durationSec How long to run
     * @param work The work to execute per thread
     * @return Total operations across all threads
     */
    public static long runTimed(int durationSec, TimedWork work) throws Exception {
        return runTimed(Runtime.getRuntime().availableProcessors(), durationSec, work);
    }

    /**
     * Run a timed benchmark with specified thread count.
     *
     * @param threads Number of threads to use
     * @param durationSec How long to run
     * @param work The work to execute per thread
     * @return Total operations across all threads
     */
    public static long runTimed(int threads, int durationSec, TimedWork work) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        long[] counts = new long[threads];
        long endTimeNanos = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    counts[threadId] = work.run(threadId, endTimeNanos);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        return Arrays.stream(counts).sum();
    }

    /**
     * Run work across all available processors (non-timed).
     *
     * @param work The work to execute per thread
     */
    public static void runParallel(IterationWork work) throws Exception {
        runParallel(Runtime.getRuntime().availableProcessors(), work);
    }

    /**
     * Run work with specified thread count (non-timed).
     *
     * @param threads Number of threads to use
     * @param work The work to execute per thread
     */
    public static void runParallel(int threads, IterationWork work) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    work.run(threadId);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Get the number of available processors.
     * Useful for pre-allocating per-thread resources.
     */
    public static int availableThreads() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Calculate end time in nanoseconds for a duration.
     */
    public static long endTimeNanos(int durationSec) {
        return System.nanoTime() + durationSec * 1_000_000_000L;
    }

    /**
     * Pre-allocate per-thread byte arrays.
     */
    public static byte[][] allocatePerThread(int size) {
        int threads = availableThreads();
        byte[][] buffers = new byte[threads][];
        for (int i = 0; i < threads; i++) {
            buffers[i] = new byte[size];
        }
        return buffers;
    }
}
