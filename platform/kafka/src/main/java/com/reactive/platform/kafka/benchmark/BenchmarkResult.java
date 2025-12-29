package com.reactive.platform.kafka.benchmark;

/**
 * Common result types for Kafka benchmarks.
 *
 * Consolidates 7+ duplicate Result/PhaseResult records across benchmark files.
 * See tasks/benchmark-refactoring-tasks.md for the full refactoring plan.
 */
public final class BenchmarkResult {

    private BenchmarkResult() {} // Container class

    /**
     * Result of a single benchmark run.
     */
    public record RunResult(
        String mode,
        long messages,
        long kafkaRecords,
        boolean verified,
        long durationMs,
        double throughputMsgPerSec,
        String notes
    ) {
        public static RunResult success(String mode, long messages, long kafkaRecords,
                                        long durationMs, double throughput) {
            return new RunResult(mode, messages, kafkaRecords, true, durationMs, throughput, null);
        }

        public static RunResult failure(String mode, long messages, String error) {
            return new RunResult(mode, messages, 0, false, 0, 0, error);
        }
    }

    /**
     * Result of a benchmark phase (used in ramping benchmarks).
     */
    public record PhaseResult(
        int phaseNumber,
        long targetRate,
        long achievedRate,
        double avgLatencyNanos,
        double p99LatencyNanos,
        long messageCount
    ) {
        public double achievedPercent() {
            return targetRate > 0 ? (achievedRate * 100.0 / targetRate) : 0;
        }
    }

    /**
     * Result of Kafka verification.
     */
    public record VerificationResult(
        boolean verified,
        long expectedCount,
        long actualCount,
        String errorMessage
    ) {
        public static VerificationResult success(long count) {
            return new VerificationResult(true, count, count, null);
        }

        public static VerificationResult failure(long expected, long actual, String error) {
            return new VerificationResult(false, expected, actual, error);
        }

        public double verifiedPercent() {
            return expectedCount > 0 ? (actualCount * 100.0 / expectedCount) : 0;
        }
    }

    /**
     * Result of calibration benchmark.
     */
    public record CalibrationResult(
        String bucketName,
        long bestThroughput,
        long avgThroughput,
        double stdDevPercent,
        boolean regression,
        double changePercent
    ) {}
}
