package com.reactive.platform.kafka.benchmark;

/**
 * Formatting utilities for benchmark output.
 *
 * Consolidates duplicate formatting methods across benchmark files.
 * See tasks/benchmark-refactoring-tasks.md for the full refactoring plan.
 */
public final class FormattingUtils {

    private FormattingUtils() {} // Utility class

    /**
     * Format a time interval in microseconds to human-readable form.
     * Examples: 500 -> "500µs", 1500 -> "1.5ms", 2000000 -> "2.0s"
     */
    public static String formatInterval(int micros) {
        if (micros >= 1_000_000) return String.format("%.1fs", micros / 1_000_000.0);
        if (micros >= 1_000) return String.format("%.1fms", micros / 1_000.0);
        return micros + "µs";
    }

    /**
     * Format a time interval in nanoseconds to human-readable form.
     */
    public static String formatNanos(long nanos) {
        if (nanos >= 1_000_000_000L) return String.format("%.2fs", nanos / 1_000_000_000.0);
        if (nanos >= 1_000_000L) return String.format("%.2fms", nanos / 1_000_000.0);
        if (nanos >= 1_000L) return String.format("%.2fµs", nanos / 1_000.0);
        return nanos + "ns";
    }

    /**
     * Format throughput as messages per second.
     * Uses appropriate suffix (K, M, B) for readability.
     */
    public static String formatThroughput(double msgPerSec) {
        if (msgPerSec >= 1_000_000_000) return String.format("%.2fB msg/s", msgPerSec / 1_000_000_000);
        if (msgPerSec >= 1_000_000) return String.format("%.2fM msg/s", msgPerSec / 1_000_000);
        if (msgPerSec >= 1_000) return String.format("%.2fK msg/s", msgPerSec / 1_000);
        return String.format("%.0f msg/s", msgPerSec);
    }

    /**
     * Format bytes to human-readable form (KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format("%.2f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L) return String.format("%.2f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024L) return String.format("%.2f KB", bytes / 1_024.0);
        return bytes + " B";
    }

    /**
     * Format a count with appropriate suffix (K, M, B).
     */
    public static String formatCount(long count) {
        if (count >= 1_000_000_000L) return String.format("%.2fB", count / 1_000_000_000.0);
        if (count >= 1_000_000L) return String.format("%.2fM", count / 1_000_000.0);
        if (count >= 1_000L) return String.format("%.2fK", count / 1_000.0);
        return String.valueOf(count);
    }
}
