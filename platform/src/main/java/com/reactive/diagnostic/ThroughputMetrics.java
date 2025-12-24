package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Throughput metrics - events processed per unit time.
 * This is one of the two core metrics (along with latency).
 */
public record ThroughputMetrics(
    @JsonProperty("events_per_second") double eventsPerSecond,
    @JsonProperty("bytes_per_second") long bytesPerSecond,
    @JsonProperty("events_per_second_capacity") double eventsPerSecondCapacity,
    @JsonProperty("capacity_utilization_percent") double capacityUtilizationPercent,
    @JsonProperty("batch_size_avg") double batchSizeAvg,
    @JsonProperty("batch_size_p99") long batchSizeP99,
    @JsonProperty("batches_per_second") double batchesPerSecond,
    @JsonProperty("batch_efficiency_percent") double batchEfficiencyPercent,
    @JsonProperty("parallelism_active") int parallelismActive,
    @JsonProperty("parallelism_max") int parallelismMax,
    @JsonProperty("queue_depth") long queueDepth,
    @JsonProperty("queue_capacity") long queueCapacity,
    @JsonProperty("rejected_count") long rejectedCount,
    @JsonProperty("dropped_count") long droppedCount
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double eventsPerSecond;
        private long bytesPerSecond;
        private double eventsPerSecondCapacity;
        private double capacityUtilizationPercent;
        private double batchSizeAvg;
        private long batchSizeP99;
        private double batchesPerSecond;
        private double batchEfficiencyPercent;
        private int parallelismActive;
        private int parallelismMax;
        private long queueDepth;
        private long queueCapacity;
        private long rejectedCount;
        private long droppedCount;

        public Builder eventsPerSecond(double v) { this.eventsPerSecond = v; return this; }
        public Builder bytesPerSecond(long v) { this.bytesPerSecond = v; return this; }
        public Builder eventsPerSecondCapacity(double v) { this.eventsPerSecondCapacity = v; return this; }
        public Builder capacityUtilizationPercent(double v) { this.capacityUtilizationPercent = v; return this; }
        public Builder batchSizeAvg(double v) { this.batchSizeAvg = v; return this; }
        public Builder batchSizeP99(long v) { this.batchSizeP99 = v; return this; }
        public Builder batchesPerSecond(double v) { this.batchesPerSecond = v; return this; }
        public Builder batchEfficiencyPercent(double v) { this.batchEfficiencyPercent = v; return this; }
        public Builder parallelismActive(int v) { this.parallelismActive = v; return this; }
        public Builder parallelismMax(int v) { this.parallelismMax = v; return this; }
        public Builder queueDepth(long v) { this.queueDepth = v; return this; }
        public Builder queueCapacity(long v) { this.queueCapacity = v; return this; }
        public Builder rejectedCount(long v) { this.rejectedCount = v; return this; }
        public Builder droppedCount(long v) { this.droppedCount = v; return this; }

        public ThroughputMetrics build() {
            return new ThroughputMetrics(
                eventsPerSecond, bytesPerSecond, eventsPerSecondCapacity,
                capacityUtilizationPercent, batchSizeAvg, batchSizeP99,
                batchesPerSecond, batchEfficiencyPercent, parallelismActive,
                parallelismMax, queueDepth, queueCapacity, rejectedCount, droppedCount
            );
        }
    }
}
