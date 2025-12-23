package com.reactive.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reactive.platform.observe.Traceable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import static com.reactive.platform.Opt.or;

/**
 * Result from Flink counter processing.
 * Implements Traceable for automatic span attribute extraction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CounterResult implements Traceable {
    private String requestId;     // Correlation ID for this request
    private String customerId;    // Customer/tenant ID
    private String eventId;       // Unique event ID
    private String sessionId;     // Session ID (counter instance)
    private Integer currentValue; // Current counter value
    private String alert;         // Alert level from Drools
    private String message;       // Alert message
    private Long timestamp;

    // Nested timing object from Flink
    private EventTiming timing;

    // ========================================================================
    // Traceable implementation (Scala-style accessors)
    // ========================================================================

    @Override public String requestId() { return or(requestId, ""); }
    @Override public String customerId() { return or(customerId, ""); }
    @Override public String eventId() { return or(eventId, ""); }
    @Override public String sessionId() { return or(sessionId, ""); }

    // Convenience method to get processing time
    public Long getProcessingTimeMs() {
        if (timing != null && timing.getFlinkProcessedAt() != null && timing.getGatewayReceivedAt() != null) {
            return timing.getFlinkProcessedAt() - timing.getGatewayReceivedAt();
        }
        return null;
    }

    // Alias for currentValue
    public Integer getNewValue() {
        return currentValue;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventTiming {
        private Long gatewayReceivedAt;
        private Long gatewayPublishedAt;
        private Long flinkReceivedAt;
        private Long flinkProcessedAt;
        private Long droolsCalledAt;
        private Long droolsRespondedAt;
    }
}
