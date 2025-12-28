package com.reactive.platform.gateway.microbatch;

import java.util.function.Consumer;

/**
 * Thread-local batcher. Each producer thread manages its own batch.
 * Zero shared state = zero contention = maximum throughput.
 *
 * Design matches BULK baseline:
 * - Each thread accumulates into its own buffer
 * - Each thread flushes when batch full or interval elapsed
 * - No coordination between threads
 *
 * The trade-off: batching is per-thread, not global.
 * At high throughput, this doesn't matter.
 * At low throughput, each thread may have partial batches.
 */
public final class ThreadLocalBatcher implements AutoCloseable {

    private final Consumer<byte[]> sender;
    private final int messageSize;

    // Adaptive parameters (read by all threads)
    private volatile int batchThreshold;
    private volatile long intervalNanos;

    // Thread-local state
    private final ThreadLocal<Batch> threadBatch;

    public ThreadLocalBatcher(Consumer<byte[]> sender, int batchThreshold, long intervalMs) {
        this(sender, batchThreshold, intervalMs, 64);
    }

    public ThreadLocalBatcher(Consumer<byte[]> sender, int batchThreshold, long intervalMs, int messageSize) {
        this.sender = sender;
        this.messageSize = messageSize;
        this.batchThreshold = batchThreshold;
        this.intervalNanos = intervalMs * 1_000_000L;
        this.threadBatch = ThreadLocal.withInitial(() -> new Batch(messageSize, 100_000));
    }

    /**
     * Submit a message. Entirely thread-local - no contention.
     */
    public void submit(byte[] message) {
        Batch batch = threadBatch.get();
        batch.add(message);

        // Check flush condition
        if (batch.count >= batchThreshold ||
            (batch.count > 0 && System.nanoTime() - batch.startNanos >= intervalNanos)) {
            byte[] data = batch.drain();
            sender.accept(data);
        }
    }

    /**
     * Update adaptive parameters.
     */
    public void setParameters(int threshold, long intervalMs) {
        this.batchThreshold = threshold;
        this.intervalNanos = intervalMs * 1_000_000L;
    }

    /**
     * Flush current thread's batch.
     */
    public void flushCurrent() {
        Batch batch = threadBatch.get();
        if (batch.count > 0) {
            sender.accept(batch.drain());
        }
    }

    @Override
    public void close() {
        // Note: Can only flush current thread. Other threads must flush themselves.
        flushCurrent();
    }

    /**
     * Simple batch buffer.
     */
    private static class Batch {
        final byte[] buffer;
        final int messageSize;
        int count;
        long startNanos;

        Batch(int messageSize, int capacity) {
            this.messageSize = messageSize;
            this.buffer = new byte[capacity * messageSize];
            this.startNanos = System.nanoTime();
        }

        void add(byte[] msg) {
            if (count == 0) {
                startNanos = System.nanoTime();
            }
            System.arraycopy(msg, 0, buffer, count * messageSize, Math.min(msg.length, messageSize));
            count++;
        }

        byte[] drain() {
            byte[] result = new byte[count * messageSize];
            System.arraycopy(buffer, 0, result, 0, result.length);
            count = 0;
            startNanos = System.nanoTime();
            return result;
        }
    }
}
