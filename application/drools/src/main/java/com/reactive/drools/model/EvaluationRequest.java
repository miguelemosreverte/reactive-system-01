package com.reactive.drools.model;

/**
 * Evaluation request from Flink.
 *
 * Business IDs:
 * - requestId: Correlation ID for request tracking
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 * - sessionId: Counter instance ID
 */
public class EvaluationRequest {
    private int value;
    private String requestId;
    private String customerId;
    private String eventId;
    private String sessionId;

    public EvaluationRequest() {
    }

    public EvaluationRequest(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
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
}
