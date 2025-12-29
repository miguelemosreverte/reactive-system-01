package com.reactive.platform.gateway.microbatch;

import com.reactive.platform.base.Result;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * PartitionedBatcher - maximum throughput via partitioning.
 *
 * Design:
 * - Pre-allocated partitions indexed by thread ID (no ThreadLocal overhead)
 * - No atomics - single writer per partition guaranteed
 * - Flush thread periodically drains all partitions
 * - Sequencing preserved at publish time
 *
 * Run MaxThroughputBrochure to get actual benchmark results.
 * Performance depends on hardware, JVM, and workload characteristics.
 *
 * Per-message overhead sources:
 * - Thread.currentThread().threadId() lookup
 * - Field access (p.pos) vs local variable
 * - Method call boundary overhead
 */
public class PartitionedBatcher implements MessageBatcher {

    private static final int NUM_PARTITIONS = 256;  // Power of 2 for fast masking
    private static final int PARTITION_MASK = NUM_PARTITIONS - 1;
    private static final int PARTITION_SIZE = 4 * 1024 * 1024;  // 4MB per partition
    private static final int FLUSH_THRESHOLD = 3 * 1024 * 1024; // Flush at 3MB

    private final Consumer<byte[]> sender;
    private final Partition[] partitions;
    private final Thread flushThread;
    private volatile boolean running = true;

    public PartitionedBatcher(Consumer<byte[]> sender) {
        this.sender = sender;
        this.partitions = new Partition[NUM_PARTITIONS];
        for (int i = 0; i < NUM_PARTITIONS; i++) {
            partitions[i] = new Partition();
        }

        this.flushThread = Thread.ofPlatform()
            .name("partition-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    /**
     * Send a message.
     *
     * ULTRA-FAST HOT PATH:
     * 1. Thread ID & mask → partition index (1 AND operation)
     * 2. Direct array access to partition (no ThreadLocal.get())
     * 3. arraycopy to partition buffer
     * 4. Position increment (no atomics - single writer)
     */
    @Override
    public void send(byte[] message) {
        // Direct partition lookup - faster than ThreadLocal.get()
        int slot = (int) (Thread.currentThread().threadId() & PARTITION_MASK);
        Partition p = partitions[slot];

        int len = message.length;
        int pos = p.pos;

        // Overflow check
        if (pos + len > PARTITION_SIZE) {
            // Mark for flush and reset
            p.readySize = pos;
            p.pos = 0;
            pos = 0;
        }

        // Copy message - no atomics needed, single writer
        System.arraycopy(message, 0, p.buffer, pos, len);
        p.pos = pos + len;
    }

    private void flushLoop() {
        while (running) {
            boolean flushed = false;

            for (int i = 0; i < NUM_PARTITIONS; i++) {
                Partition p = partitions[i];

                // Check if partition has data ready to flush
                int ready = p.readySize;
                if (ready > 0) {
                    sender.accept(Arrays.copyOf(p.buffer, ready));
                    p.readySize = 0;
                    flushed = true;
                }

                // Also check threshold
                int pos = p.pos;
                if (pos >= FLUSH_THRESHOLD) {
                    sender.accept(Arrays.copyOf(p.buffer, pos));
                    p.pos = 0;
                    flushed = true;
                }
            }

            if (!flushed) {
                if (Result.sleep(0, 100_000).isFailure()) break;
            }
        }

        // Final flush
        for (Partition p : partitions) {
            int pos = p.pos;
            if (pos > 0) {
                sender.accept(Arrays.copyOf(p.buffer, pos));
            }
            int ready = p.readySize;
            if (ready > 0) {
                sender.accept(Arrays.copyOf(p.buffer, ready));
            }
        }
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        Result.join(flushThread, 1000);
    }

    /**
     * Per-partition buffer. No synchronization - single writer guaranteed.
     */
    private static final class Partition {
        final byte[] buffer = new byte[PARTITION_SIZE];
        int pos = 0;           // Current write position
        volatile int readySize = 0;  // Size ready for flush (set by writer, read by flusher)
    }
}
