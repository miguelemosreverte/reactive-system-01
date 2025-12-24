package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Time-windowed metrics for historical analysis.
 */
public record HistoricalBucket(
    @JsonProperty("bucket_start_ms") long bucketStartMs,
    @JsonProperty("bucket_duration_ms") long bucketDurationMs,
    @JsonProperty("avg_throughput") double avgThroughput,
    @JsonProperty("avg_latency_ms") double avgLatencyMs,
    @JsonProperty("max_latency_ms") double maxLatencyMs,
    @JsonProperty("error_count") long errorCount,
    @JsonProperty("heap_used_avg_percent") double heapUsedAvgPercent
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long bucketStartMs;
        private long bucketDurationMs = 60000; // 1 minute default
        private double avgThroughput;
        private double avgLatencyMs;
        private double maxLatencyMs;
        private long errorCount;
        private double heapUsedAvgPercent;

        public Builder bucketStartMs(long v) { this.bucketStartMs = v; return this; }
        public Builder bucketDurationMs(long v) { this.bucketDurationMs = v; return this; }
        public Builder avgThroughput(double v) { this.avgThroughput = v; return this; }
        public Builder avgLatencyMs(double v) { this.avgLatencyMs = v; return this; }
        public Builder maxLatencyMs(double v) { this.maxLatencyMs = v; return this; }
        public Builder errorCount(long v) { this.errorCount = v; return this; }
        public Builder heapUsedAvgPercent(double v) { this.heapUsedAvgPercent = v; return this; }

        public HistoricalBucket build() {
            return new HistoricalBucket(
                bucketStartMs, bucketDurationMs, avgThroughput,
                avgLatencyMs, maxLatencyMs, errorCount, heapUsedAvgPercent
            );
        }
    }
}
