package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Individual memory pool status (Eden, Survivor, OldGen, Metaspace).
 */
public record MemoryPool(
    @JsonProperty("name") String name,
    @JsonProperty("used_bytes") long usedBytes,
    @JsonProperty("max_bytes") long maxBytes,
    @JsonProperty("used_percent") double usedPercent
) {
    public static MemoryPool of(String name, long usedBytes, long maxBytes, double usedPercent) {
        return new MemoryPool(name, usedBytes, maxBytes, usedPercent);
    }
}
