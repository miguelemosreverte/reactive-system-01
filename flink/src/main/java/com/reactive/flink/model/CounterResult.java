package com.reactive.flink.model;

import java.io.Serializable;

/**
 * Counter result to be published to Kafka.
 *
 * Business IDs:
 * - requestId: Correlation ID for this request
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 * - sessionId: Counter instance ID
 */
public class CounterResult implements Serializable {
    private static final long serialVersionUID = 3L;

    private String requestId;      // Correlation ID for this request
    private String customerId;     // Customer/tenant ID for multi-tenancy
    private String eventId;        // Unique event ID
    private String sessionId;      // Counter instance ID
    private int currentValue;
    private String alert;
    private String message;
    private long timestamp;
    private EventTiming timing;

    public CounterResult() {
    }

    public CounterResult(String sessionId, int currentValue, String alert, String message) {
        this.sessionId = sessionId;
        this.currentValue = currentValue;
        this.alert = alert;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public CounterResult(String sessionId, int currentValue, String alert, String message,
                         String requestId, String customerId) {
        this(sessionId, currentValue, alert, message);
        this.requestId = requestId;
        this.customerId = customerId;
    }

    public CounterResult(String sessionId, int currentValue, String alert, String message,
                         String requestId, String customerId, String eventId, EventTiming timing) {
        this(sessionId, currentValue, alert, message, requestId, customerId);
        this.eventId = eventId;
        this.timing = timing;
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

    public int getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(int currentValue) {
        this.currentValue = currentValue;
    }

    public String getAlert() {
        return alert;
    }

    public void setAlert(String alert) {
        this.alert = alert;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public EventTiming getTiming() {
        return timing;
    }

    public void setTiming(EventTiming timing) {
        this.timing = timing;
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
