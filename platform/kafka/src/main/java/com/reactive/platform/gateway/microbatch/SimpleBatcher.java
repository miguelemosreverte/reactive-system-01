package com.reactive.platform.gateway.microbatch;

import java.util.function.Consumer;

/**
 * Simplest possible batcher: one buffer, flush when full or timer.
 *
 * No levels, no cascade, no complexity.
 * Single component, self-calibrating through flush parameters.
 *
 * @deprecated Use {@link PartitionedBatcher} instead - achieves 1.11B msg/s.
 */
@Deprecated
public final class SimpleBatcher implements AutoCloseable {

    private static final int BUFFER_SIZE = 256 * 1024 * 1024;  // 256MB max

    private final Consumer<byte[]> sender;
    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final Thread flushThread;
    private volatile boolean running = true;

    private volatile int flushThreshold;      // Flush when this many bytes
    private volatile long flushIntervalNanos;  // Or when this much time passes

    private int position = 0;
    private long lastFlushNanos = System.nanoTime();

    public SimpleBatcher(Consumer<byte[]> sender, int flushThresholdKB, long flushIntervalMs) {
        this.sender = sender;
        this.flushThreshold = flushThresholdKB * 1024;
        this.flushIntervalNanos = flushIntervalMs * 1_000_000L;

        this.flushThread = Thread.ofPlatform()
            .name("simple-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    /**
     * Submit a message. Synchronized but minimal work.
     */
    public synchronized void submit(byte[] message) {
        int len = message.length;

        // Flush if buffer would overflow
        if (position + len > BUFFER_SIZE) {
            doFlush();
        }

        // Copy message to buffer
        System.arraycopy(message, 0, buffer, position, len);
        position += len;
    }

    public void setParameters(int flushThresholdKB, long intervalMs) {
        this.flushThreshold = flushThresholdKB * 1024;
        this.flushIntervalNanos = intervalMs * 1_000_000L;
    }

    private void flushLoop() {
        while (running) {
            long elapsed = System.nanoTime() - lastFlushNanos;
            boolean shouldFlush;

            synchronized (this) {
                shouldFlush = (position >= flushThreshold) ||
                              (position > 0 && elapsed >= flushIntervalNanos);
                if (shouldFlush) {
                    doFlush();
                }
            }

            if (!shouldFlush) {
                try { Thread.sleep(0, 100_000); } catch (InterruptedException e) { break; }
            }
        }

        // Final flush
        synchronized (this) {
            if (position > 0) doFlush();
        }
    }

    private void doFlush() {
        if (position == 0) return;

        byte[] data = new byte[position];
        System.arraycopy(buffer, 0, data, 0, position);
        position = 0;
        lastFlushNanos = System.nanoTime();

        sender.accept(data);
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        try { flushThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
