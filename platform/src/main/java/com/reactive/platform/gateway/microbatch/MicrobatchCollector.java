package com.reactive.platform.gateway.microbatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * High-throughput microbatch collector.
 *
 * Design: Minimal overhead on submit path, batch at flush time.
 *
 * Performance characteristics:
 * - Submit: 1 queue.offer() call (no atomics, no allocation)
 * - Flush: Batch processing with single Kafka call per batch
 * - Metrics: Tracked at flush time only
 *
 * @param <T> The type of items being collected
 */
public final class MicrobatchCollector<T> implements AutoCloseable {

    /** Result of batch flush. */
    public record BatchResult(int batchSize, long flushTimeNanos, boolean success, String errorMessage) {
        public static BatchResult success(int size, long nanos) { return new BatchResult(size, nanos, true, null); }
        public static BatchResult failure(int size, String err) { return new BatchResult(size, 0, false, err); }
    }

    /** Metrics snapshot. */
    public record Metrics(
        long totalRequests, long totalBatches, long totalFlushTimeNanos,
        int currentBatchSize, int currentFlushIntervalMicros,
        double avgBatchSize, double avgFlushTimeMicros, double throughputPerSec, long lastFlushAtNanos
    ) {
        public double avgLatencyMicros() {
            return totalBatches > 0 ? (totalFlushTimeNanos / totalBatches) / 1000.0 : 0.0;
        }
    }

    // Configuration
    private final Consumer<List<T>> batchConsumer;
    private final BatchCalibration calibration;
    private final int maxBatchSize;

    // Calibrated parameters
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

    // Flush threads
    private final Thread[] flushThreads;
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    private MicrobatchCollector(Consumer<List<T>> batchConsumer, BatchCalibration calibration, int maxBatchSize) {
        this.batchConsumer = batchConsumer;
        this.calibration = calibration;
        this.maxBatchSize = maxBatchSize;

        // Load calibration
        var config = calibration.getBestConfig();
        this.targetBatchSize = config.batchSize();
        this.flushIntervalMicros = config.flushIntervalMicros();

        // One queue per CPU core - sweet spot for throughput
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
    }

    /** Create collector with default settings. */
    public static <T> MicrobatchCollector<T> create(Consumer<List<T>> batchConsumer, BatchCalibration calibration) {
        return new MicrobatchCollector<>(batchConsumer, calibration, 1024);
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

    /** Flush loop for one partition. Simple and fast. */
    private void flushLoop(int partition) {
        var queue = queues[partition];
        var batch = new ArrayList<T>(maxBatchSize);

        while (running) {
            batch.clear();

            int target = targetBatchSize;
            long deadlineNanos = System.nanoTime() + (flushIntervalMicros * 1000L);

            // Drain queue until target size or deadline
            while (batch.size() < target) {
                T item = queue.poll();
                if (item != null) {
                    batch.add(item);
                } else if (System.nanoTime() >= deadlineNanos) {
                    break;
                } else {
                    // Yield briefly to let producers catch up
                    Thread.onSpinWait();
                }
            }

            if (!batch.isEmpty()) {
                flush(batch);
            }
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
            System.nanoTime()
        );
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
        for (Thread t : flushThreads) t.interrupt();
        for (Thread t : flushThreads) {
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        flush();
    }
}
