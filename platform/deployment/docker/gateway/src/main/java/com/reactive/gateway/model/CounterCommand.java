package com.reactive.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CounterCommand {
    private String requestId;    // Correlation ID for this request
    private String customerId;   // Customer/tenant ID for multi-tenancy
    private String eventId;      // Unique event ID
    private String sessionId;    // Session ID (counter instance)
    private String action;       // increment, decrement, set
    private Integer value;
    private Long timestamp;
    private EventTiming timing;

    @JsonProperty("source")
    @Builder.Default
    private String source = "gateway-java";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventTiming {
        private Long gatewayReceivedAt;
        private Long gatewayPublishedAt;
    }
}
