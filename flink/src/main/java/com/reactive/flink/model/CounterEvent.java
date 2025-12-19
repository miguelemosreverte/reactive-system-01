package com.reactive.flink.model;

import java.io.Serializable;

public class CounterEvent implements Serializable {
    private static final long serialVersionUID = 2L;

    private String sessionId;
    private String action;  // "increment", "decrement", "set"
    private int value;
    private long timestamp;
    private String traceId;
    private String spanId;
    private String eventId;
    private EventTiming timing;
    private long deserializedAt;

    public CounterEvent() {
    }

    public CounterEvent(String sessionId, String action, int value) {
        this.sessionId = sessionId;
        this.action = action;
        this.value = value;
        this.timestamp = System.currentTimeMillis();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public EventTiming getTiming() {
        return timing;
    }

    public void setTiming(EventTiming timing) {
        this.timing = timing;
    }

    public long getDeserializedAt() {
        return deserializedAt;
    }

    public void setDeserializedAt(long deserializedAt) {
        this.deserializedAt = deserializedAt;
    }

    @Override
    public String toString() {
        return "CounterEvent{sessionId='" + sessionId + "', action='" + action + "', value=" + value + ", traceId='" + traceId + "', eventId='" + eventId + "'}";
    }
}
