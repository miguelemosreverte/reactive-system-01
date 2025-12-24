package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Latency metrics - time breakdown for processing.
 * This is one of the two core metrics (along with throughput).
 */
public record LatencyMetrics(
    @JsonProperty("total_ms_p50") double totalMsP50,
    @JsonProperty("total_ms_p95") double totalMsP95,
    @JsonProperty("total_ms_p99") double totalMsP99,
    @JsonProperty("total_ms_max") double totalMsMax,
    @JsonProperty("cpu_time_ms") double cpuTimeMs,
    @JsonProperty("queue_wait_ms") double queueWaitMs,
    @JsonProperty("lock_wait_ms") double lockWaitMs,
    @JsonProperty("gc_pause_ms") double gcPauseMs,
    @JsonProperty("network_ms") double networkMs,
    @JsonProperty("serialization_ms") double serializationMs,
    @JsonProperty("deserialization_ms") double deserializationMs,
    @JsonProperty("dependency_wait_ms") double dependencyWaitMs,
    @JsonProperty("breakdown_percent") Map<String, Double> breakdownPercent
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double totalMsP50;
        private double totalMsP95;
        private double totalMsP99;
        private double totalMsMax;
        private double cpuTimeMs;
        private double queueWaitMs;
        private double lockWaitMs;
        private double gcPauseMs;
        private double networkMs;
        private double serializationMs;
        private double deserializationMs;
        private double dependencyWaitMs;
        private Map<String, Double> breakdownPercent = Map.of();

        public Builder totalMsP50(double v) { this.totalMsP50 = v; return this; }
        public Builder totalMsP95(double v) { this.totalMsP95 = v; return this; }
        public Builder totalMsP99(double v) { this.totalMsP99 = v; return this; }
        public Builder totalMsMax(double v) { this.totalMsMax = v; return this; }
        public Builder cpuTimeMs(double v) { this.cpuTimeMs = v; return this; }
        public Builder queueWaitMs(double v) { this.queueWaitMs = v; return this; }
        public Builder lockWaitMs(double v) { this.lockWaitMs = v; return this; }
        public Builder gcPauseMs(double v) { this.gcPauseMs = v; return this; }
        public Builder networkMs(double v) { this.networkMs = v; return this; }
        public Builder serializationMs(double v) { this.serializationMs = v; return this; }
        public Builder deserializationMs(double v) { this.deserializationMs = v; return this; }
        public Builder dependencyWaitMs(double v) { this.dependencyWaitMs = v; return this; }
        public Builder breakdownPercent(Map<String, Double> v) { this.breakdownPercent = v; return this; }

        public LatencyMetrics build() {
            return new LatencyMetrics(
                totalMsP50, totalMsP95, totalMsP99, totalMsMax,
                cpuTimeMs, queueWaitMs, lockWaitMs, gcPauseMs,
                networkMs, serializationMs, deserializationMs,
                dependencyWaitMs, breakdownPercent
            );
        }
    }
}
