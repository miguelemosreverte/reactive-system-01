package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.config.PlatformConfig;

/**
 * Shared constants for Kafka benchmarks.
 *
 * All values are loaded from HOCON configuration (reference.conf).
 * This enables runtime customization via environment variables or application.conf.
 *
 * Configuration path: platform.kafka-benchmark
 */
public final class BenchmarkConstants {

    private BenchmarkConstants() {} // Utility class

    // Lazy-loaded config instance
    private static volatile PlatformConfig.KafkaBenchmarkConfig config;

    private static PlatformConfig.KafkaBenchmarkConfig config() {
        if (config == null) {
            synchronized (BenchmarkConstants.class) {
                if (config == null) {
                    config = PlatformConfig.load().kafkaBenchmark();
                }
            }
        }
        return config;
    }

    // ========================================================================
    // Message Configuration
    // ========================================================================

    /** Standard message size for benchmarks (bytes) */
    public static final int MESSAGE_SIZE = config().messageSize();

    // ========================================================================
    // Benchmark Durations (seconds)
    // ========================================================================

    /** Quick validation - just verify things work (<5s total) */
    public static final int SMOKE_DURATION_SEC = config().smokeDurationSec();

    /** Development testing - fast but meaningful results */
    public static final int QUICK_DURATION_SEC = config().quickDurationSec();

    /** Final validation - comprehensive test before release */
    public static final int THOROUGH_DURATION_SEC = config().thoroughDurationSec();

    /** Standard brochure total duration (5 minutes) */
    public static final int BROCHURE_TOTAL_DURATION_SEC = config().brochureTotalDurationSec();

    /** Brochure ramp-up phase (4 minutes) */
    public static final int BROCHURE_RAMP_DURATION_SEC = config().brochureRampDurationSec();

    /** Brochure sustained max load phase (1 minute) */
    public static final int BROCHURE_SUSTAIN_DURATION_SEC = config().brochureSustainDurationSec();

    // ========================================================================
    // Kafka Producer Configuration
    // ========================================================================

    /** Small batch size for naive/low-latency producers (16KB) */
    public static final int BATCH_SIZE_SMALL = config().batchSizeSmall();

    /** Standard batch size for bulk producers (256KB) */
    public static final int BATCH_SIZE_STANDARD = config().batchSizeStandard();

    /** Large batch size for high-throughput producers (1MB) */
    public static final int BATCH_SIZE_LARGE = config().batchSizeLarge();

    /** Maximum batch size for extreme throughput (16MB) */
    public static final int BATCH_SIZE_MAX = config().batchSizeMax();

    /** Standard buffer memory (128MB) */
    public static final long BUFFER_MEMORY_STANDARD = config().bufferMemoryStandard();

    /** Large buffer memory for high throughput (512MB) */
    public static final long BUFFER_MEMORY_LARGE = config().bufferMemoryLarge();

    /** Maximum fetch bytes for consumers (50MB) */
    public static final int FETCH_MAX_BYTES = config().fetchMaxBytes();

    /** Maximum request size (100MB) */
    public static final int MAX_REQUEST_SIZE = config().maxRequestSize();

    // ========================================================================
    // Batcher Configuration
    // ========================================================================

    /** Standard batcher threshold (16KB) - triggers flush when reached */
    public static final int BATCHER_THRESHOLD_STANDARD = config().batcherThresholdStandard();

    /** Standard batcher flush interval (ms) */
    public static final int BATCHER_INTERVAL_MS = config().batcherIntervalMs();

    // ========================================================================
    // Warmup Configuration
    // ========================================================================

    /** Number of warmup messages for naive producer */
    public static final int WARMUP_MESSAGES_NAIVE = config().warmupMessagesNaive();

    /** Number of warmup batches for bulk producer */
    public static final int WARMUP_BATCHES_BULK = config().warmupBatchesBulk();

    // ========================================================================
    // Producer Configuration (from HOCON)
    // ========================================================================

    /** Compression type for high-throughput producers */
    public static final String COMPRESSION_TYPE = config().compression();

    /** Linger time for high-throughput mode (ms) */
    public static final int LINGER_MS_HIGH_THROUGHPUT = config().lingerMsHighThroughput();

    /** Linger time for low-latency mode (ms) */
    public static final int LINGER_MS_LOW_LATENCY = config().lingerMsLowLatency();

    /** Linger time for fire-and-forget mode (ms) */
    public static final int LINGER_MS_FIRE_AND_FORGET = config().lingerMsFireAndForget();
}
