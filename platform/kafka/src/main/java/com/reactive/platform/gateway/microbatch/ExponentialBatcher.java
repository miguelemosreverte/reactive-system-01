package com.reactive.platform.gateway.microbatch;

import java.util.function.Consumer;

/**
 * Exponential bucket batcher with thread-local Level 0.
 *
 * Design:
 * - Thread-local Level 0 (1KB) - zero contention on write
 * - Shared levels 1-5 (10KB -> 100MB) - synchronized overflow
 * - Overflow is one memcpy per 1KB, not per message
 *
 * Hot path: arraycopy to thread-local buffer + counter increment.
 * Overflow (every 1KB): synchronized copy to shared bucket.
 *
 * At high load -> data accumulates in large buckets -> BULK-like flush.
 * At low load -> small buckets flush quickly -> low latency.
 *
 * @deprecated Use {@link PartitionedBatcher} instead - achieves 1.11B msg/s with simpler flat partitions.
 */
@Deprecated
public final class ExponentialBatcher implements AutoCloseable {

    private static final int LEVEL0_SIZE = 64 * 1024;  // 64KB thread-local buffer (overflow every ~1000 msgs)

    // Shared bucket sizes (exponential, starting 10x Level 0)
    private static final int[] SHARED_SIZES = {
        640 * 1024,         // Level 1: 640 KB (10x thread buffers from 12 cores)
        6 * 1024 * 1024,    // Level 2: 6 MB
        60 * 1024 * 1024,   // Level 3: 60 MB
        200 * 1024 * 1024   // Level 4: 200 MB (max reasonable)
    };

    private final Consumer<byte[]> sender;
    private final SharedBuckets shared;
    private final ThreadLocal<LocalBuffer> localBuffer;
    private final Thread flushThread;
    private volatile boolean running = true;

    private volatile int flushLevel;  // 0=10KB, 1=100KB, 2=1MB, 3=10MB, 4=100MB
    private volatile long flushIntervalNanos;

    public ExponentialBatcher(Consumer<byte[]> sender, int flushLevel, long flushIntervalMs) {
        this.sender = sender;
        this.flushLevel = Math.min(flushLevel, SHARED_SIZES.length - 1);
        this.flushIntervalNanos = flushIntervalMs * 1_000_000L;
        this.shared = new SharedBuckets();
        this.localBuffer = ThreadLocal.withInitial(() -> new LocalBuffer(shared));

        this.flushThread = Thread.ofPlatform()
            .name("exp-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    /**
     * Submit message. Hot path: thread-local arraycopy + increment.
     */
    public void submit(byte[] message) {
        localBuffer.get().write(message);
    }

    public void setParameters(int flushLevel, long intervalMs) {
        this.flushLevel = Math.min(flushLevel, SHARED_SIZES.length - 1);
        this.flushIntervalNanos = intervalMs * 1_000_000L;
    }

    private void flushLoop() {
        while (running) {
            int highest = shared.highestLevel();
            long elapsed = System.nanoTime() - shared.lastWriteNanos;

            boolean shouldFlush = (highest >= flushLevel) ||
                                  (highest >= 0 && elapsed >= flushIntervalNanos);

            if (shouldFlush) {
                byte[] data = shared.drain();
                if (data != null && data.length > 0) {
                    sender.accept(data);
                }
            } else {
                try { Thread.sleep(0, 100_000); } catch (InterruptedException e) { break; }
            }
        }

        // Final flush
        byte[] data = shared.drain();
        if (data != null && data.length > 0) {
            sender.accept(data);
        }
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        try { flushThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Thread-local Level 0 buffer. Zero contention.
     */
    private static class LocalBuffer {
        private final byte[] buffer = new byte[LEVEL0_SIZE];
        private final SharedBuckets shared;
        private int pos = 0;

        LocalBuffer(SharedBuckets shared) {
            this.shared = shared;
        }

        void write(byte[] msg) {
            int len = msg.length;

            // Check if we need to overflow first
            if (pos + len > LEVEL0_SIZE) {
                if (pos > 0) {
                    shared.overflow(buffer, pos);  // Overflow current data
                    pos = 0;
                }
            }

            // Write to local buffer
            if (len <= LEVEL0_SIZE) {
                System.arraycopy(msg, 0, buffer, pos, len);
                pos += len;
            } else {
                // Large message - send directly to shared
                shared.overflow(msg, len);
            }
        }
    }

    /**
     * Shared buckets (levels 1-5). Synchronized access.
     */
    private static class SharedBuckets {
        private final byte[][] buckets = new byte[SHARED_SIZES.length][];
        private final int[] positions = new int[SHARED_SIZES.length];
        volatile long lastWriteNanos = System.nanoTime();

        SharedBuckets() {
            for (int i = 0; i < SHARED_SIZES.length; i++) {
                buckets[i] = new byte[SHARED_SIZES[i]];
            }
        }

        /**
         * Overflow data from Level 0 into shared buckets.
         */
        synchronized void overflow(byte[] data, int len) {
            lastWriteNanos = System.nanoTime();

            // Find level with room
            int level = 0;
            while (level < SHARED_SIZES.length - 1 && positions[level] + len > SHARED_SIZES[level]) {
                // Current level full - cascade to next
                if (positions[level] > 0) {
                    int nextLevel = level + 1;
                    if (positions[nextLevel] + positions[level] > SHARED_SIZES[nextLevel]) {
                        // Next level also full - recursive cascade
                        cascadeUp(level + 1);
                    }
                    System.arraycopy(buckets[level], 0, buckets[nextLevel], positions[nextLevel], positions[level]);
                    positions[nextLevel] += positions[level];
                    positions[level] = 0;
                }
                level++;
            }

            // Write to this level
            if (level < SHARED_SIZES.length && positions[level] + len <= SHARED_SIZES[level]) {
                System.arraycopy(data, 0, buckets[level], positions[level], len);
                positions[level] += len;
            }
        }

        private void cascadeUp(int fromLevel) {
            for (int i = fromLevel; i < SHARED_SIZES.length - 1; i++) {
                if (positions[i] > 0 && positions[i + 1] + positions[i] <= SHARED_SIZES[i + 1]) {
                    System.arraycopy(buckets[i], 0, buckets[i + 1], positions[i + 1], positions[i]);
                    positions[i + 1] += positions[i];
                    positions[i] = 0;
                }
            }
        }

        int highestLevel() {
            for (int i = SHARED_SIZES.length - 1; i >= 0; i--) {
                if (positions[i] > 0) return i;
            }
            return -1;
        }

        synchronized byte[] drain() {
            int total = 0;
            for (int i = 0; i < SHARED_SIZES.length; i++) {
                total += positions[i];
            }
            if (total == 0) return null;

            byte[] result = new byte[total];
            int pos = 0;

            // Drain highest levels first (older data)
            for (int i = SHARED_SIZES.length - 1; i >= 0; i--) {
                if (positions[i] > 0) {
                    System.arraycopy(buckets[i], 0, result, pos, positions[i]);
                    pos += positions[i];
                    positions[i] = 0;
                }
            }

            return result;
        }
    }
}
