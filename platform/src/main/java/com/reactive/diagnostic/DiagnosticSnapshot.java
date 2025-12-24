package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Complete diagnostic snapshot of a component's health and performance.
 * This is the core data structure for the unified diagnostic system.
 *
 * Everything derives from throughput and latency - all other metrics
 * explain WHY these two core metrics are what they are.
 */
public record DiagnosticSnapshot(
    // Identity & Timing
    @JsonProperty("component") String component,
    @JsonProperty("instance_id") String instanceId,
    @JsonProperty("timestamp_ms") long timestampMs,
    @JsonProperty("uptime_ms") long uptimeMs,
    @JsonProperty("version") String version,

    // THE TWO CORE METRICS
    @JsonProperty("throughput") ThroughputMetrics throughput,
    @JsonProperty("latency") LatencyMetrics latency,

    // EVERYTHING ELSE EXPLAINS WHY THROUGHPUT/LATENCY ARE WHAT THEY ARE
    @JsonProperty("saturation") ResourceSaturation saturation,
    @JsonProperty("trends") RateOfChange trends,
    @JsonProperty("contention") ContentionAnalysis contention,
    @JsonProperty("memory") MemoryPressure memory,
    @JsonProperty("gc") GCAnalysis gc,
    @JsonProperty("stages") List<PipelineStage> stages,
    @JsonProperty("dependencies") List<DependencyAnalysis> dependencies,
    @JsonProperty("errors") ErrorAnalysis errors,
    @JsonProperty("capacity") CapacityAnalysis capacity,
    @JsonProperty("history") List<HistoricalBucket> history,
    @JsonProperty("sampled_requests") List<RequestTrace> sampledRequests
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String component;
        private String instanceId;
        private long timestampMs;
        private long uptimeMs;
        private String version;
        private ThroughputMetrics throughput;
        private LatencyMetrics latency;
        private ResourceSaturation saturation;
        private RateOfChange trends;
        private ContentionAnalysis contention;
        private MemoryPressure memory;
        private GCAnalysis gc;
        private List<PipelineStage> stages = List.of();
        private List<DependencyAnalysis> dependencies = List.of();
        private ErrorAnalysis errors;
        private CapacityAnalysis capacity;
        private List<HistoricalBucket> history = List.of();
        private List<RequestTrace> sampledRequests = List.of();

        public Builder component(String component) { this.component = component; return this; }
        public Builder instanceId(String instanceId) { this.instanceId = instanceId; return this; }
        public Builder timestampMs(long timestampMs) { this.timestampMs = timestampMs; return this; }
        public Builder uptimeMs(long uptimeMs) { this.uptimeMs = uptimeMs; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder throughput(ThroughputMetrics throughput) { this.throughput = throughput; return this; }
        public Builder latency(LatencyMetrics latency) { this.latency = latency; return this; }
        public Builder saturation(ResourceSaturation saturation) { this.saturation = saturation; return this; }
        public Builder trends(RateOfChange trends) { this.trends = trends; return this; }
        public Builder contention(ContentionAnalysis contention) { this.contention = contention; return this; }
        public Builder memory(MemoryPressure memory) { this.memory = memory; return this; }
        public Builder gc(GCAnalysis gc) { this.gc = gc; return this; }
        public Builder stages(List<PipelineStage> stages) { this.stages = stages; return this; }
        public Builder dependencies(List<DependencyAnalysis> dependencies) { this.dependencies = dependencies; return this; }
        public Builder errors(ErrorAnalysis errors) { this.errors = errors; return this; }
        public Builder capacity(CapacityAnalysis capacity) { this.capacity = capacity; return this; }
        public Builder history(List<HistoricalBucket> history) { this.history = history; return this; }
        public Builder sampledRequests(List<RequestTrace> sampledRequests) { this.sampledRequests = sampledRequests; return this; }

        public DiagnosticSnapshot build() {
            return new DiagnosticSnapshot(
                component, instanceId, timestampMs, uptimeMs, version,
                throughput, latency, saturation, trends, contention,
                memory, gc, stages, dependencies, errors, capacity,
                history, sampledRequests
            );
        }
    }
}
