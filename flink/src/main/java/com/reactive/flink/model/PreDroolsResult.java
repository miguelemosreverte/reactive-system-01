package com.reactive.flink.model;

import java.io.Serializable;

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
 * Note: OpenTelemetry trace propagation is handled automatically.
 * We only propagate business IDs here.
 */
public class PreDroolsResult implements Serializable {
    private static final long serialVersionUID = 3L;

    private String requestId;      // Correlation ID for this request
    private String customerId;     // Customer/tenant ID for multi-tenancy
    private String eventId;        // Unique event ID
    private String sessionId;      // Counter instance ID
    private int counterValue;
    private EventTiming timing;
    private long arrivalTime;

    public PreDroolsResult() {}

    public PreDroolsResult(String sessionId, int counterValue, String requestId, String customerId,
                           String eventId, EventTiming timing, long arrivalTime) {
        this.sessionId = sessionId;
        this.counterValue = counterValue;
        this.requestId = requestId;
        this.customerId = customerId;
        this.eventId = eventId;
        this.timing = timing;
        this.arrivalTime = arrivalTime;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getCounterValue() {
        return counterValue;
    }

    public void setCounterValue(int counterValue) {
        this.counterValue = counterValue;
    }

    public EventTiming getTiming() {
        return timing;
    }

    public void setTiming(EventTiming timing) {
        this.timing = timing;
    }

    public long getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(long arrivalTime) {
        this.arrivalTime = arrivalTime;
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
