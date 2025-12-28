package com.reactive.platform.gateway.microbatch;

import java.util.function.Consumer;

/**
 * InlineBatcher - wrapper around DirectFlushBatcher.
 *
 * Provides the clean send(message) interface with maximum throughput.
 * No atomics on hot path - each thread flushes directly.
 */
public final class InlineBatcher implements MessageBatcher {

    private final DirectFlushBatcher batcher;

    /**
     * Create an InlineBatcher.
     *
     * @param sender Where to send flushed batches (e.g., Kafka producer)
     */
    public InlineBatcher(Consumer<byte[]> sender) {
        this.batcher = new DirectFlushBatcher(sender);
    }

    /**
     * Send a message.
     *
     * No atomics, no locks - just ThreadLocal buffer + arraycopy.
     * When buffer is full, flush directly to sender.
     */
    @Override
    public void send(byte[] message) {
        batcher.send(message);
    }

    @Override
    public void close() {
        batcher.close();
    }
}
