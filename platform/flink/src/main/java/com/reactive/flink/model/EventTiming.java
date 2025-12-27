package com.reactive.flink.model;

/**
 * Tracks timing information as an event flows through the pipeline.
 * Used for per-component latency breakdown in benchmark reports.
 *
 * Immutable record with copy methods for creating modified instances.
 * Uses primitive longs (0 = not set) to avoid boxed null handling.
 */
public record EventTiming(
        long gatewayReceivedAt,
        long gatewayPublishedAt,
        long flinkReceivedAt,
        long flinkProcessedAt,
        long droolsStartAt,
        long droolsEndAt
) {
    private static final EventTiming EMPTY = new EventTiming(0, 0, 0, 0, 0, 0);

    public static EventTiming empty() {
        return EMPTY;
    }

    /**
     * Create from another timing, or empty if input is absent.
     * Use at boundaries where timing may come from external sources.
     */
    public static EventTiming from(EventTiming other) {
        return other != null ? other : EMPTY;
    }

    public EventTiming withGatewayReceivedAt(long t) {
        return new EventTiming(t, gatewayPublishedAt, flinkReceivedAt, flinkProcessedAt, droolsStartAt, droolsEndAt);
    }

    public EventTiming withGatewayPublishedAt(long t) {
        return new EventTiming(gatewayReceivedAt, t, flinkReceivedAt, flinkProcessedAt, droolsStartAt, droolsEndAt);
    }

    public EventTiming withFlinkReceivedAt(long t) {
        return new EventTiming(gatewayReceivedAt, gatewayPublishedAt, t, flinkProcessedAt, droolsStartAt, droolsEndAt);
    }

    public EventTiming withFlinkProcessedAt(long t) {
        return new EventTiming(gatewayReceivedAt, gatewayPublishedAt, flinkReceivedAt, t, droolsStartAt, droolsEndAt);
    }

    public EventTiming withDroolsStartAt(long t) {
        return new EventTiming(gatewayReceivedAt, gatewayPublishedAt, flinkReceivedAt, flinkProcessedAt, t, droolsEndAt);
    }

    public EventTiming withDroolsEndAt(long t) {
        return new EventTiming(gatewayReceivedAt, gatewayPublishedAt, flinkReceivedAt, flinkProcessedAt, droolsStartAt, t);
    }

    public EventTiming withDroolsTiming(long start, long end) {
        return new EventTiming(gatewayReceivedAt, gatewayPublishedAt, flinkReceivedAt, flinkProcessedAt, start, end);
    }
}
