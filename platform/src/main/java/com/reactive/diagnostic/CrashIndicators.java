package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Signs of imminent failure - OOM, GC thrashing, memory leaks, thread exhaustion.
 */
public record CrashIndicators(
    @JsonProperty("oom_risk") boolean oomRisk,
    @JsonProperty("oom_risk_percent") double oomRiskPercent,
    @JsonProperty("gc_thrashing") boolean gcThrashing,
    @JsonProperty("memory_leak_suspected") boolean memoryLeakSuspected,
    @JsonProperty("thread_exhaustion_risk") boolean threadExhaustionRisk
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean oomRisk;
        private double oomRiskPercent;
        private boolean gcThrashing;
        private boolean memoryLeakSuspected;
        private boolean threadExhaustionRisk;

        public Builder oomRisk(boolean v) { this.oomRisk = v; return this; }
        public Builder oomRiskPercent(double v) { this.oomRiskPercent = v; return this; }
        public Builder gcThrashing(boolean v) { this.gcThrashing = v; return this; }
        public Builder memoryLeakSuspected(boolean v) { this.memoryLeakSuspected = v; return this; }
        public Builder threadExhaustionRisk(boolean v) { this.threadExhaustionRisk = v; return this; }

        public CrashIndicators build() {
            return new CrashIndicators(
                oomRisk, oomRiskPercent, gcThrashing,
                memoryLeakSuspected, threadExhaustionRisk
            );
        }
    }
}
