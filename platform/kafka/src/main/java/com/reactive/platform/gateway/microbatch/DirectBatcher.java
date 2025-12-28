package com.reactive.platform.gateway.microbatch;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Simple batcher. Accumulate messages, flush when threshold or interval reached.
 *
 * Design: Lock-free submit, simple flush.
 * - Partitioned ring buffers (one per core)
 * - Lock-free submit: single atomic increment + array store
 * - Flush when: count >= threshold OR time >= interval
 *
 * Target: Match BULK baseline throughput with adaptive batching.
 */
public final class DirectBatcher implements AutoCloseable {

    private static final int PARTITION_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int BUFFER_SIZE = 1 << 20;  // 1M messages per partition
    private static final int MASK = BUFFER_SIZE - 1;

    private final Partition[] partitions;
    private final Consumer<byte[]> sender;
    private final int messageSize;
    private final Thread[] flushThreads;
    private volatile boolean running = true;

    // Adaptive parameters
    private volatile int flushThresholdMessages;
    private volatile long flushIntervalNanos;

    public DirectBatcher(Consumer<byte[]> sender, int flushThresholdMessages, long flushIntervalMs) {
        this(sender, flushThresholdMessages, flushIntervalMs, 64);  // Default 64 byte messages
    }

    public DirectBatcher(Consumer<byte[]> sender, int flushThresholdMessages, long flushIntervalMs, int messageSize) {
        this.sender = sender;
        this.messageSize = messageSize;
        this.flushThresholdMessages = flushThresholdMessages;
        this.flushIntervalNanos = flushIntervalMs * 1_000_000L;

        this.partitions = new Partition[PARTITION_COUNT];
        for (int i = 0; i < PARTITION_COUNT; i++) {
            partitions[i] = new Partition(messageSize);
        }

        // One flush thread per partition (matches BULK's parallelism)
        this.flushThreads = new Thread[PARTITION_COUNT];
        for (int i = 0; i < PARTITION_COUNT; i++) {
            final int p = i;
            flushThreads[i] = Thread.ofPlatform()
                .name("flush-" + i)
                .daemon(true)
                .start(() -> flushLoop(p));
        }
    }

    /**
     * Submit a message. Lock-free: one atomic increment + one array store.
     */
    public void submit(byte[] message) {
        int p = (int) (Thread.currentThread().threadId() & (PARTITION_COUNT - 1));
        partitions[p].add(message);
    }

    /**
     * Update adaptive parameters.
     */
    public void setParameters(int thresholdMessages, long intervalMs) {
        this.flushThresholdMessages = thresholdMessages;
        this.flushIntervalNanos = intervalMs * 1_000_000L;
    }

    private void flushLoop(int partitionIndex) {
        Partition p = partitions[partitionIndex];

        while (running) {
            int count = p.count();
            long elapsed = System.nanoTime() - p.batchStartNanos;

            boolean shouldFlush = (count >= flushThresholdMessages) ||
                                  (count > 0 && elapsed >= flushIntervalNanos);

            if (shouldFlush && count > 0) {
                byte[] batch = p.drain();
                if (batch != null && batch.length > 0) {
                    sender.accept(batch);
                }
            } else if (count == 0) {
                try { Thread.sleep(0, 100_000); } catch (InterruptedException e) { break; }
            } else {
                Thread.onSpinWait();
            }
        }

        // Final flush
        byte[] remaining = p.drain();
        if (remaining != null && remaining.length > 0) {
            sender.accept(remaining);
        }
    }

    public void flush() {
        for (Partition p : partitions) {
            byte[] batch = p.drain();
            if (batch != null && batch.length > 0) {
                sender.accept(batch);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        for (Thread t : flushThreads) {
            t.interrupt();
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        flush();
    }

    /**
     * Lock-free partition using pre-allocated buffer.
     * Submit: atomic increment + arraycopy into pre-allocated buffer.
     * Drain: copy out and reset.
     */
    private static class Partition {
        private final byte[] buffer;
        private final int messageSize;
        private final AtomicInteger writePos = new AtomicInteger(0);
        private volatile int drainPos = 0;
        volatile long batchStartNanos = System.nanoTime();

        Partition(int messageSize) {
            this.messageSize = messageSize;
            this.buffer = new byte[BUFFER_SIZE * messageSize];
        }

        void add(byte[] msg) {
            int pos = writePos.getAndIncrement();
            if (pos == 0) {
                batchStartNanos = System.nanoTime();
            }
            int offset = (pos & MASK) * messageSize;
            System.arraycopy(msg, 0, buffer, offset, Math.min(msg.length, messageSize));
        }

        int count() {
            return writePos.get() - drainPos;
        }

        synchronized byte[] drain() {
            int write = writePos.get();
            int read = drainPos;
            int count = write - read;
            if (count <= 0) return null;

            // Handle wrap-around by capping at buffer boundary
            int effectiveCount = Math.min(count, BUFFER_SIZE);
            int startOffset = (read & MASK) * messageSize;
            int bytes = effectiveCount * messageSize;

            byte[] result;
            if (startOffset + bytes <= buffer.length) {
                // Contiguous - single copy
                result = new byte[bytes];
                System.arraycopy(buffer, startOffset, result, 0, bytes);
            } else {
                // Wrap-around - two copies
                int firstPart = buffer.length - startOffset;
                int secondPart = bytes - firstPart;
                result = new byte[bytes];
                System.arraycopy(buffer, startOffset, result, 0, firstPart);
                System.arraycopy(buffer, 0, result, firstPart, secondPart);
            }

            drainPos = read + effectiveCount;
            batchStartNanos = System.nanoTime();
            return result;
        }
    }
}
