package com.reactive.flink.model;

import java.io.Serializable;

public class CounterResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private int currentValue;
    private String alert;
    private String message;
    private long timestamp;
    private String traceId;

    public CounterResult() {
    }

    public CounterResult(String sessionId, int currentValue, String alert, String message) {
        this.sessionId = sessionId;
        this.currentValue = currentValue;
        this.alert = alert;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public CounterResult(String sessionId, int currentValue, String alert, String message, String traceId) {
        this(sessionId, currentValue, alert, message);
        this.traceId = traceId;
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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    @Override
    public String toString() {
        return "CounterResult{sessionId='" + sessionId + "', value=" + currentValue + ", alert='" + alert + "', traceId='" + traceId + "'}";
    }
}
