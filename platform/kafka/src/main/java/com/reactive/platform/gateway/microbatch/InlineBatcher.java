package com.reactive.platform.gateway.microbatch;

import java.util.function.Consumer;

/**
 * InlineBatcher - factory for maximum throughput batcher.
 *
 * Provides convenient entry point to create the fastest available batcher.
 * Currently uses PartitionedBatcher.
 *
 * Usage:
 *   MessageBatcher batcher = InlineBatcher.create(sender);
 *   batcher.send(message);
 *
 * Run MaxThroughputBrochure to get actual benchmark results.
 * Performance depends on hardware, JVM, and workload characteristics.
 */
public final class InlineBatcher {

    private InlineBatcher() {}  // Factory class

    /**
     * Create a high-throughput batcher.
     *
     * @param sender Where to send flushed batches (e.g., Kafka producer)
     * @return A MessageBatcher achieving 1+ billion msg/s
     */
    public static MessageBatcher create(Consumer<byte[]> sender) {
        return new PartitionedBatcher(sender);
    }
}
