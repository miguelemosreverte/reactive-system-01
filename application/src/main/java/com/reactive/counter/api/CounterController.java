package com.reactive.counter.api;

import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.domain.CounterState;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.JsonCodec;
import com.reactive.platform.tracing.Tracing;
import io.opentelemetry.api.trace.Span;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counter REST API.
 *
 * Thin adapter layer - delegates to domain logic (CounterFSM)
 * and platform infrastructure (KafkaPublisher).
 *
 * Business IDs:
 * - requestId: Correlation ID for request tracking
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 *
 * Note: OpenTelemetry trace propagation is handled automatically by the OTel agent.
 */
@RestController
@RequestMapping("/api")
public class CounterController {

    private static final Logger log = LoggerFactory.getLogger(CounterController.class);

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String kafkaBootstrap;

    @Value("${app.kafka.topics.events:counter-events}")
    private String eventsTopic;

    private final Tracing tracing = Tracing.create("counter-api");
    private final IdGenerator idGenerator = IdGenerator.getInstance();
    private final Map<String, CounterState> stateStore = new ConcurrentHashMap<>();

    private KafkaPublisher<CounterEvent> publisher;

    @PostConstruct
    void init() {
        Codec<CounterEvent> codec = JsonCodec.forClass(CounterEvent.class);

        publisher = KafkaPublisher.<CounterEvent>builder()
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(codec)
                .keyExtractor(e -> buildKafkaKey(e.customerId(), e.sessionId()))
                .tracer(tracing.tracer())
                .build();
    }

    @PreDestroy
    void cleanup() {
        if (publisher != null) {
            publisher.close();
        }
    }

    /**
     * Build Kafka key for partitioning: customerId:sessionId
     */
    private String buildKafkaKey(String customerId, String sessionId) {
        if (customerId != null && !customerId.isEmpty()) {
            return customerId + ":" + sessionId;
        }
        return sessionId;
    }

    /**
     * Submit a counter action with customer context.
     * POST /api/customers/{customerId}/counter
     */
    @PostMapping("/customers/{customerId}/counter")
    public Mono<ResponseEntity<ActionResponse>> submitActionWithCustomer(
            @PathVariable String customerId,
            @RequestBody ActionRequest request) {
        return processAction(request, customerId);
    }

    /**
     * Submit a counter action (backwards compatible).
     * POST /api/counter
     */
    @PostMapping("/counter")
    public Mono<ResponseEntity<ActionResponse>> submitAction(@RequestBody ActionRequest request) {
        return processAction(request, null);
    }

    private Mono<ResponseEntity<ActionResponse>> processAction(ActionRequest request, String customerId) {
        return Mono.fromCallable(() -> tracing.span("counter.submit", span -> {
            String sessionId = request.sessionIdOrDefault();
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();
            String otelTraceId = span.getSpanContext().getTraceId();

            setSpanAttributes(span, requestId, customerId, eventId, sessionId, request);

            try {
                MDC.put("requestId", requestId);
                if (customerId != null) MDC.put("customerId", customerId);

                CounterEvent event = CounterEvent.create(
                        requestId, customerId, eventId, sessionId, request.action(), request.value()
                );
                publisher.publishFireAndForget(event);

                log.info("Event published: action={}, value={}, session={}",
                        request.action(), request.value(), sessionId);

                return ResponseEntity.ok(new ActionResponse(
                        true, requestId, customerId != null ? customerId : "",
                        eventId, otelTraceId, "accepted"
                ));
            } finally {
                MDC.remove("requestId");
                MDC.remove("customerId");
            }
        }));
    }

    private void setSpanAttributes(Span span, String requestId, String customerId,
                                   String eventId, String sessionId, ActionRequest request) {
        span.setAttribute("requestId", requestId);
        span.setAttribute("customerId", customerId != null ? customerId : "");
        span.setAttribute("eventId", eventId);
        span.setAttribute("session.id", sessionId);
        span.setAttribute("counter.action", request.action());
        span.setAttribute("counter.value", request.value());
    }

    /**
     * Fast path for benchmarks with customer context.
     * POST /api/customers/{customerId}/counter/fast
     */
    @PostMapping("/customers/{customerId}/counter/fast")
    public ResponseEntity<ActionResponse> submitFastWithCustomer(
            @PathVariable String customerId,
            @RequestBody ActionRequest request) {
        return processActionFast(request, customerId);
    }

    /**
     * Fast path for benchmarks (backwards compatible).
     * POST /api/counter/fast
     */
    @PostMapping("/counter/fast")
    public ResponseEntity<ActionResponse> submitFast(@RequestBody ActionRequest request) {
        return processActionFast(request, null);
    }

    private ResponseEntity<ActionResponse> processActionFast(ActionRequest request, String customerId) {
        String sessionId = request.sessionIdOrDefault();
        String requestId = idGenerator.generateRequestId();
        String eventId = idGenerator.generateEventId();
        String otelTraceId = Span.current().getSpanContext().getTraceId();

        Span span = Span.current();
        span.setAttribute("requestId", requestId);
        span.setAttribute("customerId", customerId != null ? customerId : "");
        span.setAttribute("eventId", eventId);

        try {
            MDC.put("requestId", requestId);
            if (customerId != null) MDC.put("customerId", customerId);

            CounterEvent event = CounterEvent.create(
                    requestId, customerId, eventId, sessionId, request.action(), request.value()
            );
            publisher.publishFireAndForget(event);

            log.info("Event published (fast): action={}, value={}, session={}",
                    request.action(), request.value(), sessionId);

            return ResponseEntity.ok(new ActionResponse(
                    true, requestId, customerId != null ? customerId : "",
                    eventId, otelTraceId, "accepted"
            ));
        } finally {
            MDC.remove("requestId");
            MDC.remove("customerId");
        }
    }

    /**
     * Get current counter status.
     */
    @GetMapping("/counter/status")
    public ResponseEntity<StatusResponse> getStatus(
            @RequestParam(defaultValue = "default") String sessionId
    ) {
        CounterState state = stateStore.getOrDefault(sessionId, CounterState.initial());

        return ResponseEntity.ok(new StatusResponse(
                sessionId,
                state.value(),
                state.alert().name(),
                state.message()
        ));
    }

    /**
     * Update state (called by result consumer).
     */
    public void updateState(String sessionId, int value, String alert, String message) {
        stateStore.put(sessionId, new CounterState(
                value,
                CounterState.AlertLevel.fromString(alert),
                message
        ));
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    public record ActionResponse(
            boolean success,
            String requestId,
            String customerId,
            String eventId,
            String otelTraceId,
            String status
    ) {}

    public record StatusResponse(
            String sessionId,
            int value,
            String alert,
            String message
    ) {}
}
