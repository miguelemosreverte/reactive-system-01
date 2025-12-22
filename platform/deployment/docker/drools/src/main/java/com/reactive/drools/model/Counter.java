package com.reactive.drools.model;

public class Counter {
    private int value;
    private String alert;
    private String message;

    public Counter() {
    }

    public Counter(int value) {
        this.value = value;
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

    @Override
    public String toString() {
        return "Counter{value=" + value + ", alert='" + alert + "', message='" + message + "'}";
    }
}
