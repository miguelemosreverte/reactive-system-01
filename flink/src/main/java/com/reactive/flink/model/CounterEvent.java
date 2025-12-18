package com.reactive.flink.model;

import java.io.Serializable;

public class CounterEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private String action;  // "increment", "decrement", "set"
    private int value;
    private long timestamp;

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

    @Override
    public String toString() {
        return "CounterEvent{sessionId='" + sessionId + "', action='" + action + "', value=" + value + "}";
    }
}
