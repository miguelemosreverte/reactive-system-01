package com.reactive.platform.kafka.benchmark;

/**
 * Simple rate limiter for benchmark throughput control.
 *
 * Eliminates duplicate rate-limiting patterns found in 6+ benchmark files.
 *
 * Usage:
 * <pre>
 *   RateLimiter limiter = RateLimiter.perSecond(1_000_000); // 1M ops/sec
 *
 *   while (running) {
 *       doWork();
 *       limiter.acquire();
 *   }
 * </pre>
 *
 * For batch-based limiting:
 * <pre>
 *   RateLimiter limiter = RateLimiter.perSecond(1_000_000, 1000); // 1M/s in batches of 1000
 *
 *   while (running) {
 *       for (int i = 0; i < 1000; i++) doWork();
 *       limiter.acquireBatch();
 *   }
 * </pre>
 */
public final class RateLimiter {

    private final long targetNanosPerOp;
    private final int batchSize;
    private long batchStartNanos;
    private int batchCount;

    private RateLimiter(long targetOpsPerSecond, int batchSize) {
        this.targetNanosPerOp = 1_000_000_000L / targetOpsPerSecond;
        this.batchSize = batchSize;
        this.batchStartNanos = System.nanoTime();
        this.batchCount = 0;
    }

    /**
     * Create a rate limiter for single operations.
     *
     * @param opsPerSecond Target operations per second
     * @return Rate limiter instance
     */
    public static RateLimiter perSecond(long opsPerSecond) {
        return new RateLimiter(opsPerSecond, 1);
    }

    /**
     * Create a rate limiter for batched operations.
     *
     * @param opsPerSecond Target operations per second
     * @param batchSize Number of operations per batch
     * @return Rate limiter instance
     */
    public static RateLimiter perSecond(long opsPerSecond, int batchSize) {
        return new RateLimiter(opsPerSecond, batchSize);
    }

    /**
     * Create an unlimited rate limiter (no-op).
     */
    public static RateLimiter unlimited() {
        return new RateLimiter(Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Acquire permission for one operation.
     * Sleeps if necessary to maintain target rate.
     */
    public void acquire() throws InterruptedException {
        batchCount++;
        if (batchCount >= batchSize) {
            throttle();
        }
    }

    /**
     * Acquire permission for a batch of operations.
     * Call this after completing batchSize operations.
     */
    public void acquireBatch() throws InterruptedException {
        throttle();
    }

    /**
     * Acquire permission for specified number of operations.
     */
    public void acquire(int count) throws InterruptedException {
        batchCount += count;
        if (batchCount >= batchSize) {
            throttle();
        }
    }

    private void throttle() throws InterruptedException {
        long elapsed = System.nanoTime() - batchStartNanos;
        long targetElapsed = batchCount * targetNanosPerOp;
        long sleepNanos = targetElapsed - elapsed;

        if (sleepNanos > 1_000_000) { // Only sleep if > 1ms
            Thread.sleep(sleepNanos / 1_000_000);
        }

        reset();
    }

    /**
     * Reset the batch counter and timer.
     */
    public void reset() {
        batchStartNanos = System.nanoTime();
        batchCount = 0;
    }

    /**
     * Check if this is an unlimited rate limiter.
     */
    public boolean isUnlimited() {
        return targetNanosPerOp == 0;
    }

    /**
     * Get the target operations per second.
     */
    public long targetOpsPerSecond() {
        return 1_000_000_000L / targetNanosPerOp;
    }

    /**
     * Get the batch size.
     */
    public int batchSize() {
        return batchSize;
    }
}
