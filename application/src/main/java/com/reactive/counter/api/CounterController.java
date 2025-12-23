package com.reactive.counter.api;

import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.domain.CounterState;
import com.reactive.counter.service.ResultConsumerService;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.observe.Log;
import com.reactive.platform.serialization.JsonCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counter REST API.
 *
 * Clean controller - business logic only.
 * Tracing is handled automatically by OTel agent (HTTP instrumentation).
 * KafkaPublisher handles its own tracing internally.
 */
@RestController
@RequestMapping("/api")
public class CounterController {

    private static final Logger log = LoggerFactory.getLogger(CounterController.class);

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String kafkaBootstrap;

    @Value("${app.kafka.topics.events:counter-events}")
    private String eventsTopic;

    @Value("${app.delivery-mode:fire-and-forget}")
    private String deliveryMode;

    @Value("${app.result-timeout-ms:5000}")
    private long resultTimeoutMs;

    @Autowired(required = false)
    private ResultConsumerService resultConsumerService;

    private final IdGenerator idGenerator = IdGenerator.getInstance();
    private final Map<String, CounterState> stateStore = new ConcurrentHashMap<>();
    private KafkaPublisher<CounterEvent> publisher;

    @PostConstruct
    void init() {
        publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(JsonCodec.forClass(CounterEvent.class))
                .keyExtractor(e -> kafkaKey(e.customerId(), e.sessionId())));

        // Register callback for state updates from Kafka results
        if (resultConsumerService != null) {
            resultConsumerService.setStateUpdateCallback(result ->
                    updateState(result.sessionId(), result.currentValue(), result.alert(), result.message()));
        }
    }

    @PreDestroy
    void cleanup() {
        if (publisher != null) {
            publisher.close();
        }
    }

    // ========================================================================
    // Endpoints
    // ========================================================================

    @PostMapping("/customers/{customerId}/counter")
    public Mono<ResponseEntity<ActionResponse>> submitWithCustomer(
            @PathVariable String customerId,
            @RequestBody ActionRequest request) {
        return submit(request, customerId);
    }

    @PostMapping("/counter")
    public Mono<ResponseEntity<ActionResponse>> submit(@RequestBody ActionRequest request) {
        return submit(request, "");
    }

    @PostMapping("/customers/{customerId}/counter/fast")
    public ResponseEntity<ActionResponse> submitFastWithCustomer(
            @PathVariable String customerId,
            @RequestBody ActionRequest request) {
        return submitFast(request, customerId);
    }

    @PostMapping("/counter/fast")
    public ResponseEntity<ActionResponse> submitFast(@RequestBody ActionRequest request) {
        return submitFast(request, "");
    }

    @GetMapping("/counter/status")
    public ResponseEntity<StatusResponse> getStatus(
            @RequestParam(defaultValue = "default") String sessionId) {
        CounterState state = stateStore.getOrDefault(sessionId, CounterState.initial());
        return ResponseEntity.ok(new StatusResponse(
                sessionId, state.value(), state.alert().name(), state.message()));
    }

    // ========================================================================
    // Core Logic (pure business operations)
    // ========================================================================

    private Mono<ResponseEntity<ActionResponse>> submit(ActionRequest request, String customerId) {
        boolean waitForResult = "wait-for-result".equals(deliveryMode) && resultConsumerService != null;

        String sessionId = request.sessionIdOrDefault();
        String requestId = idGenerator.generateRequestId();
        String eventId = idGenerator.generateEventId();

        // Add business context to current span (created by OTel HTTP instrumentation)
        addSpanAttributes(requestId, customerId, eventId, sessionId, request);

        CounterEvent event = CounterEvent.create(
                requestId, customerId, eventId, sessionId, request.action(), request.value());

        log.info("Publishing event: action={}, value={}, session={}",
                request.action(), request.value(), sessionId);

        publisher.publishFireAndForget(event);

        ActionResponse response = new ActionResponse(
                true, requestId, customerId,
                eventId, traceId(), waitForResult ? "pending" : "accepted");

        if (waitForResult) {
            return Mono.fromFuture(resultConsumerService.registerPendingTransaction(eventId))
                    .timeout(Duration.ofMillis(resultTimeoutMs))
                    .map(result -> ResponseEntity.ok(response.withStatus("completed")))
                    .onErrorReturn(ResponseEntity.ok(response.withStatus("timeout")));
        }

        return Mono.just(ResponseEntity.ok(response));
    }

    private ResponseEntity<ActionResponse> submitFast(ActionRequest request, String customerId) {
        String sessionId = request.sessionIdOrDefault();
        String requestId = idGenerator.generateRequestId();
        String eventId = idGenerator.generateEventId();

        addSpanAttributes(requestId, customerId, eventId, sessionId, request);

        CounterEvent event = CounterEvent.create(
                requestId, customerId, eventId, sessionId, request.action(), request.value());

        log.info("Publishing event (fast): action={}, value={}, session={}",
                request.action(), request.value(), sessionId);

        publisher.publishFireAndForget(event);

        return ResponseEntity.ok(new ActionResponse(
                true, requestId, customerId,
                eventId, traceId(), "accepted"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String kafkaKey(String customerId, String sessionId) {
        return customerId.isEmpty()
                ? sessionId
                : customerId + ":" + sessionId;
    }

    /**
     * Add business context attributes to current span using platform Log API.
     * No third-party OTel types leak into this controller.
     */
    private void addSpanAttributes(String requestId, String customerId, String eventId,
                                   String sessionId, ActionRequest request) {
        Log.attr("requestId", requestId);
        Log.attr("customerId", customerId);
        Log.attr("eventId", eventId);
        Log.attr("session.id", sessionId);
        Log.attr("counter.action", request.action());
        Log.attr("counter.value", request.value());
    }

    private String traceId() {
        return Log.traceId();
    }

    /** Called by ResultConsumerService to update local state cache. */
    public void updateState(String sessionId, int value, String alert, String message) {
        stateStore.put(sessionId, new CounterState(
                value, CounterState.AlertLevel.fromString(alert), message));
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
    ) {
        public ActionResponse withStatus(String newStatus) {
            return new ActionResponse(success, requestId, customerId, eventId, otelTraceId, newStatus);
        }
    }

    public record StatusResponse(String sessionId, int value, String alert, String message) {}
}
