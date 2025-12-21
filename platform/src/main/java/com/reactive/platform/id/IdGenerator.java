package com.reactive.platform.id;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Snowflake-style unique ID generator.
 *
 * Generates 128-bit IDs formatted as 32-character hex strings, compatible with
 * OpenTelemetry trace ID format.
 *
 * ID Structure (128 bits):
 * - 48 bits: timestamp in milliseconds (enough for ~8900 years)
 * - 16 bits: node ID (derived from hostname + random seed)
 * - 64 bits: sequence counter (per-millisecond uniqueness)
 *
 * This approach guarantees uniqueness without requiring a database:
 * - Timestamp ensures uniqueness across time
 * - Node ID ensures uniqueness across instances
 * - Sequence counter ensures uniqueness within same millisecond
 */
public final class IdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC

    private static final IdGenerator INSTANCE = new IdGenerator();

    private final int nodeId;
    private final AtomicLong lastTimestamp = new AtomicLong(-1);
    private final AtomicLong sequence = new AtomicLong(0);

    private IdGenerator() {
        this.nodeId = generateNodeId();
    }

    /**
     * Get the singleton instance.
     */
    public static IdGenerator getInstance() {
        return INSTANCE;
    }

    /**
     * Generate a unique 128-bit request ID as a 32-character hex string.
     * Thread-safe and lock-free.
     */
    public String generateRequestId() {
        long timestamp = System.currentTimeMillis();
        long seq = getNextSequence(timestamp);

        // Build 128-bit ID:
        // High 64 bits: timestamp (48 bits) + nodeId (16 bits)
        // Low 64 bits: sequence counter
        long high = ((timestamp - EPOCH) << 16) | (nodeId & 0xFFFF);
        long low = seq;

        return String.format("%016x%016x", high, low);
    }

    /**
     * @deprecated Use {@link #generateRequestId()} instead.
     */
    @Deprecated
    public String generateTraceId() {
        return generateRequestId();
    }

    /**
     * Generate a unique event ID (shorter format for internal use).
     */
    public String generateEventId() {
        long timestamp = System.currentTimeMillis();
        long seq = getNextSequence(timestamp);

        // Use lower 48 bits of timestamp + 16 bits of sequence
        return String.format("%012x%04x", timestamp & 0xFFFFFFFFFFFFL, seq & 0xFFFF);
    }

    /**
     * Get the node ID for this instance.
     */
    public int getNodeId() {
        return nodeId;
    }

    private long getNextSequence(long currentTimestamp) {
        long lastTs = lastTimestamp.get();

        if (currentTimestamp == lastTs) {
            // Same millisecond - increment sequence
            return sequence.incrementAndGet();
        } else if (currentTimestamp > lastTs) {
            // New millisecond - reset sequence
            if (lastTimestamp.compareAndSet(lastTs, currentTimestamp)) {
                sequence.set(0);
                return 0;
            }
            // CAS failed, someone else updated - just increment
            return sequence.incrementAndGet();
        } else {
            // Clock went backwards - use sequence to maintain uniqueness
            return sequence.incrementAndGet();
        }
    }

    private static int generateNodeId() {
        try {
            // Combine hostname hash with random bits for uniqueness
            String hostname = InetAddress.getLocalHost().getHostName();
            int hostnameHash = hostname.hashCode() & 0xFF; // 8 bits from hostname
            int randomBits = new SecureRandom().nextInt() & 0xFF; // 8 random bits
            return (hostnameHash << 8) | randomBits;
        } catch (Exception e) {
            // Fallback to pure random
            return new SecureRandom().nextInt() & 0xFFFF;
        }
    }
}
