package com.reactive.platform.gateway.microbatch;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Bucket-based batcher with pre-allocated memory and overflow.
 *
 * Design:
 * - Pre-allocate pool of fixed-size buckets
 * - Each thread fills its current bucket (no atomic per message)
 * - When bucket full -> swap to new bucket from pool (one atomic)
 * - Timer/threshold flushes filled buckets to Kafka
 *
 * Goal: Amortize atomic operations over bucket size.
 * At 1000 msgs/bucket: 1000x fewer atomic ops than per-message tracking.
 *
 * @deprecated Use {@link PartitionedBatcher} instead - achieves 1.11B msg/s without bucket swapping overhead.
 */
@Deprecated
public final class BucketBatcher implements AutoCloseable {

    private static final int BUCKET_SIZE = 1000;  // Messages per bucket
    private static final int POOL_SIZE = 1000;    // Pre-allocated buckets

    private final int messageSize;
    private final Consumer<byte[]> sender;

    // Pre-allocated bucket pool
    private final Queue<byte[]> bucketPool = new ConcurrentLinkedQueue<>();

    // Filled buckets ready for flush
    private final Queue<FilledBucket> filledBuckets = new ConcurrentLinkedQueue<>();

    // Thread-local current bucket (no contention on write)
    private final ThreadLocal<Bucket> currentBucket;

    // Flush thread
    private final Thread flushThread;
    private volatile boolean running = true;

    // Adaptive parameters
    private volatile int flushThresholdBuckets;
    private volatile long flushIntervalNanos;

    public BucketBatcher(Consumer<byte[]> sender, int flushThresholdMessages, long flushIntervalMs) {
        this(sender, flushThresholdMessages, flushIntervalMs, 64);
    }

    public BucketBatcher(Consumer<byte[]> sender, int flushThresholdMessages, long flushIntervalMs, int messageSize) {
        this.sender = sender;
        this.messageSize = messageSize;
        this.flushThresholdBuckets = Math.max(1, flushThresholdMessages / BUCKET_SIZE);
        this.flushIntervalNanos = flushIntervalMs * 1_000_000L;

        // Pre-allocate bucket pool
        for (int i = 0; i < POOL_SIZE; i++) {
            bucketPool.offer(new byte[BUCKET_SIZE * messageSize]);
        }

        // Thread-local bucket initialization
        this.currentBucket = ThreadLocal.withInitial(this::acquireBucket);

        // Single flush thread
        this.flushThread = Thread.ofPlatform()
            .name("bucket-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    /**
     * Submit a message. Hot path: arraycopy + increment + check. No atomic per message.
     */
    public void submit(byte[] message) {
        Bucket bucket = currentBucket.get();

        // Write to bucket (just arraycopy)
        bucket.add(message);

        // Check if bucket full
        if (bucket.isFull()) {
            // Bucket full - submit to flush queue and get new bucket
            filledBuckets.offer(new FilledBucket(bucket.data, bucket.count, System.nanoTime()));
            currentBucket.set(acquireBucket());
        }
    }

    /**
     * Update adaptive parameters.
     */
    public void setParameters(int thresholdMessages, long intervalMs) {
        this.flushThresholdBuckets = Math.max(1, thresholdMessages / BUCKET_SIZE);
        this.flushIntervalNanos = intervalMs * 1_000_000L;
    }

    private Bucket acquireBucket() {
        byte[] data = bucketPool.poll();
        if (data == null) {
            // Pool exhausted - allocate new (should be rare)
            data = new byte[BUCKET_SIZE * messageSize];
        }
        return new Bucket(data, messageSize);
    }

    private void returnBucket(byte[] data) {
        bucketPool.offer(data);
    }

    private void flushLoop() {
        long lastFlushNanos = System.nanoTime();

        while (running) {
            int pendingBuckets = filledBuckets.size();
            long elapsed = System.nanoTime() - lastFlushNanos;

            boolean shouldFlush = (pendingBuckets >= flushThresholdBuckets) ||
                                  (pendingBuckets > 0 && elapsed >= flushIntervalNanos);

            if (shouldFlush) {
                flushAll();
                lastFlushNanos = System.nanoTime();
            } else if (pendingBuckets == 0) {
                try { Thread.sleep(0, 100_000); } catch (InterruptedException e) { break; }
            } else {
                Thread.onSpinWait();
            }
        }

        // Final flush
        flushAll();
    }

    private void flushAll() {
        // Drain all filled buckets and combine into single payload
        int totalBuckets = filledBuckets.size();
        if (totalBuckets == 0) return;

        // Collect all filled buckets
        FilledBucket[] buckets = new FilledBucket[totalBuckets];
        int count = 0;
        FilledBucket fb;
        while ((fb = filledBuckets.poll()) != null && count < totalBuckets) {
            buckets[count++] = fb;
        }

        if (count == 0) return;

        // Calculate total size
        int totalMessages = 0;
        for (int i = 0; i < count; i++) {
            totalMessages += buckets[i].messageCount;
        }

        // Combine into single payload
        byte[] payload = new byte[totalMessages * messageSize];
        int pos = 0;
        for (int i = 0; i < count; i++) {
            int bytes = buckets[i].messageCount * messageSize;
            System.arraycopy(buckets[i].data, 0, payload, pos, bytes);
            pos += bytes;
            // Return bucket to pool
            returnBucket(buckets[i].data);
        }

        // Send to Kafka
        sender.accept(payload);
    }

    /**
     * Flush current thread's partial bucket.
     */
    public void flushCurrent() {
        Bucket bucket = currentBucket.get();
        if (bucket.count > 0) {
            filledBuckets.offer(new FilledBucket(bucket.data, bucket.count, System.nanoTime()));
            currentBucket.set(acquireBucket());
        }
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        try { flushThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        flushAll();
    }

    /**
     * Thread-local bucket. No synchronization needed.
     */
    private static class Bucket {
        final byte[] data;
        final int messageSize;
        int count;

        Bucket(byte[] data, int messageSize) {
            this.data = data;
            this.messageSize = messageSize;
        }

        void add(byte[] msg) {
            System.arraycopy(msg, 0, data, count * messageSize, Math.min(msg.length, messageSize));
            count++;
        }

        boolean isFull() {
            return count >= BUCKET_SIZE;
        }
    }

    /**
     * Filled bucket ready for flush.
     */
    private record FilledBucket(byte[] data, int messageCount, long filledAtNanos) {}
}
