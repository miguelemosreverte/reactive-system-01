package com.reactive.flink.model;

import java.io.Serializable;

/**
 * Intermediate result before Drools enrichment.
 * Used to decouple counter state processing from async Drools call.
 */
public class PreDroolsResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private int counterValue;
    private String traceId;
    private String spanId;
    private long arrivalTime;

    public PreDroolsResult() {}

    public PreDroolsResult(String sessionId, int counterValue, String traceId, String spanId, long arrivalTime) {
        this.sessionId = sessionId;
        this.counterValue = counterValue;
        this.traceId = traceId;
        this.spanId = spanId;
        this.arrivalTime = arrivalTime;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getCounterValue() { return counterValue; }
    public void setCounterValue(int counterValue) { this.counterValue = counterValue; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public long getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(long arrivalTime) { this.arrivalTime = arrivalTime; }
}
