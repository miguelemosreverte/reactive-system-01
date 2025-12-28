package com.reactive.platform.gateway.microbatch;

import java.util.function.Consumer;

/**
 * Naive message batcher - sends immediately, no batching.
 *
 * This is the simplest implementation:
 * - send(message) → immediately forwards to sender
 * - No buffering, no batching, no magic
 *
 * Use as baseline comparison for other implementations.
 */
public final class NaiveBatcher implements MessageBatcher {

    private final Consumer<byte[]> sender;

    public NaiveBatcher(Consumer<byte[]> sender) {
        this.sender = sender;
    }

    @Override
    public void send(byte[] message) {
        sender.accept(message);
    }

    @Override
    public void close() {
        // Nothing to close
    }
}
