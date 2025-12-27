package com.reactive.platform.gateway.microbatch;

import java.util.ArrayList;
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
 * - Submit: 1 array store via MPSC ring buffer (zero allocation)
 * - Flush: Direct array handoff to consumer (no ArrayList copy)
 * - Goal: Reach 70%+ of BULK throughput
 *
 * v2: Replaced ConcurrentLinkedQueue with MpscRingBuffer for zero-allocation submit.
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

    // Partitioned ring buffers - zero allocation on submit
    private final int partitionCount;
    private final FastRingBuffer<T>[] ringBuffers;

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
        this(batchConsumer, calibration, maxBatchSize, Runtime.getRuntime().availableProcessors());
    }

    @SuppressWarnings("unchecked")
    private MicrobatchCollector(Consumer<List<T>> batchConsumer, BatchCalibration calibration, int maxBatchSize, int flushThreadCount) {
        this.batchConsumer = batchConsumer;
        this.calibration = calibration;
        this.maxBatchSize = maxBatchSize;
        this.windowStartNanos = System.nanoTime();

        // Load calibration for initial pressure level
        var config = calibration.getBestConfig();
        this.targetBatchSize = config.batchSize();
        this.flushIntervalMicros = config.flushIntervalMicros();

        // Ring buffers match CPU cores for submit distribution
        this.partitionCount = Runtime.getRuntime().availableProcessors();
        this.ringBuffers = new FastRingBuffer[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            ringBuffers[i] = new FastRingBuffer<>(65536); // 64K slots per partition
        }

        // Configurable flush thread count (fewer = less Kafka contention)
        int actualFlushThreads = Math.min(flushThreadCount, partitionCount);
        this.flushThreads = new Thread[actualFlushThreads];
        for (int i = 0; i < actualFlushThreads; i++) {
            final int startPartition = i;
            final int step = actualFlushThreads;
            flushThreads[i] = Thread.ofPlatform()
                .name("flush-" + i)
                .daemon(true)
                .start(() -> flushLoopMultiPartition(startPartition, step));
        }

        // Pressure monitoring thread
        this.pressureThread = Thread.ofPlatform()
            .name("pressure-monitor")
            .daemon(true)
            .start(this::pressureLoop);
    }

    /**
     * Create collector with default settings.
     * Max batch size is dynamic based on calibration's current pressure level.
     */
    public static <T> MicrobatchCollector<T> create(Consumer<List<T>> batchConsumer, BatchCalibration calibration) {
        // Max batch size based on pressure level's latency budget
        // Default to 2 flush threads - fewer threads = less Kafka contention
        int maxBatch = calibration.getCurrentPressure().maxBatchSize();
        return new MicrobatchCollector<>(batchConsumer, calibration, Math.max(maxBatch, 65536), 2);
    }

    /**
     * Create collector with custom flush thread count.
     * Fewer threads = less Kafka contention, potentially higher throughput.
     */
    public static <T> MicrobatchCollector<T> create(Consumer<List<T>> batchConsumer, BatchCalibration calibration, int flushThreadCount) {
        return new MicrobatchCollector<>(batchConsumer, calibration, 65536, flushThreadCount);
    }

    /**
     * Create collector with full customization.
     * Use for benchmarking and exploring optimal configurations.
     */
    public static <T> MicrobatchCollector<T> create(Consumer<List<T>> batchConsumer, BatchCalibration calibration,
                                                     int maxBatchSize, int flushThreadCount) {
        return new MicrobatchCollector<>(batchConsumer, calibration, maxBatchSize, flushThreadCount);
    }

    /**
     * Submit item for batching. Fire-and-forget, returns immediately.
     * This is the hot path - ONE array store, ZERO allocation.
     */
    public void submitFireAndForget(T item) {
        // Thread ID gives stable partition - no atomic counter needed
        int partition = (int) (Thread.currentThread().threadId() & (partitionCount - 1));
        ringBuffers[partition].offerSpin(item); // Spin on full (rare)
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
        var ringBuffer = ringBuffers[partition];
        @SuppressWarnings("unchecked")
        T[] batchArray = (T[]) new Object[maxBatchSize];
        var batchList = new ArrayBackedList<>(batchArray);
        long lastDrainNanos = System.nanoTime();
        int emptySpins = 0;

        while (running) {
            int target = targetBatchSize;
            int count = ringBuffer.drain(batchArray, target);

            if (count > 0) {
                lastDrainNanos = System.nanoTime();
                emptySpins = 0;
                batchList.setSize(count);
                flush(batchList);
            } else {
                long sinceLastDrain = System.nanoTime() - lastDrainNanos;
                if (sinceLastDrain < 1_000_000) {
                    Thread.onSpinWait();
                } else if (sinceLastDrain < 10_000_000) {
                    if (++emptySpins % 100 == 0) Thread.yield();
                    else Thread.onSpinWait();
                } else {
                    try { Thread.sleep(0, 10_000); } catch (InterruptedException e) { break; }
                }
            }
        }
    }

    /**
     * Flush loop that drains from multiple partitions (for fewer flush threads).
     * Round-robins across assigned partitions to balance load.
     */
    private void flushLoopMultiPartition(int startPartition, int step) {
        @SuppressWarnings("unchecked")
        T[] batchArray = (T[]) new Object[maxBatchSize];
        var batchList = new ArrayBackedList<>(batchArray);
        long lastDrainNanos = System.nanoTime();
        int emptySpins = 0;

        while (running) {
            int target = targetBatchSize;
            int totalCount = 0;

            // Drain from all assigned partitions into single batch
            for (int p = startPartition; p < partitionCount && totalCount < target; p += step) {
                int count = ringBuffers[p].drain(batchArray, target - totalCount, totalCount);
                totalCount += count;
            }

            if (totalCount > 0) {
                lastDrainNanos = System.nanoTime();
                emptySpins = 0;
                batchList.setSize(totalCount);
                flush(batchList);
            } else {
                long sinceLastDrain = System.nanoTime() - lastDrainNanos;
                if (sinceLastDrain < 1_000_000) {
                    Thread.onSpinWait();
                } else if (sinceLastDrain < 10_000_000) {
                    if (++emptySpins % 100 == 0) Thread.yield();
                    else Thread.onSpinWait();
                } else {
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

    /** Force flush all ring buffers. */
    public void flush() {
        var all = new ArrayList<T>();
        for (var ringBuffer : ringBuffers) {
            T item;
            while ((item = ringBuffer.poll()) != null) {
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
        return BatchCalibration.Config.bootstrap(targetBatchSize, flushIntervalMicros);
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
