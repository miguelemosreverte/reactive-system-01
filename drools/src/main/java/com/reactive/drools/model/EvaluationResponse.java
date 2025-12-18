package com.reactive.drools.model;

public class EvaluationResponse {
    private int value;
    private String alert;
    private String message;
    private long timestamp;

    public EvaluationResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    public EvaluationResponse(int value, String alert) {
        this.value = value;
        this.alert = alert;
        this.timestamp = System.currentTimeMillis();
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
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
}
