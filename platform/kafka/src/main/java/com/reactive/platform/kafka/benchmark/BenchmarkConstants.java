package com.reactive.platform.kafka.benchmark;

/**
 * Shared constants for Kafka benchmarks.
 *
 * Centralizes magic numbers that were duplicated across 17+ benchmark files.
 * See tasks/benchmark-refactoring-tasks.md for the full refactoring plan.
 */
public final class BenchmarkConstants {

    private BenchmarkConstants() {} // Utility class

    // ========================================================================
    // Message Configuration
    // ========================================================================

    /** Standard message size for benchmarks (bytes) */
    public static final int MESSAGE_SIZE = 64;

    // ========================================================================
    // Benchmark Durations (seconds)
    // ========================================================================

    /** Quick validation - just verify things work (<5s total) */
    public static final int SMOKE_DURATION_SEC = 3;

    /** Development testing - fast but meaningful results */
    public static final int QUICK_DURATION_SEC = 15;

    /** Final validation - comprehensive test before release */
    public static final int THOROUGH_DURATION_SEC = 60;

    /** Standard brochure total duration (5 minutes) */
    public static final int BROCHURE_TOTAL_DURATION_SEC = 300;

    /** Brochure ramp-up phase (4 minutes) */
    public static final int BROCHURE_RAMP_DURATION_SEC = 240;

    /** Brochure sustained max load phase (1 minute) */
    public static final int BROCHURE_SUSTAIN_DURATION_SEC = 60;

    // ========================================================================
    // Kafka Producer Configuration
    // ========================================================================

    /** Small batch size for naive/low-latency producers (16KB) */
    public static final int BATCH_SIZE_SMALL = 16_384;

    /** Standard batch size for bulk producers (256KB) */
    public static final int BATCH_SIZE_STANDARD = 262_144;

    /** Large batch size for high-throughput producers (1MB) */
    public static final int BATCH_SIZE_LARGE = 1_048_576;

    /** Maximum batch size for extreme throughput (16MB) */
    public static final int BATCH_SIZE_MAX = 16_777_216;

    /** Standard buffer memory (128MB) */
    public static final long BUFFER_MEMORY_STANDARD = 134_217_728L;

    /** Large buffer memory for high throughput (512MB) */
    public static final long BUFFER_MEMORY_LARGE = 536_870_912L;

    /** Maximum fetch bytes for consumers (50MB) */
    public static final int FETCH_MAX_BYTES = 52_428_800;

    /** Maximum request size (100MB) */
    public static final int MAX_REQUEST_SIZE = 104_857_600;

    // ========================================================================
    // Batcher Configuration
    // ========================================================================

    /** Standard batcher threshold (16KB) - triggers flush when reached */
    public static final int BATCHER_THRESHOLD_STANDARD = 16_384;

    /** Standard batcher flush interval (ms) */
    public static final int BATCHER_INTERVAL_MS = 100;

    // ========================================================================
    // Warmup Configuration
    // ========================================================================

    /** Number of warmup messages for naive producer */
    public static final int WARMUP_MESSAGES_NAIVE = 10_000;

    /** Number of warmup batches for bulk producer */
    public static final int WARMUP_BATCHES_BULK = 100;
}
