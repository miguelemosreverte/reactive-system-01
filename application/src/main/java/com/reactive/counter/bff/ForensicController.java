package com.reactive.counter.bff;

import com.reactive.counter.api.ActionRequest;
import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.domain.CounterState;
import com.reactive.counter.replay.CounterFSMAdapter;
import com.reactive.platform.benchmark.BenchmarkTypes.LogEntry;
import com.reactive.platform.benchmark.BenchmarkTypes.Trace;
import com.reactive.platform.benchmark.ObservabilityFetcher;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.replay.EventStore;
import com.reactive.platform.replay.KafkaEventStore;
import com.reactive.platform.replay.ReplayService;
import com.reactive.platform.replay.StoredEvent;
import com.reactive.platform.serialization.JsonCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Forensic Debug API for deep inspection of events.
 *
 * Unlike production tracing (which samples at 0.01%), this endpoint provides
 * 100% trace capture for debugging specific requests.
 *
 * Use cases:
 * - Debug a specific historical event by requestId
 * - Send a new event with full tracing enabled
 * - Inspect state transitions and timing breakdown
 *
 * Endpoints:
 * - GET  /api/forensic/request/{requestId}     - Lookup historical event
 * - POST /api/forensic/trace                   - Send new event with full tracing
 * - GET  /api/forensic/session/{sessionId}     - Full session forensics
 */
@RestController
@RequestMapping("/api/forensic")
public class ForensicController {

    private static final Logger log = LoggerFactory.getLogger(ForensicController.class);

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String kafkaBootstrap;

    @Value("${app.kafka.topics.events:counter-events}")
    private String eventsTopic;

    @Value("${jaeger.query.url:http://jaeger:16686}")
    private String jaegerUrl;

    @Value("${loki.url:http://loki:3100}")
    private String lokiUrl;

    private final IdGenerator idGenerator = IdGenerator.getInstance();
    private final CounterFSMAdapter fsm = new CounterFSMAdapter();

    private KafkaPublisher<CounterEvent> publisher;
    private ObservabilityFetcher observability;
    private EventStore eventStore;
    private ReplayService<CounterState> replayService;

    @PostConstruct
    void init() {
        publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(JsonCodec.forClass(CounterEvent.class))
                .keyExtractor(e -> buildKafkaKey(e.customerId(), e.sessionId())));

        observability = ObservabilityFetcher.withUrls(jaegerUrl, lokiUrl);

        eventStore = KafkaEventStore.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .aggregateIdField(fsm.aggregateIdField())
                .eventIdField(fsm.eventIdField()));

        replayService = ReplayService.withSnapshots(eventStore, fsm);

        log.info("Forensic controller initialized");
    }

    @PreDestroy
    void cleanup() {
        if (publisher != null) {
            publisher.close();
        }
    }

    private String buildKafkaKey(String customerId, String sessionId) {
        if (customerId != null && !customerId.isEmpty()) {
            return customerId + ":" + sessionId;
        }
        return sessionId;
    }

    /**
     * Extract trace ID from various formats:
     * - Raw trace ID: "abc123..."
     * - Traceparent format: "00-traceId-spanId-flags"
     */
    private String extractTraceId(String rawTraceId) {
        if (rawTraceId == null || rawTraceId.isEmpty()) {
            return null;
        }
        // Check if in traceparent format: 00-traceId-spanId-flags
        if (rawTraceId.startsWith("00-") && rawTraceId.contains("-")) {
            String[] parts = rawTraceId.split("-");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return rawTraceId;
    }

    // ========================================================================
    // Lookup Historical Event
    // ========================================================================

    /**
     * Lookup a historical event by requestId and return full forensic data.
     *
     * GET /api/forensic/request/{requestId}
     *
     * Returns:
     * - Event details (action, value, timestamp)
     * - Session context (all events in session up to this point)
     * - State before and after this event
     * - Original trace from Jaeger (if still available)
     * - Related logs from Loki
     */
    @GetMapping("/request/{requestId}")
    public Mono<ResponseEntity<ForensicResponse>> lookupRequest(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "60") int lookbackMinutes
    ) {
        return Mono.fromCallable(() -> {
            // Find the event in Kafka
            var eventResult = eventStore.getEventById(requestId);

            if (eventResult.isFailure()) {
                return ResponseEntity.internalServerError()
                        .body(ForensicResponse.error("Failed to search events: " + eventResult.error().map(Throwable::getMessage).orElse("unknown")));
            }

            var maybeEvent = eventResult.getOrThrow();
            if (maybeEvent.isEmpty()) {
                return ResponseEntity.notFound().<ForensicResponse>build();
            }

            StoredEvent event = maybeEvent.get();
            String sessionId = event.aggregateId();

            // Get session history up to this event
            var historyResult = replayService.getStateHistory(sessionId);
            List<StateTransition> transitions = new ArrayList<>();
            StateTransition targetTransition = null;

            if (historyResult.isSuccess()) {
                for (var snapshot : historyResult.getOrThrow()) {
                    var transition = new StateTransition(
                            snapshot.event().eventId(),
                            (String) snapshot.event().payload().getOrDefault("action", ""),
                            ((Number) snapshot.event().payload().getOrDefault("value", 0)).intValue(),
                            fsm.stateToMap(snapshot.stateBefore()),
                            fsm.stateToMap(snapshot.stateAfter()),
                            snapshot.event().timestamp().toString()
                    );
                    transitions.add(transition);

                    if (requestId.equals(snapshot.event().eventId())) {
                        targetTransition = transition;
                    }
                }
            }

            // Try to fetch original trace
            // traceId may be in traceparent format: 00-traceId-spanId-flags
            String rawTraceId = event.traceId();
            String traceId = extractTraceId(rawTraceId);
            TraceInfo trace = null;
            if (traceId != null && !traceId.isEmpty()) {
                trace = observability.fetchTraceByOtelId(traceId)
                        .map(this::toTraceInfo)
                        .orElse(null);
            }

            // Fetch logs
            Instant now = Instant.now();
            Instant start = now.minus(Duration.ofMinutes(lookbackMinutes));
            List<LogEntry> logEntries = observability.fetchLogsMulti(traceId, requestId, start, now);
            List<LogInfo> logs = logEntries.stream()
                    .map(l -> new LogInfo(l.timestamp(), l.labels().getOrDefault("service", "unknown"), l.line()))
                    .toList();

            // Build response
            var payload = event.payload();
            return ResponseEntity.ok(new ForensicResponse(
                    true,
                    requestId,
                    sessionId,
                    (String) payload.getOrDefault("action", ""),
                    ((Number) payload.getOrDefault("value", 0)).intValue(),
                    event.timestamp().toString(),
                    traceId,
                    targetTransition != null ? targetTransition.stateBefore() : null,
                    targetTransition != null ? targetTransition.stateAfter() : null,
                    transitions.size(),
                    transitions,
                    trace,
                    logs,
                    null
            ));
        });
    }

    // ========================================================================
    // Live Forensic Trace
    // ========================================================================

    /**
     * Send a new event with full tracing enabled and wait for complete trace.
     *
     * POST /api/forensic/trace
     *
     * This endpoint:
     * 1. Creates a new event with unique IDs
     * 2. Publishes to Kafka
     * 3. Waits for trace propagation (configurable)
     * 4. Fetches complete trace from Jaeger
     * 5. Fetches all related logs
     * 6. Returns comprehensive forensic data
     *
     * Query params:
     * - waitMs: How long to wait for trace propagation (default: 5000)
     */
    @PostMapping("/trace")
    public Mono<ResponseEntity<ForensicResponse>> traceNewEvent(
            @RequestBody ActionRequest request,
            @RequestParam(defaultValue = "") String customerId,
            @RequestParam(defaultValue = "5000") long waitMs
    ) {
        return Mono.deferContextual(ctx -> {
            // Capture trace context
            String otelTraceId = io.opentelemetry.api.trace.Span.current()
                    .getSpanContext().getTraceId();
            if (!io.opentelemetry.api.trace.Span.current().getSpanContext().isValid()) {
                otelTraceId = null;
            }

            String sessionId = request.sessionIdOrDefault();
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();

            log.info("Forensic trace: action={}, value={}, requestId={}, traceId={}",
                    request.action(), request.value(), requestId, otelTraceId);

            // Create and publish event
            CounterEvent event = CounterEvent.create(
                    requestId, customerId, eventId, sessionId, request.action(), request.value()
            );
            publisher.publishFireAndForget(event);

            final String finalTraceId = otelTraceId;
            final String finalRequestId = requestId;
            final String finalSessionId = sessionId;

            // Wait for trace propagation and fetch data
            return Mono.delay(Duration.ofMillis(waitMs))
                    .flatMap(ignored -> Mono.fromCallable(() -> {
                        // Fetch trace with extended polling
                        TraceInfo trace = null;
                        if (finalTraceId != null) {
                            trace = observability.fetchTraceByOtelId(finalTraceId)
                                    .map(this::toTraceInfo)
                                    .orElse(null);
                        }

                        // Fetch logs
                        Instant now = Instant.now();
                        List<LogEntry> logEntries = observability.fetchLogsMulti(
                                finalTraceId, finalRequestId, now.minusSeconds(120), now);
                        List<LogInfo> logs = logEntries.stream()
                                .map(l -> new LogInfo(l.timestamp(),
                                        l.labels().getOrDefault("service", "unknown"), l.line()))
                                .toList();

                        // Calculate timing breakdown from trace spans
                        TimingBreakdown timing = trace != null ? extractTiming(trace) : null;

                        return ResponseEntity.ok(new ForensicResponse(
                                true,
                                finalRequestId,
                                finalSessionId,
                                request.action(),
                                request.value(),
                                Instant.now().toString(),
                                finalTraceId,
                                null, // stateBefore - not available for new events
                                null, // stateAfter - would need to wait for result
                                1,
                                List.of(), // No history for new event
                                trace,
                                logs,
                                timing
                        ));
                    }));
        });
    }

    // ========================================================================
    // Session Forensics
    // ========================================================================

    /**
     * Get full forensic view of a session.
     *
     * GET /api/forensic/session/{sessionId}
     *
     * Returns all events in the session with state transitions.
     */
    @GetMapping("/session/{sessionId}")
    public Mono<ResponseEntity<SessionForensicResponse>> sessionForensics(
            @PathVariable String sessionId
    ) {
        return Mono.fromCallable(() -> {
            var historyResult = replayService.getStateHistory(sessionId);

            if (historyResult.isFailure()) {
                return ResponseEntity.internalServerError()
                        .body(new SessionForensicResponse(
                                false, sessionId, 0, List.of(),
                                "Failed: " + historyResult.error().map(Throwable::getMessage).orElse("unknown")));
            }

            var snapshots = historyResult.getOrThrow();
            List<StateTransition> transitions = snapshots.stream()
                    .map(s -> new StateTransition(
                            s.event().eventId(),
                            (String) s.event().payload().getOrDefault("action", ""),
                            ((Number) s.event().payload().getOrDefault("value", 0)).intValue(),
                            fsm.stateToMap(s.stateBefore()),
                            fsm.stateToMap(s.stateAfter()),
                            s.event().timestamp().toString()
                    ))
                    .toList();

            return ResponseEntity.ok(new SessionForensicResponse(
                    true, sessionId, transitions.size(), transitions, null));
        });
    }

    // ========================================================================
    // Health
    // ========================================================================

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "jaegerUrl", jaegerUrl,
                "lokiUrl", lokiUrl,
                "eventStoreHealthy", eventStore.isHealthy()
        ));
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private TraceInfo toTraceInfo(Trace trace) {
        // Build service map from processes
        Map<String, String> processToService = new HashMap<>();
        for (var entry : trace.processes().entrySet()) {
            processToService.put(entry.getKey(), entry.getValue().serviceName());
        }

        // Filter to pipeline-relevant spans
        var filteredSpans = trace.spans().stream()
                .filter(s -> {
                    String service = processToService.getOrDefault(s.processId(), "");
                    // Exclude Jaeger self-traces and polling
                    if (service.contains("jaeger")) return false;
                    if (s.operationName().contains("/api/traces")) return false;
                    return true;
                })
                .sorted((a, b) -> Long.compare(a.startTime(), b.startTime()))
                .toList();

        // Calculate duration
        long minStart = filteredSpans.stream().mapToLong(s -> s.startTime()).min().orElse(0);
        long maxEnd = filteredSpans.stream().mapToLong(s -> s.startTime() + s.duration()).max().orElse(0);
        long durationMs = (maxEnd - minStart) / 1000;

        return new TraceInfo(
                trace.traceId(),
                filteredSpans.size(),
                durationMs,
                minStart,
                filteredSpans.stream()
                        .map(s -> new SpanInfo(
                                s.spanId(),
                                s.operationName(),
                                s.startTime(),
                                s.duration() / 1000,
                                processToService.getOrDefault(s.processId(), "unknown")
                        ))
                        .toList()
        );
    }

    private TimingBreakdown extractTiming(TraceInfo trace) {
        long gatewayMs = 0, kafkaMs = 0, flinkMs = 0, droolsMs = 0;

        for (SpanInfo span : trace.spans()) {
            String service = span.service().toLowerCase();
            String op = span.operationName().toLowerCase();

            if (service.contains("counter-application") || service.contains("gateway")) {
                if (op.contains("post") || op.contains("controller")) {
                    gatewayMs += span.durationMs();
                } else if (op.contains("kafka") || op.contains("publish")) {
                    kafkaMs += span.durationMs();
                }
            } else if (service.contains("flink")) {
                flinkMs += span.durationMs();
            } else if (service.contains("drools")) {
                droolsMs += span.durationMs();
            }
        }

        return new TimingBreakdown(gatewayMs, kafkaMs, flinkMs, droolsMs, trace.durationMs());
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    public record ForensicResponse(
            boolean success,
            String requestId,
            String sessionId,
            String action,
            int value,
            String timestamp,
            String traceId,
            Map<String, Object> stateBefore,
            Map<String, Object> stateAfter,
            int totalEventsInSession,
            List<StateTransition> sessionHistory,
            TraceInfo trace,
            List<LogInfo> logs,
            TimingBreakdown timing
    ) {
        public static ForensicResponse error(String message) {
            return new ForensicResponse(false, null, null, null, 0, null, null,
                    null, null, 0, List.of(), null, List.of(),
                    null);
        }
    }

    public record SessionForensicResponse(
            boolean success,
            String sessionId,
            int eventCount,
            List<StateTransition> transitions,
            String error
    ) {}

    public record StateTransition(
            String requestId,
            String action,
            int value,
            Map<String, Object> stateBefore,
            Map<String, Object> stateAfter,
            String timestamp
    ) {}

    public record TraceInfo(
            String traceId,
            int spanCount,
            long durationMs,
            long startTimeUs,
            List<SpanInfo> spans
    ) {}

    public record SpanInfo(
            String spanId,
            String operationName,
            long startTimeUs,
            long durationMs,
            String service
    ) {}

    public record LogInfo(String timestamp, String service, String message) {}

    public record TimingBreakdown(
            long gatewayMs,
            long kafkaMs,
            long flinkMs,
            long droolsMs,
            long totalMs
    ) {}
}
