package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about a contended lock.
 */
public record LockInfo(
    @JsonProperty("name") String name,
    @JsonProperty("contention_percent") double contentionPercent,
    @JsonProperty("wait_count") long waitCount,
    @JsonProperty("avg_wait_ms") double avgWaitMs
) {
    public static LockInfo of(String name, double contentionPercent, long waitCount, double avgWaitMs) {
        return new LockInfo(name, contentionPercent, waitCount, avgWaitMs);
    }
}
