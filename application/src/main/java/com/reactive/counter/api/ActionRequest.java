package com.reactive.counter.api;

/**
 * Counter action request.
 */
public record ActionRequest(
        String sessionId,
        String action,
        int value
) {
    public String sessionIdOrDefault() {
        return sessionId != null ? sessionId : "default";
    }
}
