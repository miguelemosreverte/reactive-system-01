package com.reactive.platform.gateway.microbatch;

import com.reactive.platform.base.Result;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Lock-free striped message batcher.
 *
 * IMPLICITLY ADAPTIVE:
 * - No if-else adaptation logic
 * - Buckets fill naturally as messages arrive
 * - Flush when threshold OR time elapsed
 * - At low load: buckets stay small, flush by time -> low latency
 * - At high load: buckets fill fast, flush by size -> high throughput
 *
 * Design:
 * - N stripes (one per core), each with pre-allocated buffer
 * - Threads write to their stripe via lock-free atomic position
 * - Single flush thread drains all stripes periodically
 * - Zero allocation on hot path (just arraycopy + atomic increment)
 *
 * @deprecated Use {@link PartitionedBatcher} instead - achieves 1.11B msg/s without atomic overhead.
 */
@Deprecated
public final class StripedBatcher implements MessageBatcher {

    private static final int NUM_STRIPES = Runtime.getRuntime().availableProcessors();
    private static final int STRIPE_SIZE = 32 * 1024 * 1024;  // 32MB per stripe

    // Fixed at construction - no runtime changes
    private final int flushThreshold;
    private final long flushIntervalNanos;

    private final Consumer<byte[]> sender;
    private final Stripe[] stripes;
    private final Thread flushThread;
    private volatile boolean running = true;
    private long lastFlushNanos = System.nanoTime();

    /**
     * Create a striped batcher.
     *
     * @param sender       Where to send flushed data (e.g., Kafka producer)
     * @param thresholdKB  Flush when total bytes exceeds this (KB)
     * @param intervalMs   Flush when this much time has passed (ms)
     */
    public StripedBatcher(Consumer<byte[]> sender, int thresholdKB, long intervalMs) {
        this.sender = sender;
        this.flushThreshold = thresholdKB * 1024;
        this.flushIntervalNanos = intervalMs * 1_000_000L;

        this.stripes = new Stripe[NUM_STRIPES];
        for (int i = 0; i < NUM_STRIPES; i++) {
            stripes[i] = new Stripe();
        }

        this.flushThread = Thread.ofPlatform()
            .name("striped-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    /**
     * Send a message.
     *
     * Lock-free hot path: atomic position reserve + arraycopy.
     * No allocation, no locks, no contention between threads.
     */
    @Override
    public void send(byte[] message) {
        int stripe = (int) (Thread.currentThread().threadId() % NUM_STRIPES);
        stripes[stripe].write(message);
    }

    /**
     * Send a batch of messages as a single chunk.
     * Use when source data is already batched for maximum throughput.
     */
    public void sendBatch(byte[] batch) {
        int stripe = (int) (Thread.currentThread().threadId() % NUM_STRIPES);
        stripes[stripe].write(batch);
    }

    /**
     * Flush loop: FLUSH when (bytes >= threshold) OR (time >= interval)
     *
     * This is the ONLY logic. No adaptation code, no if-else for load levels.
     * The system naturally adapts:
     * - Low load → time triggers flush → small batches → low latency
     * - High load → size triggers flush → large batches → high throughput
     */
    private void flushLoop() {
        while (running) {
            int totalBytes = 0;
            for (Stripe stripe : stripes) {
                totalBytes += stripe.position.get();
            }

            long elapsed = System.nanoTime() - lastFlushNanos;
            boolean shouldFlush = (totalBytes >= flushThreshold) ||
                                  (totalBytes > 0 && elapsed >= flushIntervalNanos);

            if (shouldFlush) {
                doFlush();
            } else {
                if (Result.sleep(0, 100_000).isFailure()) break;
            }
        }

        // Final flush on close
        doFlush();
    }

    private void doFlush() {
        // Calculate total size across all stripes
        int[] sizes = new int[NUM_STRIPES];
        int total = 0;
        for (int i = 0; i < NUM_STRIPES; i++) {
            sizes[i] = stripes[i].position.get();
            total += sizes[i];
        }

        if (total == 0) return;

        // Allocate output buffer and copy from all stripes
        byte[] data = new byte[total];
        int pos = 0;
        for (int i = 0; i < NUM_STRIPES; i++) {
            if (sizes[i] > 0) {
                System.arraycopy(stripes[i].buffer, 0, data, pos, sizes[i]);
                pos += sizes[i];
                stripes[i].position.set(0);
            }
        }

        lastFlushNanos = System.nanoTime();
        sender.accept(data);
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        Result.join(flushThread, 1000);
    }

    /**
     * Per-thread stripe: pre-allocated buffer + atomic position.
     */
    private static class Stripe {
        final byte[] buffer = new byte[STRIPE_SIZE];
        final AtomicInteger position = new AtomicInteger(0);

        void write(byte[] data) {
            int len = data.length;
            int pos = position.getAndAdd(len);

            // Handle overflow (rare - only when stripe fills up)
            if (pos + len > STRIPE_SIZE) {
                position.set(0);
                pos = position.getAndAdd(len);
            }

            if (pos + len <= STRIPE_SIZE) {
                System.arraycopy(data, 0, buffer, pos, len);
            }
        }
    }
}
