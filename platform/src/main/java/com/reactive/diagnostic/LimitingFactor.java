package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Factor limiting throughput capacity.
 * Pure metrics - AI interprets this data to provide recommendations.
 */
public record LimitingFactor(
    @JsonProperty("factor") String factor,
    @JsonProperty("impact_percent") double impactPercent,
    @JsonProperty("current_value") double currentValue,
    @JsonProperty("threshold_value") double thresholdValue,
    @JsonProperty("unit") String unit
) {
    public static LimitingFactor of(String factor, double impactPercent, double currentValue, double thresholdValue, String unit) {
        return new LimitingFactor(factor, impactPercent, currentValue, thresholdValue, unit);
    }
}
