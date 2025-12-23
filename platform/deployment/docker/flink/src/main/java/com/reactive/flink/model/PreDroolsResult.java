package com.reactive.flink.model;

import com.reactive.platform.observe.TracedMessage;

import static com.reactive.platform.Opt.or;

/**
 * Intermediate result before Drools enrichment.
 * Used to decouple counter state processing from async Drools call.
 *
 * Business IDs:
 * - requestId: Correlation ID for this request
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 * - sessionId: Counter instance ID
 *
 * Immutable record with compact constructor for normalization.
 * Implements TracedMessage for automatic span attribute extraction
 * and trace context propagation.
 */
public record PreDroolsResult(
        String sessionId,
        int counterValue,
        String requestId,
        String customerId,
        String eventId,
        EventTiming timing,
        long arrivalTime,
        String traceparent,
        String tracestate
) implements TracedMessage {
    /**
     * Compact constructor normalizes all fields at construction.
     */
    public PreDroolsResult {
        sessionId = or(sessionId, "");
        requestId = or(requestId, "");
        customerId = or(customerId, "");
        eventId = or(eventId, "");
        timing = EventTiming.from(timing);
        traceparent = or(traceparent, "");
        tracestate = or(tracestate, "");
    }

    @Override
    public String toString() {
        return "PreDroolsResult{" +
                "requestId='" + requestId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", counterValue=" + counterValue +
                ", eventId='" + eventId + '\'' +
                '}';
    }
}
