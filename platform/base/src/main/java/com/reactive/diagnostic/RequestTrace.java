package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Sampled request with detailed timing breakdown.
 */
public record RequestTrace(
    @JsonProperty("trace_id") String traceId,
    @JsonProperty("total_time_ms") double totalTimeMs,
    @JsonProperty("time_breakdown") Map<String, Double> timeBreakdown,
    @JsonProperty("gc_pauses_encountered") int gcPausesEncountered,
    @JsonProperty("retries") int retries,
    @JsonProperty("final_status") String finalStatus
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String traceId;
        private double totalTimeMs;
        private Map<String, Double> timeBreakdown = Map.of();
        private int gcPausesEncountered;
        private int retries;
        private String finalStatus = "SUCCESS";

        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder totalTimeMs(double v) { this.totalTimeMs = v; return this; }
        public Builder timeBreakdown(Map<String, Double> v) { this.timeBreakdown = v; return this; }
        public Builder gcPausesEncountered(int v) { this.gcPausesEncountered = v; return this; }
        public Builder retries(int v) { this.retries = v; return this; }
        public Builder finalStatus(String v) { this.finalStatus = v; return this; }

        public RequestTrace build() {
            return new RequestTrace(
                traceId, totalTimeMs, timeBreakdown,
                gcPausesEncountered, retries, finalStatus
            );
        }
    }
}
