package com.reactive.flink.model;

import java.io.Serializable;

/**
 * Tracks timing information as an event flows through the pipeline
 * Used for per-component latency breakdown in benchmark reports
 */
public class EventTiming implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long gatewayReceivedAt;
    private Long gatewayPublishedAt;
    private Long flinkReceivedAt;
    private Long flinkProcessedAt;
    private Long droolsStartAt;
    private Long droolsEndAt;

    public EventTiming() {
    }

    public Long getGatewayReceivedAt() {
        return gatewayReceivedAt;
    }

    public void setGatewayReceivedAt(Long gatewayReceivedAt) {
        this.gatewayReceivedAt = gatewayReceivedAt;
    }

    public Long getGatewayPublishedAt() {
        return gatewayPublishedAt;
    }

    public void setGatewayPublishedAt(Long gatewayPublishedAt) {
        this.gatewayPublishedAt = gatewayPublishedAt;
    }

    public Long getFlinkReceivedAt() {
        return flinkReceivedAt;
    }

    public void setFlinkReceivedAt(Long flinkReceivedAt) {
        this.flinkReceivedAt = flinkReceivedAt;
    }

    public Long getFlinkProcessedAt() {
        return flinkProcessedAt;
    }

    public void setFlinkProcessedAt(Long flinkProcessedAt) {
        this.flinkProcessedAt = flinkProcessedAt;
    }

    public Long getDroolsStartAt() {
        return droolsStartAt;
    }

    public void setDroolsStartAt(Long droolsStartAt) {
        this.droolsStartAt = droolsStartAt;
    }

    public Long getDroolsEndAt() {
        return droolsEndAt;
    }

    public void setDroolsEndAt(Long droolsEndAt) {
        this.droolsEndAt = droolsEndAt;
    }

    /**
     * Copy timing from another instance
     */
    public static EventTiming copyFrom(EventTiming other) {
        if (other == null) return new EventTiming();
        EventTiming copy = new EventTiming();
        copy.gatewayReceivedAt = other.gatewayReceivedAt;
        copy.gatewayPublishedAt = other.gatewayPublishedAt;
        copy.flinkReceivedAt = other.flinkReceivedAt;
        copy.flinkProcessedAt = other.flinkProcessedAt;
        copy.droolsStartAt = other.droolsStartAt;
        copy.droolsEndAt = other.droolsEndAt;
        return copy;
    }

    @Override
    public String toString() {
        return "EventTiming{" +
                "gatewayReceivedAt=" + gatewayReceivedAt +
                ", gatewayPublishedAt=" + gatewayPublishedAt +
                ", flinkReceivedAt=" + flinkReceivedAt +
                ", flinkProcessedAt=" + flinkProcessedAt +
                ", droolsStartAt=" + droolsStartAt +
                ", droolsEndAt=" + droolsEndAt +
                '}';
    }
}
