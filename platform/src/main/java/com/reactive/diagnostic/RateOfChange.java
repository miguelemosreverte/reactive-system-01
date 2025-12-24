package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rate of change - trends that predict future problems.
 * Growing queues, rising latency, and memory growth indicate impending issues.
 */
public record RateOfChange(
    @JsonProperty("throughput_trend") double throughputTrend,
    @JsonProperty("latency_trend") double latencyTrend,
    @JsonProperty("heap_growth_bytes_per_sec") long heapGrowthBytesPerSec,
    @JsonProperty("old_gen_growth_bytes_per_sec") long oldGenGrowthBytesPerSec,
    @JsonProperty("queue_depth_trend") double queueDepthTrend,
    @JsonProperty("error_rate_trend") double errorRateTrend,
    @JsonProperty("gc_frequency_trend") double gcFrequencyTrend,
    @JsonProperty("estimated_heap_exhaustion_sec") long estimatedHeapExhaustionSec,
    @JsonProperty("estimated_old_gen_exhaustion_sec") long estimatedOldGenExhaustionSec
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double throughputTrend;
        private double latencyTrend;
        private long heapGrowthBytesPerSec;
        private long oldGenGrowthBytesPerSec;
        private double queueDepthTrend;
        private double errorRateTrend;
        private double gcFrequencyTrend;
        private long estimatedHeapExhaustionSec = -1;
        private long estimatedOldGenExhaustionSec = -1;

        public Builder throughputTrend(double v) { this.throughputTrend = v; return this; }
        public Builder latencyTrend(double v) { this.latencyTrend = v; return this; }
        public Builder heapGrowthBytesPerSec(long v) { this.heapGrowthBytesPerSec = v; return this; }
        public Builder oldGenGrowthBytesPerSec(long v) { this.oldGenGrowthBytesPerSec = v; return this; }
        public Builder queueDepthTrend(double v) { this.queueDepthTrend = v; return this; }
        public Builder errorRateTrend(double v) { this.errorRateTrend = v; return this; }
        public Builder gcFrequencyTrend(double v) { this.gcFrequencyTrend = v; return this; }
        public Builder estimatedHeapExhaustionSec(long v) { this.estimatedHeapExhaustionSec = v; return this; }
        public Builder estimatedOldGenExhaustionSec(long v) { this.estimatedOldGenExhaustionSec = v; return this; }

        public RateOfChange build() {
            return new RateOfChange(
                throughputTrend, latencyTrend, heapGrowthBytesPerSec,
                oldGenGrowthBytesPerSec, queueDepthTrend, errorRateTrend,
                gcFrequencyTrend, estimatedHeapExhaustionSec, estimatedOldGenExhaustionSec
            );
        }
    }
}
