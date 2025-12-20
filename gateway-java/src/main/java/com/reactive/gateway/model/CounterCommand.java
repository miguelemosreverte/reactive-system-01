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
    private String eventId;
    private String traceId;
    private String spanId;
    private String sessionId;
    private String action;
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
