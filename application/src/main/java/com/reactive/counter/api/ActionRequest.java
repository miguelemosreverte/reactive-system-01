package com.reactive.counter.api;

/**
 * Counter action request.
 *
 * Compact constructor normalizes null values from JSON deserialization.
 */
public record ActionRequest(
        String sessionId,
        String action,
        int value
) {
    public ActionRequest {
        // Normalize nulls from JSON deserialization
        sessionId = sessionId != null ? sessionId : "";
        action = action != null ? action : "increment";
    }

    public String sessionIdOrDefault() {
        return sessionId.isEmpty() ? "default" : sessionId;
    }
}
