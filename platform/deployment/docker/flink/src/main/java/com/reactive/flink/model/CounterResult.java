package com.reactive.flink.model;

import static com.reactive.platform.Opt.or;

/**
 * Counter result to be published to Kafka.
 *
 * Business IDs:
 * - requestId: Correlation ID for this request
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 * - sessionId: Counter instance ID
 *
 * Immutable record with compact constructor for normalization.
 */
public record CounterResult(
        String sessionId,
        int currentValue,
        String alert,
        String message,
        String requestId,
        String customerId,
        String eventId,
        EventTiming timing,
        long timestamp,
        String traceparent,
        String tracestate
) {
    /**
     * Compact constructor normalizes all fields at construction.
     */
    public CounterResult {
        sessionId = or(sessionId, "");
        alert = or(alert, "NONE");
        message = or(message, "");
        requestId = or(requestId, "");
        customerId = or(customerId, "");
        eventId = or(eventId, "");
        timing = EventTiming.from(timing);
        traceparent = or(traceparent, "");
        tracestate = or(tracestate, "");
    }

    /**
     * Create result with automatic timestamp.
     */
    public CounterResult(String sessionId, int currentValue, String alert, String message,
                         String requestId, String customerId, String eventId, EventTiming timing) {
        this(sessionId, currentValue, alert, message, requestId, customerId, eventId,
             timing, System.currentTimeMillis(), "", "");
    }

    public CounterResult withTraceContext(String traceparent, String tracestate) {
        return new CounterResult(sessionId, currentValue, alert, message, requestId,
                customerId, eventId, timing, timestamp, traceparent, tracestate);
    }

    @Override
    public String toString() {
        return "CounterResult{" +
                "requestId='" + requestId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", value=" + currentValue +
                ", alert='" + alert + '\'' +
                ", eventId='" + eventId + '\'' +
                '}';
    }
}
