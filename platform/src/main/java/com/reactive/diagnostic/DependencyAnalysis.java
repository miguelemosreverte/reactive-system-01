package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * External dependency health - explains dependency-related latency.
 */
public record DependencyAnalysis(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("latency_ms_p50") double latencyMsP50,
    @JsonProperty("latency_ms_p99") double latencyMsP99,
    @JsonProperty("calls_per_second") double callsPerSecond,
    @JsonProperty("error_rate_percent") double errorRatePercent,
    @JsonProperty("circuit_breaker_state") String circuitBreakerState,
    @JsonProperty("connection_pool_used") int connectionPoolUsed,
    @JsonProperty("connection_pool_max") int connectionPoolMax,
    @JsonProperty("saturation_percent") double saturationPercent,
    @JsonProperty("last_error") String lastError,
    @JsonProperty("last_error_time_ms") long lastErrorTimeMs
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String type;
        private double latencyMsP50;
        private double latencyMsP99;
        private double callsPerSecond;
        private double errorRatePercent;
        private String circuitBreakerState = "CLOSED";
        private int connectionPoolUsed;
        private int connectionPoolMax;
        private double saturationPercent;
        private String lastError;
        private long lastErrorTimeMs;

        public Builder name(String v) { this.name = v; return this; }
        public Builder type(String v) { this.type = v; return this; }
        public Builder latencyMsP50(double v) { this.latencyMsP50 = v; return this; }
        public Builder latencyMsP99(double v) { this.latencyMsP99 = v; return this; }
        public Builder callsPerSecond(double v) { this.callsPerSecond = v; return this; }
        public Builder errorRatePercent(double v) { this.errorRatePercent = v; return this; }
        public Builder circuitBreakerState(String v) { this.circuitBreakerState = v; return this; }
        public Builder connectionPoolUsed(int v) { this.connectionPoolUsed = v; return this; }
        public Builder connectionPoolMax(int v) { this.connectionPoolMax = v; return this; }
        public Builder saturationPercent(double v) { this.saturationPercent = v; return this; }
        public Builder lastError(String v) { this.lastError = v; return this; }
        public Builder lastErrorTimeMs(long v) { this.lastErrorTimeMs = v; return this; }

        public DependencyAnalysis build() {
            return new DependencyAnalysis(
                name, type, latencyMsP50, latencyMsP99, callsPerSecond,
                errorRatePercent, circuitBreakerState, connectionPoolUsed,
                connectionPoolMax, saturationPercent, lastError, lastErrorTimeMs
            );
        }
    }
}
