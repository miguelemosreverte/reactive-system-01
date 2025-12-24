package com.reactive.diagnostic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Individual error details.
 */
public record ErrorInfo(
    @JsonProperty("type") String type,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp_ms") long timestampMs,
    @JsonProperty("stack_trace_hash") String stackTraceHash
) {
    public static ErrorInfo of(String type, String message, long timestampMs, String stackTraceHash) {
        return new ErrorInfo(type, message, timestampMs, stackTraceHash);
    }
}
