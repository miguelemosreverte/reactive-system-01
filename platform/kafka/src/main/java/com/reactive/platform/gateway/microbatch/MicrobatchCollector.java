package com.reactive.platform.gateway.microbatch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Adaptive microbatch collector for high-throughput event ingestion.
 *
 * Collects incoming items and flushes them to Kafka in optimal batches.
 * The batch size and flush interval are dynamically calibrated based on
 * observed throughput and latency.
 *
 * Design principles:
 * - Lock-free hot path using CAS operations
 * - Bounded memory with configurable limits
 * - Graceful degradation under backpressure
 * - Self-calibrating for optimal throughput
 *
 * @param <T> The type of items being collected
 */
public final class MicrobatchCollector<T> implements AutoCloseable {

    /**
     * A pending request waiting for batch completion.
     */
    public record PendingRequest<T>(
        T item,
        long receivedAtNanos,
        CompletableFuture<BatchResult> future
    ) {}

    /**
     * Result of batch flush - shared by all requests in the batch.
     */
    public record BatchResult(
        int batchSize,
        long flushTimeNanos,
        boolean success,
        String errorMessage
    ) {
        public static BatchResult success(int size, long flushTimeNanos) {
            return new BatchResult(size, flushTimeNanos, true, null);
        }

        public static BatchResult failure(int size, String error) {
            return new BatchResult(size, 0, false, error);
        }
    }

    /**
     * Metrics snapshot for observability.
     */
    public record Metrics(
        long totalRequests,
        long totalBatches,
        long totalFlushTimeNanos,
        int currentBatchSize,
        int currentFlushIntervalMicros,
        double avgBatchSize,
        double avgFlushTimeMicros,
        double throughputPerSec,
        long lastFlushAtNanos
    ) {
        public double avgLatencyMicros() {
            return totalBatches > 0 ? (totalFlushTimeNanos / totalBatches) / 1000.0 : 0.0;
        }
    }

    // Configuration
    private final int maxBatchSize;
    private final int maxPendingRequests;
    private final Consumer<List<T>> batchConsumer;
    private final BatchCalibration calibration;

    // Current calibrated parameters
    private volatile int targetBatchSize;
    private volatile int flushIntervalMicros;

    // Partitioned queues for reduced contention
    private final int partitionCount;
    private final ConcurrentLinkedQueue<PendingRequest<T>>[] partitionedQueues;
    private final AtomicLong pendingCount = new AtomicLong(0);
    private final AtomicLong submitCounter = new AtomicLong(0); // For round-robin

    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalFlushTimeNanos = new AtomicLong(0);
    private final AtomicLong lastFlushAtNanos = new AtomicLong(System.nanoTime());

    // Flush threads (multiple for parallel draining)
    private final Thread[] flushThreads;
    private final int flushThreadCount;
    private volatile boolean running = true;

    // Calibration state
    private final AtomicLong calibrationWindowStart = new AtomicLong(System.nanoTime());
    private final AtomicLong calibrationRequests = new AtomicLong(0);
    private final AtomicLong calibrationLatencySum = new AtomicLong(0);
    private final AtomicLong calibrationMaxLatency = new AtomicLong(0);
    private static final long CALIBRATION_WINDOW_NANOS = 5_000_000_000L; // 5 seconds

    @SuppressWarnings("unchecked")
    private MicrobatchCollector(
        int maxBatchSize,
        int maxPendingRequests,
        Consumer<List<T>> batchConsumer,
        BatchCalibration calibration
    ) {
        this.maxBatchSize = maxBatchSize;
        this.maxPendingRequests = maxPendingRequests;
        this.batchConsumer = batchConsumer;
        this.calibration = calibration;

        // Load initial calibration
        BatchCalibration.Config config = calibration.getBestConfig();
        this.targetBatchSize = config.batchSize();
        this.flushIntervalMicros = config.flushIntervalMicros();

        // Create partitioned queues - one per flush thread for zero contention
        // Benchmarks showed 32 flush threads with batch=128 achieves 1M+ msg/sec at 100% efficiency
        this.flushThreadCount = Math.max(16, Runtime.getRuntime().availableProcessors() * 4);
        this.partitionCount = flushThreadCount;
        this.partitionedQueues = new ConcurrentLinkedQueue[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            partitionedQueues[i] = new ConcurrentLinkedQueue<>();
        }

        // Start flush threads - PLATFORM threads for guaranteed scheduling
        // Virtual threads get starved by producer load
        this.flushThreads = new Thread[flushThreadCount];
        for (int i = 0; i < flushThreadCount; i++) {
            final int partition = i;
            flushThreads[i] = Thread.ofPlatform()
                .name("microbatch-flush-" + i)
                .daemon(true)
                .start(() -> flushLoopPartitioned(partition));
        }
    }

    /**
     * Create a new microbatch collector.
     *
     * @param batchConsumer Function to consume batches (typically sends to Kafka)
     * @param calibration Calibration store for persistence
     */
    public static <T> MicrobatchCollector<T> create(
        Consumer<List<T>> batchConsumer,
        BatchCalibration calibration
    ) {
        return new MicrobatchCollector<>(
            4096,       // Max batch size (larger for high throughput)
            1_000_000,  // Max pending requests (1M buffer)
            batchConsumer,
            calibration
        );
    }

    /**
     * Submit an item for batching.
     *
     * Returns a future that completes when the batch is flushed.
     * This allows the caller to respond to HTTP after Kafka ack.
     */
    public CompletableFuture<BatchResult> submit(T item) {
        // Backpressure check
        if (pendingCount.get() >= maxPendingRequests) {
            return CompletableFuture.completedFuture(
                BatchResult.failure(0, "Backpressure: too many pending requests")
            );
        }

        totalRequests.incrementAndGet();
        pendingCount.incrementAndGet();

        CompletableFuture<BatchResult> future = new CompletableFuture<>();
        PendingRequest<T> request = new PendingRequest<>(item, System.nanoTime(), future);

        // Round-robin to partition
        int partition = (int)(submitCounter.getAndIncrement() % partitionCount);
        partitionedQueues[partition].offer(request);

        return future;
    }

    // Track dropped requests
    private final AtomicLong droppedCount = new AtomicLong(0);

    /**
     * Submit an item with fire-and-forget semantics.
     * Returns immediately after adding to queue.
     */
    public void submitFireAndForget(T item) {
        if (pendingCount.get() >= maxPendingRequests) {
            droppedCount.incrementAndGet();
            return; // Drop under backpressure
        }

        totalRequests.incrementAndGet();
        pendingCount.incrementAndGet();

        PendingRequest<T> request = new PendingRequest<>(item, System.nanoTime(), null);

        // Round-robin to partition - use thread ID for better distribution
        int partition = (int)(Thread.currentThread().threadId() % partitionCount);
        partitionedQueues[partition].offer(request);
    }

    /**
     * Get count of dropped requests due to backpressure.
     */
    public long droppedCount() {
        return droppedCount.get();
    }

    /**
     * Partitioned flush loop - each thread owns its own queue.
     * Respects calibrated targetBatchSize and flushIntervalMicros.
     * The calibration system will find the optimal configuration.
     */
    private void flushLoopPartitioned(int partition) {
        List<PendingRequest<T>> batch = new ArrayList<>(maxBatchSize);
        ConcurrentLinkedQueue<PendingRequest<T>> myQueue = partitionedQueues[partition];

        while (running) {
            try {
                batch.clear();

                // Get current calibrated parameters
                int target = targetBatchSize;
                int intervalMicros = flushIntervalMicros;
                long deadlineNanos = System.nanoTime() + (intervalMicros * 1000L);

                // Collect until target size OR deadline
                PendingRequest<T> request;
                while (batch.size() < target) {
                    request = myQueue.poll();
                    if (request != null) {
                        batch.add(request);
                    } else if (System.nanoTime() >= deadlineNanos) {
                        // Deadline reached - flush what we have
                        break;
                    } else {
                        // Brief pause to allow accumulation
                        Thread.onSpinWait();
                    }
                }

                if (!batch.isEmpty()) {
                    flushBatch(batch);
                }

            } catch (Exception e) {
                BatchResult errorResult = BatchResult.failure(batch.size(), e.getMessage());
                for (PendingRequest<T> req : batch) {
                    if (req.future() != null) {
                        req.future().complete(errorResult);
                    }
                }
            }
        }
    }

    // Reusable item list per thread to avoid allocation
    private static final ThreadLocal<List<Object>> itemListCache =
        ThreadLocal.withInitial(() -> new ArrayList<>(4096));

    /**
     * Flush a collected batch to Kafka.
     * Optimized for high throughput with minimal allocation.
     */
    @SuppressWarnings("unchecked")
    private void flushBatch(List<PendingRequest<T>> batch) {
        int batchSize = batch.size();
        if (batchSize == 0) return;

        long startNanos = System.nanoTime();

        // Reuse thread-local list to avoid allocation
        List<Object> items = itemListCache.get();
        items.clear();
        for (int i = 0; i < batchSize; i++) {
            items.add(batch.get(i).item());
        }

        // Send to Kafka (batch)
        try {
            batchConsumer.accept((List<T>) items);

            long flushTimeNanos = System.nanoTime() - startNanos;

            // Complete futures in bulk (only for sync mode)
            // Skip per-item latency recording for throughput - sample only
            boolean recordLatency = (totalBatches.get() % 100) == 0;
            if (recordLatency && !batch.isEmpty()) {
                long latencyNanos = System.nanoTime() - batch.get(0).receivedAtNanos();
                recordLatency(latencyNanos * batchSize); // Approximate total
            }

            // Bulk complete futures
            BatchResult result = BatchResult.success(batchSize, flushTimeNanos);
            for (int i = 0; i < batchSize; i++) {
                CompletableFuture<BatchResult> future = batch.get(i).future();
                if (future != null) {
                    future.complete(result);
                }
            }

            // Update metrics
            totalBatches.incrementAndGet();
            totalFlushTimeNanos.addAndGet(flushTimeNanos);
            pendingCount.addAndGet(-batchSize);

        } catch (Exception e) {
            BatchResult errorResult = BatchResult.failure(batchSize, e.getMessage());
            for (int i = 0; i < batchSize; i++) {
                CompletableFuture<BatchResult> future = batch.get(i).future();
                if (future != null) {
                    future.complete(errorResult);
                }
            }
            pendingCount.addAndGet(-batchSize);
        }
    }

    /**
     * Record latency for calibration.
     */
    private void recordLatency(long latencyNanos) {
        calibrationRequests.incrementAndGet();
        calibrationLatencySum.addAndGet(latencyNanos);

        // Update max latency (CAS loop)
        long currentMax;
        do {
            currentMax = calibrationMaxLatency.get();
        } while (latencyNanos > currentMax &&
                 !calibrationMaxLatency.compareAndSet(currentMax, latencyNanos));

        // Check if calibration window expired
        long windowStart = calibrationWindowStart.get();
        long now = System.nanoTime();
        if (now - windowStart >= CALIBRATION_WINDOW_NANOS) {
            if (calibrationWindowStart.compareAndSet(windowStart, now)) {
                // Record observation and recalibrate
                recalibrate();
            }
        }
    }

    /**
     * Recalibrate based on recent observations.
     */
    private void recalibrate() {
        long requests = calibrationRequests.getAndSet(0);
        long latencySum = calibrationLatencySum.getAndSet(0);
        long maxLatency = calibrationMaxLatency.getAndSet(0);

        if (requests < 100) return; // Not enough data

        double avgLatencyMicros = (latencySum / requests) / 1000.0;
        double p99LatencyMicros = (maxLatency / 1000.0) * 0.99; // Approximate
        long throughputPerSec = requests * 1_000_000_000L / CALIBRATION_WINDOW_NANOS;

        // Record observation
        BatchCalibration.Observation obs = new BatchCalibration.Observation(
            targetBatchSize,
            flushIntervalMicros,
            throughputPerSec,
            avgLatencyMicros,
            p99LatencyMicros,
            requests,
            Instant.now()
        );
        calibration.recordObservation(obs);

        // Get suggestion for next config
        BatchCalibration.Config next = calibration.suggestNext(ThreadLocalRandom.current());

        // Apply new config
        this.targetBatchSize = Math.min(next.batchSize(), maxBatchSize);
        this.flushIntervalMicros = next.flushIntervalMicros();
    }

    /**
     * Get current metrics snapshot.
     */
    public Metrics getMetrics() {
        long requests = totalRequests.get();
        long batches = totalBatches.get();
        long elapsedNanos = System.nanoTime() - calibrationWindowStart.get();

        return new Metrics(
            requests,
            batches,
            totalFlushTimeNanos.get(),
            targetBatchSize,
            flushIntervalMicros,
            batches > 0 ? (double) requests / batches : 0.0,
            batches > 0 ? totalFlushTimeNanos.get() / batches / 1000.0 : 0.0,
            elapsedNanos > 0 ? requests * 1_000_000_000.0 / elapsedNanos : 0.0,
            lastFlushAtNanos.get()
        );
    }

    /**
     * Get current calibrated configuration.
     */
    public BatchCalibration.Config getCurrentConfig() {
        return new BatchCalibration.Config(
            targetBatchSize,
            flushIntervalMicros,
            0.0, 0L, 0.0, 0.0,
            Instant.now()
        );
    }

    /**
     * Force immediate flush of all pending items from all partitions.
     */
    public void flush() {
        List<PendingRequest<T>> batch = new ArrayList<>();
        for (ConcurrentLinkedQueue<PendingRequest<T>> queue : partitionedQueues) {
            PendingRequest<T> req;
            while ((req = queue.poll()) != null) {
                batch.add(req);
            }
        }
        if (!batch.isEmpty()) {
            flushBatch(batch);
        }
    }

    @Override
    public void close() {
        running = false;
        for (Thread t : flushThreads) {
            t.interrupt();
        }
        try {
            for (Thread t : flushThreads) {
                t.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush(); // Final flush
    }
}
