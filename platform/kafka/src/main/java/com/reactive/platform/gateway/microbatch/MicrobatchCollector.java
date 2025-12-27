package com.reactive.platform.gateway.microbatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * High-throughput microbatch collector with pressure-aware calibration.
 *
 * Design: Zero-allocation hot path, bulk drain, pre-allocated buffers.
 * Adapts batch parameters based on observed pressure level.
 *
 * Performance targets (based on BULK benchmark: 131.8M msg/s):
 * - Submit: 1 array store (no queue overhead for byte[] items)
 * - Flush: Direct array handoff to consumer (no ArrayList copy)
 * - Goal: Reach 70%+ of BULK throughput
 *
 * @param <T> The type of items being collected
 */
public final class MicrobatchCollector<T> implements AutoCloseable {

    private static final long PRESSURE_WINDOW_NANOS = 10_000_000_000L; // 10 seconds

    /** Result of batch flush. */
    public record BatchResult(int batchSize, long flushTimeNanos, boolean success, String errorMessage) {
        public static BatchResult success(int size, long nanos) { return new BatchResult(size, nanos, true, null); }
        public static BatchResult failure(int size, String err) { return new BatchResult(size, 0, false, err); }
    }

    /** Metrics snapshot. */
    public record Metrics(
        long totalRequests, long totalBatches, long totalFlushTimeNanos,
        int currentBatchSize, int currentFlushIntervalMicros,
        double avgBatchSize, double avgFlushTimeMicros, double throughputPerSec, long lastFlushAtNanos,
        BatchCalibration.PressureLevel pressureLevel
    ) {
        public double avgLatencyMicros() {
            return totalBatches > 0 ? (totalFlushTimeNanos / totalBatches) / 1000.0 : 0.0;
        }
    }

    // Configuration
    private final Consumer<List<T>> batchConsumer;
    private final BatchCalibration calibration;
    private final int maxBatchSize;

    // Calibrated parameters (updated on pressure change)
    private volatile int targetBatchSize;
    private volatile int flushIntervalMicros;

    // Partitioned queues - each flush thread owns one queue
    private final int partitionCount;
    private final ConcurrentLinkedQueue<T>[] queues;

    // Metrics (only updated at flush time - no contention on submit)
    private final LongAdder totalItems = new LongAdder();
    private final LongAdder totalBatches = new LongAdder();
    private final LongAdder totalFlushNanos = new LongAdder();
    private final long startTimeNanos = System.nanoTime();

    // Pressure tracking (updated from flush metrics, no hot path overhead)
    private volatile long windowStartNanos;
    private volatile long windowItemCount;
    private volatile BatchCalibration.PressureLevel currentPressure = BatchCalibration.PressureLevel.MEDIUM;

    // Threads
    private final Thread[] flushThreads;
    private final Thread pressureThread;
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    private MicrobatchCollector(Consumer<List<T>> batchConsumer, BatchCalibration calibration, int maxBatchSize) {
        this.batchConsumer = batchConsumer;
        this.calibration = calibration;
        this.maxBatchSize = maxBatchSize;
        this.windowStartNanos = System.nanoTime();

        // Load calibration for initial pressure level
        var config = calibration.getBestConfig();
        this.targetBatchSize = config.batchSize();
        this.flushIntervalMicros = config.flushIntervalMicros();

        // One queue per CPU core for maximum throughput
        this.partitionCount = Runtime.getRuntime().availableProcessors();
        this.queues = new ConcurrentLinkedQueue[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            queues[i] = new ConcurrentLinkedQueue<>();
        }

        // One flush thread per queue
        this.flushThreads = new Thread[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            final int idx = i;
            flushThreads[i] = Thread.ofPlatform()
                .name("flush-" + i)
                .daemon(true)
                .start(() -> flushLoop(idx));
        }

        // Pressure monitoring thread
        this.pressureThread = Thread.ofPlatform()
            .name("pressure-monitor")
            .daemon(true)
            .start(this::pressureLoop);
    }

    /** Create collector with default settings. Batch size is dynamic based on calibration. */
    public static <T> MicrobatchCollector<T> create(Consumer<List<T>> batchConsumer, BatchCalibration calibration) {
        // Use max possible batch size from calibration (HTTP_60S = 20000)
        return new MicrobatchCollector<>(batchConsumer, calibration, 32768);
    }

    /**
     * Submit item for batching. Fire-and-forget, returns immediately.
     * This is the hot path - ONE queue operation, no atomics.
     */
    public void submitFireAndForget(T item) {
        // Thread ID gives stable partition - no atomic counter needed
        int partition = (int) (Thread.currentThread().threadId() & (partitionCount - 1));
        queues[partition].offer(item);
    }

    /**
     * Submit with future (for request/response flow).
     * Wraps item and future together.
     */
    public CompletableFuture<BatchResult> submit(T item) {
        CompletableFuture<BatchResult> future = new CompletableFuture<>();
        // For sync mode, we need to track the future somehow
        // Simple approach: use a wrapper queue or separate tracking
        // For now, just submit and complete immediately (can enhance later)
        submitFireAndForget(item);
        future.complete(BatchResult.success(1, 0));
        return future;
    }

    /** Flush loop for one partition. Optimized for maximum throughput. */
    private void flushLoop(int partition) {
        var queue = queues[partition];
        @SuppressWarnings("unchecked")
        T[] batchArray = (T[]) new Object[maxBatchSize];
        // Reusable list wrapper - ZERO ALLOCATION per batch
        var batchList = new ArrayBackedList<>(batchArray);
        long lastDrainNanos = System.nanoTime();
        int emptySpins = 0;

        while (running) {
            int target = targetBatchSize;

            // FAST DRAIN: Direct to array, no ArrayList overhead
            int count = drainToArray(queue, batchArray, target);

            if (count > 0) {
                // Got items - flush immediately using reusable list
                lastDrainNanos = System.nanoTime();
                emptySpins = 0;
                batchList.setSize(count);
                flush(batchList);
            } else {
                // Queue empty - wait strategy based on recent activity
                long sinceLastDrain = System.nanoTime() - lastDrainNanos;

                if (sinceLastDrain < 1_000_000) {
                    // Drained within 1ms - hot path, just spin
                    Thread.onSpinWait();
                } else if (sinceLastDrain < 10_000_000) {
                    // 1-10ms since drain - yield occasionally
                    if (++emptySpins % 100 == 0) Thread.yield();
                    else Thread.onSpinWait();
                } else {
                    // >10ms since drain - cold path, brief sleep
                    try { Thread.sleep(0, 10_000); } catch (InterruptedException e) { break; }
                }
            }
        }
    }

    /** Zero-allocation List wrapper over array segment. */
    private static class ArrayBackedList<E> extends java.util.AbstractList<E> {
        private final E[] array;
        private int size;

        ArrayBackedList(E[] array) { this.array = array; }
        void setSize(int size) { this.size = size; }
        @Override public E get(int index) { return array[index]; }
        @Override public int size() { return size; }
    }

    /** Drain directly to array - faster than ArrayList. */
    private int drainToArray(ConcurrentLinkedQueue<T> queue, T[] array, int maxItems) {
        int count = 0;
        T item;
        while (count < maxItems && (item = queue.poll()) != null) {
            array[count++] = item;
        }
        return count;
    }

    /** Pressure monitoring loop - checks every 10 seconds. */
    private void pressureLoop() {
        while (running) {
            try {
                Thread.sleep(10_000); // Check every 10 seconds
            } catch (InterruptedException e) {
                break;
            }

            long now = System.nanoTime();
            long itemsInWindow = totalItems.sum() - windowItemCount;
            long elapsed = now - windowStartNanos;

            // Calculate requests per 10 seconds
            long reqPer10Sec = elapsed > 0 ? (itemsInWindow * PRESSURE_WINDOW_NANOS) / elapsed : 0;

            // Update pressure level
            var oldPressure = currentPressure;
            currentPressure = BatchCalibration.PressureLevel.fromRequestRate(reqPer10Sec);
            calibration.updatePressure(reqPer10Sec);

            // Reload config if pressure level changed
            if (currentPressure != oldPressure) {
                var config = calibration.getBestConfig();
                this.targetBatchSize = config.batchSize();
                this.flushIntervalMicros = config.flushIntervalMicros();
            }

            // Reset window
            windowStartNanos = now;
            windowItemCount = totalItems.sum();
        }
    }

    /** Flush a batch to the consumer. */
    private void flush(List<T> batch) {
        long start = System.nanoTime();
        try {
            batchConsumer.accept(batch);
            long elapsed = System.nanoTime() - start;

            // Update metrics (LongAdder - no contention)
            totalItems.add(batch.size());
            totalBatches.increment();
            totalFlushNanos.add(elapsed);

        } catch (Exception e) {
            // Log error, continue
            System.err.println("Batch flush failed: " + e.getMessage());
        }
    }

    /** Force flush all queues. */
    public void flush() {
        var all = new ArrayList<T>();
        for (var queue : queues) {
            T item;
            while ((item = queue.poll()) != null) {
                all.add(item);
            }
        }
        if (!all.isEmpty()) {
            flush(all);
        }
    }

    /** Get metrics snapshot. */
    public Metrics getMetrics() {
        long items = totalItems.sum();
        long batches = totalBatches.sum();
        long flushNanos = totalFlushNanos.sum();
        long elapsed = System.nanoTime() - startTimeNanos;

        return new Metrics(
            items, batches, flushNanos,
            targetBatchSize, flushIntervalMicros,
            batches > 0 ? (double) items / batches : 0,
            batches > 0 ? flushNanos / batches / 1000.0 : 0,
            elapsed > 0 ? items * 1_000_000_000.0 / elapsed : 0,
            System.nanoTime(),
            currentPressure
        );
    }

    /** Get current pressure level. */
    public BatchCalibration.PressureLevel getPressureLevel() {
        return currentPressure;
    }

    /** Get current config. */
    public BatchCalibration.Config getCurrentConfig() {
        return new BatchCalibration.Config(targetBatchSize, flushIntervalMicros, 0, 0, 0, 0, Instant.now());
    }

    /** No backpressure in this design - returns 0. */
    public long droppedCount() { return 0; }

    @Override
    public void close() {
        running = false;
        pressureThread.interrupt();
        for (Thread t : flushThreads) t.interrupt();
        try { pressureThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (Thread t : flushThreads) {
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        flush();
    }
}
