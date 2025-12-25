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

    // Pending requests queue (bounded)
    private final ConcurrentLinkedQueue<PendingRequest<T>> pendingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong pendingCount = new AtomicLong(0);

    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalFlushTimeNanos = new AtomicLong(0);
    private final AtomicLong lastFlushAtNanos = new AtomicLong(System.nanoTime());

    // Flush thread
    private final Thread flushThread;
    private volatile boolean running = true;

    // Calibration state
    private final AtomicLong calibrationWindowStart = new AtomicLong(System.nanoTime());
    private final AtomicLong calibrationRequests = new AtomicLong(0);
    private final AtomicLong calibrationLatencySum = new AtomicLong(0);
    private final AtomicLong calibrationMaxLatency = new AtomicLong(0);
    private static final long CALIBRATION_WINDOW_NANOS = 5_000_000_000L; // 5 seconds

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

        // Start flush thread
        this.flushThread = Thread.ofVirtual()
            .name("microbatch-flush")
            .start(this::flushLoop);
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
            1024,       // Max batch size
            100_000,    // Max pending requests
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
        pendingQueue.offer(request);

        return future;
    }

    /**
     * Submit an item with fire-and-forget semantics.
     * Returns immediately after adding to queue.
     */
    public void submitFireAndForget(T item) {
        if (pendingCount.get() >= maxPendingRequests) {
            return; // Drop under backpressure
        }

        totalRequests.incrementAndGet();
        pendingCount.incrementAndGet();

        PendingRequest<T> request = new PendingRequest<>(item, System.nanoTime(), null);
        pendingQueue.offer(request);
    }

    /**
     * Flush loop - runs in dedicated thread.
     */
    private void flushLoop() {
        List<PendingRequest<T>> batch = new ArrayList<>(maxBatchSize);

        while (running) {
            try {
                // Collect batch
                batch.clear();
                long deadline = System.nanoTime() + (flushIntervalMicros * 1000L);

                while (batch.size() < targetBatchSize) {
                    PendingRequest<T> request = pendingQueue.poll();
                    if (request != null) {
                        batch.add(request);
                    } else if (System.nanoTime() >= deadline && !batch.isEmpty()) {
                        break; // Timeout with partial batch
                    } else if (batch.isEmpty()) {
                        // Wait briefly for first item
                        LockSupport.parkNanos(100_000); // 100µs
                    } else {
                        // Brief spin wait for more items
                        LockSupport.parkNanos(10_000); // 10µs
                    }

                    if (!running) break;
                }

                if (!batch.isEmpty()) {
                    flushBatch(batch);
                }

            } catch (Exception e) {
                // Complete all futures with error
                BatchResult errorResult = BatchResult.failure(batch.size(), e.getMessage());
                for (PendingRequest<T> req : batch) {
                    if (req.future() != null) {
                        req.future().complete(errorResult);
                    }
                }
            }
        }
    }

    /**
     * Flush a collected batch to Kafka.
     */
    private void flushBatch(List<PendingRequest<T>> batch) {
        long startNanos = System.nanoTime();

        // Extract items for consumer
        List<T> items = new ArrayList<>(batch.size());
        for (PendingRequest<T> req : batch) {
            items.add(req.item());
        }

        // Send to Kafka (batch)
        try {
            batchConsumer.accept(items);

            long flushTimeNanos = System.nanoTime() - startNanos;
            BatchResult result = BatchResult.success(batch.size(), flushTimeNanos);

            // Complete all futures
            for (PendingRequest<T> req : batch) {
                if (req.future() != null) {
                    req.future().complete(result);
                }

                // Record latency for calibration
                long latencyNanos = System.nanoTime() - req.receivedAtNanos();
                recordLatency(latencyNanos);
            }

            // Update metrics
            totalBatches.incrementAndGet();
            totalFlushTimeNanos.addAndGet(flushTimeNanos);
            lastFlushAtNanos.set(System.nanoTime());
            pendingCount.addAndGet(-batch.size());

        } catch (Exception e) {
            BatchResult errorResult = BatchResult.failure(batch.size(), e.getMessage());
            for (PendingRequest<T> req : batch) {
                if (req.future() != null) {
                    req.future().complete(errorResult);
                }
            }
            pendingCount.addAndGet(-batch.size());
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
     * Force immediate flush of all pending items.
     */
    public void flush() {
        List<PendingRequest<T>> batch = new ArrayList<>();
        PendingRequest<T> req;
        while ((req = pendingQueue.poll()) != null) {
            batch.add(req);
        }
        if (!batch.isEmpty()) {
            flushBatch(batch);
        }
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        try {
            flushThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush(); // Final flush
    }
}
