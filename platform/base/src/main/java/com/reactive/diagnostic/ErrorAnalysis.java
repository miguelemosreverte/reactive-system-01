package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Error patterns and crash risk indicators.
 */
public record ErrorAnalysis(
    @JsonProperty("total_error_count") long totalErrorCount,
    @JsonProperty("error_rate_percent") double errorRatePercent,
    @JsonProperty("errors_by_type") Map<String, Long> errorsByType,
    @JsonProperty("recent_errors") List<ErrorInfo> recentErrors,
    @JsonProperty("crash_indicators") CrashIndicators crashIndicators
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long totalErrorCount;
        private double errorRatePercent;
        private Map<String, Long> errorsByType = Map.of();
        private List<ErrorInfo> recentErrors = List.of();
        private CrashIndicators crashIndicators;

        public Builder totalErrorCount(long v) { this.totalErrorCount = v; return this; }
        public Builder errorRatePercent(double v) { this.errorRatePercent = v; return this; }
        public Builder errorsByType(Map<String, Long> v) { this.errorsByType = v; return this; }
        public Builder recentErrors(List<ErrorInfo> v) { this.recentErrors = v; return this; }
        public Builder crashIndicators(CrashIndicators v) { this.crashIndicators = v; return this; }

        public ErrorAnalysis build() {
            return new ErrorAnalysis(
                totalErrorCount, errorRatePercent, errorsByType,
                recentErrors, crashIndicators
            );
        }
    }
}
