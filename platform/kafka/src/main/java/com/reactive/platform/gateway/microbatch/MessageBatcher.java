package com.reactive.platform.gateway.microbatch;

/**
 * Simple interface for message batching.
 *
 * All implementations share this single-method contract:
 * - send(message) - that's it
 *
 * Behind the scenes, implementations may batch, buffer, or send immediately.
 * But the caller sees only: send(message).
 */
public interface MessageBatcher extends AutoCloseable {

    /**
     * Send a message.
     *
     * The implementation decides how to handle it:
     * - Naive: sends immediately to Kafka
     * - Striped: buffers in lock-free stripes, flushes periodically
     *
     * This is the ONLY method callers should use.
     */
    void send(byte[] message);

    @Override
    void close();
}
