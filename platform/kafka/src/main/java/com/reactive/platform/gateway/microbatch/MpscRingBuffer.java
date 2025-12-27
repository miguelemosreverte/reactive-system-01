package com.reactive.platform.gateway.microbatch;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Lock-free Multi-Producer Single-Consumer ring buffer.
 *
 * Zero-allocation on the hot path - items stored in pre-allocated array.
 * Uses sequence numbers for coordination (LMAX Disruptor inspired).
 *
 * Design:
 * - Pre-allocated AtomicReferenceArray (power of 2 size)
 * - Producers claim slots via atomic increment, then publish
 * - Consumer reads published slots in order
 * - No Node allocation per item (unlike ConcurrentLinkedQueue)
 *
 * @param <T> Element type
 */
public final class MpscRingBuffer<T> {

    private static final int DEFAULT_CAPACITY = 65536; // 64K slots

    private final AtomicReferenceArray<T> buffer;
    private final int mask;
    private final int capacity;

    // Producer: next slot to claim
    private final AtomicLong producerSequence = new AtomicLong(0);

    // Consumer: next slot to read (only accessed by consumer thread)
    private long consumerSequence = 0;

    // Published: highest sequence that's been fully written
    // Using array of longs for per-slot publish status (avoids false sharing)
    private final AtomicLong[] published;

    public MpscRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public MpscRingBuffer(int requestedCapacity) {
        // Round up to power of 2
        this.capacity = nextPowerOfTwo(requestedCapacity);
        this.mask = capacity - 1;
        this.buffer = new AtomicReferenceArray<>(capacity);
        this.published = new AtomicLong[capacity];
        for (int i = 0; i < capacity; i++) {
            published[i] = new AtomicLong(-1); // -1 = not published
        }
    }

    private static int nextPowerOfTwo(int n) {
        int highestBit = Integer.highestOneBit(n);
        return n == highestBit ? n : highestBit << 1;
    }

    /**
     * Offer item to buffer. Returns true if successful, false if full.
     * Lock-free, wait-free for producers.
     */
    public boolean offer(T item) {
        long seq;
        int idx;

        // Claim a slot
        do {
            seq = producerSequence.get();
            idx = (int) (seq & mask);

            // Check if slot is available (consumer has read past it)
            // Slot is available if consumer has processed seq - capacity
            long wrapPoint = seq - capacity;
            if (wrapPoint >= consumerSequence && wrapPoint >= 0) {
                // Buffer full - consumer hasn't caught up
                return false;
            }
        } while (!producerSequence.compareAndSet(seq, seq + 1));

        // Write item and publish
        buffer.set(idx, item);
        published[idx].set(seq); // Mark as published with sequence number

        return true;
    }

    /**
     * Spin-offer: keeps trying until successful.
     * Use when you can't afford to drop items.
     */
    public void offerSpin(T item) {
        while (!offer(item)) {
            Thread.onSpinWait();
        }
    }

    /**
     * Poll single item. Returns null if empty.
     * Only call from consumer thread.
     */
    public T poll() {
        int idx = (int) (consumerSequence & mask);

        // Check if item is published
        if (published[idx].get() != consumerSequence) {
            return null; // Not yet published
        }

        T item = buffer.get(idx);
        buffer.set(idx, null); // Help GC
        published[idx].set(-1); // Mark as consumed
        consumerSequence++;

        return item;
    }

    /**
     * Drain up to maxItems into array. Returns count drained.
     * Zero-allocation drain - writes directly to provided array.
     * Only call from consumer thread.
     */
    public int drain(T[] array, int maxItems) {
        return drain(array, maxItems, 0);
    }

    /**
     * Drain with offset into array.
     */
    public int drain(T[] array, int maxItems, int offset) {
        int count = 0;

        while (count < maxItems) {
            int idx = (int) (consumerSequence & mask);

            // Check if item is published at this sequence
            if (published[idx].get() != consumerSequence) {
                break; // Gap or not yet published
            }

            array[offset + count] = buffer.get(idx);
            buffer.set(idx, null); // Help GC
            published[idx].set(-1); // Mark slot as consumed
            consumerSequence++;
            count++;
        }

        return count;
    }

    /**
     * Approximate size. May be stale.
     */
    public int size() {
        long prod = producerSequence.get();
        return (int) Math.max(0, prod - consumerSequence);
    }

    /**
     * Check if empty.
     */
    public boolean isEmpty() {
        int idx = (int) (consumerSequence & mask);
        return published[idx].get() != consumerSequence;
    }

    /**
     * Capacity of buffer.
     */
    public int capacity() {
        return capacity;
    }
}
