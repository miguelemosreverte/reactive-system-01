package com.reactive.counter.api;

import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.domain.CounterState;
import com.reactive.counter.service.ResultConsumerService;
import com.reactive.diagnostic.DiagnosticCollector;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.observe.Log;
import com.reactive.counter.serialization.AvroCounterEventCodec;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Autowired
    private DiagnosticCollector diagnosticCollector;

    private final IdGenerator idGenerator = IdGenerator.getInstance();
    // Bounded LRU cache for session states (max 10k entries to prevent OOM)
    private static final int MAX_STATE_ENTRIES = 10_000;
    private final Map<String, CounterState> stateStore = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CounterState> eldest) {
                    return size() > MAX_STATE_ENTRIES;
                }
            });
    private KafkaPublisher<CounterEvent> publisher;

    @PostConstruct
    void init() {
        publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(AvroCounterEventCodec.create())  // Avro: ~5-10x faster than JSON
                .keyExtractor(e -> e.eventId())  // Use eventId for partition distribution
                .fireAndForget());  // acks=0, maxInFlight=100 for max throughput

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

        log.debug("Publishing event: action={}, value={}, session={}",
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
        long startTime = System.nanoTime();

        String sessionId = request.sessionIdOrDefault();
        String requestId = idGenerator.generateRequestId();
        String eventId = idGenerator.generateEventId();

        // Record http_receive stage
        diagnosticCollector.recordStageEvent("http_receive", (System.nanoTime() - startTime) / 1_000_000.0);
        long validationStart = System.nanoTime();

        addSpanAttributes(requestId, customerId, eventId, sessionId, request);

        CounterEvent event = CounterEvent.create(
                requestId, customerId, eventId, sessionId, request.action(), request.value());

        // Record validation/serialization stage
        diagnosticCollector.recordStageEvent("validation", (System.nanoTime() - validationStart) / 1_000_000.0);
        long kafkaStart = System.nanoTime();

        log.debug("Publishing event (fast): action={}, value={}, session={}",
                request.action(), request.value(), sessionId);

        publisher.publishFireAndForget(event);

        // Record kafka_produce stage
        double kafkaLatency = (System.nanoTime() - kafkaStart) / 1_000_000.0;
        diagnosticCollector.recordStageEvent("kafka_produce", kafkaLatency);
        diagnosticCollector.recordDependencyCall("kafka", kafkaLatency, true);

        // Record total event
        double totalLatency = (System.nanoTime() - startTime) / 1_000_000.0;
        diagnosticCollector.recordEvent(100, totalLatency); // ~100 bytes per event

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
     * Add business context attributes to current span.
     * Only called when tracing is sampled (check first to avoid overhead).
     */
    private void addSpanAttributes(String requestId, String customerId, String eventId,
                                   String sessionId, ActionRequest request) {
        // Skip if not sampled (99.9% of requests with 0.1% sampling)
        if (!Log.isSampled()) return;

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
