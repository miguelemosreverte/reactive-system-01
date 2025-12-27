package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Garbage collection behavior - explains GC-related latency and OOM risk.
 */
public record GCAnalysis(
    @JsonProperty("young_gc_count") long youngGcCount,
    @JsonProperty("young_gc_time_ms") long youngGcTimeMs,
    @JsonProperty("old_gc_count") long oldGcCount,
    @JsonProperty("old_gc_time_ms") long oldGcTimeMs,
    @JsonProperty("gc_overhead_percent") double gcOverheadPercent,
    @JsonProperty("avg_young_gc_pause_ms") double avgYoungGcPauseMs,
    @JsonProperty("avg_old_gc_pause_ms") double avgOldGcPauseMs,
    @JsonProperty("max_gc_pause_ms") double maxGcPauseMs,
    @JsonProperty("gc_frequency_per_min") double gcFrequencyPerMin,
    @JsonProperty("time_since_last_gc_ms") long timeSinceLastGcMs,
    @JsonProperty("consecutive_full_gcs") int consecutiveFullGcs,
    @JsonProperty("gc_cpu_percent") double gcCpuPercent,
    @JsonProperty("objects_promoted_per_gc") long objectsPromotedPerGc,
    @JsonProperty("gc_algorithm") String gcAlgorithm,
    @JsonProperty("gc_cause_histogram") Map<String, Long> gcCauseHistogram
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long youngGcCount;
        private long youngGcTimeMs;
        private long oldGcCount;
        private long oldGcTimeMs;
        private double gcOverheadPercent;
        private double avgYoungGcPauseMs;
        private double avgOldGcPauseMs;
        private double maxGcPauseMs;
        private double gcFrequencyPerMin;
        private long timeSinceLastGcMs;
        private int consecutiveFullGcs;
        private double gcCpuPercent;
        private long objectsPromotedPerGc;
        private String gcAlgorithm = "G1";
        private Map<String, Long> gcCauseHistogram = Map.of();

        public Builder youngGcCount(long v) { this.youngGcCount = v; return this; }
        public Builder youngGcTimeMs(long v) { this.youngGcTimeMs = v; return this; }
        public Builder oldGcCount(long v) { this.oldGcCount = v; return this; }
        public Builder oldGcTimeMs(long v) { this.oldGcTimeMs = v; return this; }
        public Builder gcOverheadPercent(double v) { this.gcOverheadPercent = v; return this; }
        public Builder avgYoungGcPauseMs(double v) { this.avgYoungGcPauseMs = v; return this; }
        public Builder avgOldGcPauseMs(double v) { this.avgOldGcPauseMs = v; return this; }
        public Builder maxGcPauseMs(double v) { this.maxGcPauseMs = v; return this; }
        public Builder gcFrequencyPerMin(double v) { this.gcFrequencyPerMin = v; return this; }
        public Builder timeSinceLastGcMs(long v) { this.timeSinceLastGcMs = v; return this; }
        public Builder consecutiveFullGcs(int v) { this.consecutiveFullGcs = v; return this; }
        public Builder gcCpuPercent(double v) { this.gcCpuPercent = v; return this; }
        public Builder objectsPromotedPerGc(long v) { this.objectsPromotedPerGc = v; return this; }
        public Builder gcAlgorithm(String v) { this.gcAlgorithm = v; return this; }
        public Builder gcCauseHistogram(Map<String, Long> v) { this.gcCauseHistogram = v; return this; }

        public GCAnalysis build() {
            return new GCAnalysis(
                youngGcCount, youngGcTimeMs, oldGcCount, oldGcTimeMs,
                gcOverheadPercent, avgYoungGcPauseMs, avgOldGcPauseMs,
                maxGcPauseMs, gcFrequencyPerMin, timeSinceLastGcMs,
                consecutiveFullGcs, gcCpuPercent, objectsPromotedPerGc,
                gcAlgorithm, gcCauseHistogram
            );
        }
    }
}
