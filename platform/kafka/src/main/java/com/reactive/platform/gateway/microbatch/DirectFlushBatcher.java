package com.reactive.platform.gateway.microbatch;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * DirectFlushBatcher - each thread flushes directly, no shared atomics.
 *
 * Design:
 * - Each thread has its own buffer (ThreadLocal)
 * - When buffer is full, that thread flushes directly to sender
 * - No atomics on hot path, no queue, no coordination
 *
 * This achieves maximum throughput by eliminating all contention.
 */
public final class DirectFlushBatcher implements MessageBatcher {

    private static final int BUFFER_SIZE = 1024 * 1024;  // 1MB per thread
    private static final int FLUSH_THRESHOLD = 900 * 1024;  // Flush at 900KB

    private final Consumer<byte[]> sender;
    private final ThreadLocal<Buffer> buffers = ThreadLocal.withInitial(Buffer::new);

    public DirectFlushBatcher(Consumer<byte[]> sender) {
        this.sender = sender;
    }

    /**
     * Send a message.
     *
     * HOT PATH - absolutely no atomics, no locks:
     * 1. Get ThreadLocal buffer (cached after first call)
     * 2. arraycopy message to buffer
     * 3. If threshold reached, flush directly to sender
     *
     * Each thread handles its own flushing - no coordination needed.
     */
    @Override
    public void send(byte[] message) {
        Buffer buf = buffers.get();

        int len = message.length;

        // Overflow check - flush first if needed
        if (buf.pos + len > BUFFER_SIZE) {
            flush(buf);
        }

        // Copy message to buffer
        System.arraycopy(message, 0, buf.data, buf.pos, len);
        buf.pos += len;

        // Threshold flush
        if (buf.pos >= FLUSH_THRESHOLD) {
            flush(buf);
        }
    }

    private void flush(Buffer buf) {
        if (buf.pos > 0) {
            // Direct send - no queue, no coordination
            sender.accept(Arrays.copyOf(buf.data, buf.pos));
            buf.pos = 0;
        }
    }

    @Override
    public void close() {
        // Flush current thread's buffer
        flush(buffers.get());
    }

    private static final class Buffer {
        final byte[] data = new byte[BUFFER_SIZE];
        int pos = 0;
    }
}
