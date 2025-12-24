package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Capacity analysis - raw data about headroom and limiting factors.
 * Pure metrics without recommendations (AI interprets this data).
 */
public record CapacityAnalysis(
    @JsonProperty("current_throughput") double currentThroughput,
    @JsonProperty("max_observed_throughput") double maxObservedThroughput,
    @JsonProperty("theoretical_max_throughput") double theoreticalMaxThroughput,
    @JsonProperty("headroom_percent") double headroomPercent,
    @JsonProperty("limiting_factors") List<LimitingFactor> limitingFactors
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double currentThroughput;
        private double maxObservedThroughput;
        private double theoreticalMaxThroughput;
        private double headroomPercent;
        private List<LimitingFactor> limitingFactors = List.of();

        public Builder currentThroughput(double v) { this.currentThroughput = v; return this; }
        public Builder maxObservedThroughput(double v) { this.maxObservedThroughput = v; return this; }
        public Builder theoreticalMaxThroughput(double v) { this.theoreticalMaxThroughput = v; return this; }
        public Builder headroomPercent(double v) { this.headroomPercent = v; return this; }
        public Builder limitingFactors(List<LimitingFactor> v) { this.limitingFactors = v; return this; }

        public CapacityAnalysis build() {
            return new CapacityAnalysis(
                currentThroughput, maxObservedThroughput, theoreticalMaxThroughput,
                headroomPercent, limitingFactors
            );
        }
    }
}
