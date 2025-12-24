package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-stage processing metrics - identifies bottlenecks in the pipeline.
 */
public record PipelineStage(
    @JsonProperty("name") String name,
    @JsonProperty("events_processed") long eventsProcessed,
    @JsonProperty("events_per_second") double eventsPerSecond,
    @JsonProperty("latency_ms_p50") double latencyMsP50,
    @JsonProperty("latency_ms_p99") double latencyMsP99,
    @JsonProperty("queue_depth") long queueDepth,
    @JsonProperty("is_bottleneck") boolean isBottleneck,
    @JsonProperty("saturation_percent") double saturationPercent,
    @JsonProperty("error_count") long errorCount,
    @JsonProperty("backpressure_applied") boolean backpressureApplied
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private long eventsProcessed;
        private double eventsPerSecond;
        private double latencyMsP50;
        private double latencyMsP99;
        private long queueDepth;
        private boolean isBottleneck;
        private double saturationPercent;
        private long errorCount;
        private boolean backpressureApplied;

        public Builder name(String v) { this.name = v; return this; }
        public Builder eventsProcessed(long v) { this.eventsProcessed = v; return this; }
        public Builder eventsPerSecond(double v) { this.eventsPerSecond = v; return this; }
        public Builder latencyMsP50(double v) { this.latencyMsP50 = v; return this; }
        public Builder latencyMsP99(double v) { this.latencyMsP99 = v; return this; }
        public Builder queueDepth(long v) { this.queueDepth = v; return this; }
        public Builder isBottleneck(boolean v) { this.isBottleneck = v; return this; }
        public Builder saturationPercent(double v) { this.saturationPercent = v; return this; }
        public Builder errorCount(long v) { this.errorCount = v; return this; }
        public Builder backpressureApplied(boolean v) { this.backpressureApplied = v; return this; }

        public PipelineStage build() {
            return new PipelineStage(
                name, eventsProcessed, eventsPerSecond, latencyMsP50,
                latencyMsP99, queueDepth, isBottleneck, saturationPercent,
                errorCount, backpressureApplied
            );
        }
    }
}
