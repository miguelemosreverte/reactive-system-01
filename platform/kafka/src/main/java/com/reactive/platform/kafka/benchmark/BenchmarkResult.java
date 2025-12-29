package com.reactive.platform.kafka.benchmark;

/**
 * Common result types for Kafka benchmarks.
 *
 * Uses public final fields for simple, direct access (result.throughput not result.throughput()).
 */
public final class BenchmarkResult {

    private BenchmarkResult() {} // Container class

    /**
     * Result of a single benchmark run.
     */
    public static final class RunResult {
        public final String mode;
        public final long messages;
        public final long kafkaRecords;
        public final boolean verified;
        public final long durationMs;
        public final double throughputMsgPerSec;
        public final String notes;

        public RunResult(String mode, long messages, long kafkaRecords, boolean verified,
                         long durationMs, double throughputMsgPerSec, String notes) {
            this.mode = mode;
            this.messages = messages;
            this.kafkaRecords = kafkaRecords;
            this.verified = verified;
            this.durationMs = durationMs;
            this.throughputMsgPerSec = throughputMsgPerSec;
            this.notes = notes;
        }

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
    public static final class PhaseResult {
        public final int phaseNumber;
        public final long targetRate;
        public final long achievedRate;
        public final double avgLatencyNanos;
        public final double p99LatencyNanos;
        public final long messageCount;

        public PhaseResult(int phaseNumber, long targetRate, long achievedRate,
                          double avgLatencyNanos, double p99LatencyNanos, long messageCount) {
            this.phaseNumber = phaseNumber;
            this.targetRate = targetRate;
            this.achievedRate = achievedRate;
            this.avgLatencyNanos = avgLatencyNanos;
            this.p99LatencyNanos = p99LatencyNanos;
            this.messageCount = messageCount;
        }

        public double achievedPercent() {
            return targetRate > 0 ? (achievedRate * 100.0 / targetRate) : 0;
        }
    }

    /**
     * Result of Kafka verification.
     */
    public static final class VerificationResult {
        public final boolean verified;
        public final long expectedCount;
        public final long actualCount;
        public final String errorMessage;

        public VerificationResult(boolean verified, long expectedCount, long actualCount, String errorMessage) {
            this.verified = verified;
            this.expectedCount = expectedCount;
            this.actualCount = actualCount;
            this.errorMessage = errorMessage;
        }

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
    public static final class CalibrationResult {
        public final String bucketName;
        public final long bestThroughput;
        public final long avgThroughput;
        public final double stdDevPercent;
        public final boolean regression;
        public final double changePercent;

        public CalibrationResult(String bucketName, long bestThroughput, long avgThroughput,
                                double stdDevPercent, boolean regression, double changePercent) {
            this.bucketName = bucketName;
            this.bestThroughput = bestThroughput;
            this.avgThroughput = avgThroughput;
            this.stdDevPercent = stdDevPercent;
            this.regression = regression;
            this.changePercent = changePercent;
        }
    }

    /**
     * Simple result for basic benchmarks (mode, throughput, notes).
     */
    public static final class SimpleResult {
        public final String mode;
        public final long messages;
        public final long durationMs;
        public final double throughputPerSec;
        public final String notes;

        public SimpleResult(String mode, long messages, long durationMs, double throughputPerSec, String notes) {
            this.mode = mode;
            this.messages = messages;
            this.durationMs = durationMs;
            this.throughputPerSec = throughputPerSec;
            this.notes = notes;
        }

        public static SimpleResult of(String mode, long messages, long durationMs, double throughput) {
            return new SimpleResult(mode, messages, durationMs, throughput, null);
        }

        public static SimpleResult withNotes(String mode, long messages, long durationMs, double throughput, String notes) {
            return new SimpleResult(mode, messages, durationMs, throughput, notes);
        }
    }

    /**
     * Result for batch size exploration.
     */
    public static final class BatchExplorationResult {
        public final long throughput;
        public final double avgLatencyMs;
        public final double batchesPerSec;
        public final long totalItems;
        public final long totalBatches;

        public BatchExplorationResult(long throughput, double avgLatencyMs, double batchesPerSec,
                                      long totalItems, long totalBatches) {
            this.throughput = throughput;
            this.avgLatencyMs = avgLatencyMs;
            this.batchesPerSec = batchesPerSec;
            this.totalItems = totalItems;
            this.totalBatches = totalBatches;
        }
    }
}
