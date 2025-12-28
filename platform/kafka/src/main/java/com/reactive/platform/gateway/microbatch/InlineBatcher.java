package com.reactive.platform.gateway.microbatch;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Inline batcher with back-pressure.
 *
 * When downstream (Kafka) can't keep up:
 * - Queue fills to max capacity
 * - send() blocks until space available
 * - Natural back-pressure propagates to caller
 *
 * In HTTP context: caller can catch and return 429 Too Many Requests
 */
public final class InlineBatcher implements MessageBatcher {

    private static final int BUFFER_SIZE = 1024 * 1024;  // 1MB per thread buffer
    private static final int FLUSH_AT = 900 * 1024;      // Flush at 900KB
    private static final int MAX_QUEUE_SIZE = 256;       // Max pending batches (256MB)

    private final Consumer<byte[]> sender;
    private final BlockingQueue<byte[]> queue;
    private final Thread flusher;
    private volatile boolean running = true;

    // Back-pressure metrics
    private final AtomicLong backPressureCount = new AtomicLong(0);

    // ThreadLocal buffers
    private final ThreadLocal<Buffer> buffers = ThreadLocal.withInitial(Buffer::new);

    public InlineBatcher(Consumer<byte[]> sender) {
        this(sender, MAX_QUEUE_SIZE);
    }

    public InlineBatcher(Consumer<byte[]> sender, int maxQueueSize) {
        this.sender = sender;
        this.queue = new ArrayBlockingQueue<>(maxQueueSize);
        this.flusher = Thread.ofPlatform()
            .name("inline-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    private void flushLoop() {
        while (running || !queue.isEmpty()) {
            try {
                byte[] batch = queue.poll();
                if (batch != null) {
                    sender.accept(batch);
                } else {
                    Thread.sleep(0, 100_000);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
        // Drain remaining
        byte[] batch;
        while ((batch = queue.poll()) != null) {
            sender.accept(batch);
        }
    }

    /**
     * Send a message.
     *
     * BACK-PRESSURE: If queue is full, this method blocks until space available.
     * Caller can wrap in try-catch and return HTTP 429 if needed.
     *
     * @throws BackPressureException if interrupted while waiting (queue full)
     */
    @Override
    public void send(byte[] message) {
        Buffer buf = buffers.get();

        int len = message.length;
        int pos = buf.pos;

        // Overflow - flush first
        if (pos + len > BUFFER_SIZE) {
            flushBuffer(buf, pos);
            pos = 0;
        }

        // Copy message
        System.arraycopy(message, 0, buf.data, pos, len);
        buf.pos = pos + len;

        // Threshold flush
        if (buf.pos >= FLUSH_AT) {
            flushBuffer(buf, buf.pos);
            buf.pos = 0;
        }
    }

    private void flushBuffer(Buffer buf, int size) {
        if (size <= 0) return;

        byte[] batch = Arrays.copyOf(buf.data, size);

        // BACK-PRESSURE: offer with blocking
        while (!queue.offer(batch)) {
            backPressureCount.incrementAndGet();
            try {
                // Block until space available
                queue.put(batch);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BackPressureException("Queue full, back-pressure applied");
            }
        }
    }

    /**
     * Check if back-pressure is being applied.
     */
    public boolean isBackPressured() {
        return queue.remainingCapacity() == 0;
    }

    /**
     * Get count of back-pressure events.
     */
    public long getBackPressureCount() {
        return backPressureCount.get();
    }

    /**
     * Get current queue size.
     */
    public int getQueueSize() {
        return queue.size();
    }

    @Override
    public void close() {
        // Flush current thread's buffer
        Buffer buf = buffers.get();
        if (buf.pos > 0) {
            try {
                queue.put(Arrays.copyOf(buf.data, buf.pos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            buf.pos = 0;
        }

        running = false;
        flusher.interrupt();
        try { flusher.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Buffer {
        final byte[] data = new byte[BUFFER_SIZE];
        int pos = 0;
    }

    /**
     * Thrown when back-pressure is applied and caller is interrupted.
     */
    public static class BackPressureException extends RuntimeException {
        public BackPressureException(String message) {
            super(message);
        }
    }
}
