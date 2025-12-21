package com.reactive.counter.domain;

/**
 * Counter domain event.
 * Immutable record representing a counter action.
 */
public record CounterEvent(
        String requestId,
        String customerId,
        String eventId,
        String sessionId,
        String action,
        int value,
        long timestamp,
        Timing timing
) {
    public record Timing(Long gatewayReceivedAt, Long gatewayPublishedAt) {}

    public static CounterEvent create(
            String requestId,
            String customerId,
            String eventId,
            String sessionId,
            String action,
            int value
    ) {
        long now = System.currentTimeMillis();
        return new CounterEvent(requestId, customerId, eventId, sessionId, action, value, now, new Timing(now, null));
    }
}
