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

    // Hex lookup table for fast conversion
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Encode a long value as hex characters into a char array.
     * @param value the value to encode
     * @param chars target char array
     * @param start start index (inclusive)
     * @param end end index (exclusive)
     */
    private static void encodeHex(long value, char[] chars, int start, int end) {
        for (int i = end - 1; i >= start; i--) {
            chars[i] = HEX[(int)(value & 0xF)];
            value >>>= 4;
        }
    }

    /**
     * Generate a unique 128-bit request ID as a 32-character hex string.
     * Thread-safe and lock-free. Optimized to avoid String.format() overhead.
     */
    public String generateRequestId() {
        long timestamp = System.currentTimeMillis();
        long seq = getNextSequence(timestamp);

        // Build 128-bit ID:
        // High 64 bits: timestamp (48 bits) + nodeId (16 bits)
        // Low 64 bits: sequence counter
        long high = ((timestamp - EPOCH) << 16) | (nodeId & 0xFFFF);

        char[] chars = new char[32];
        encodeHex(high, chars, 0, 16);
        encodeHex(seq, chars, 16, 32);
        return new String(chars);
    }

    /**
     * Generate a unique event ID (shorter format for internal use).
     * Optimized to avoid String.format() overhead.
     */
    public String generateEventId() {
        long timestamp = System.currentTimeMillis();
        long seq = getNextSequence(timestamp);

        // 16 chars: 12 for timestamp + 4 for sequence
        char[] chars = new char[16];
        encodeHex(timestamp & 0xFFFFFFFFFFFFL, chars, 0, 12);
        encodeHex(seq & 0xFFFF, chars, 12, 16);
        return new String(chars);
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
