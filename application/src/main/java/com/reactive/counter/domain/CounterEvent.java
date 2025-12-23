package com.reactive.counter.domain;

/**
 * Counter domain event.
 * Immutable record representing a counter action.
 *
 * Compact constructor normalizes null values to ensure null-free internals.
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
    public CounterEvent {
        // Normalize optional string fields
        customerId = customerId != null ? customerId : "";
        action = action != null ? action : "increment";
    }

    /**
     * Timing information. Uses 0L for unpopulated timestamps (not null).
     */
    public record Timing(long gatewayReceivedAt, long gatewayPublishedAt) {
        public static Timing atGatewayReceived(long timestamp) {
            return new Timing(timestamp, 0L);
        }

        public static Timing complete(long receivedAt, long publishedAt) {
            return new Timing(receivedAt, publishedAt);
        }
    }

    public static CounterEvent create(
            String requestId,
            String customerId,
            String eventId,
            String sessionId,
            String action,
            int value
    ) {
        long now = System.currentTimeMillis();
        return new CounterEvent(requestId, customerId, eventId, sessionId, action, value, now, Timing.atGatewayReceived(now));
    }
}
