package com.reactive.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CounterResult {
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
