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
import com.reactive.platform.observe.Log;
import com.reactive.counter.replay.AvroKafkaEventStore;
import com.reactive.platform.replay.EventStore;
import com.reactive.platform.replay.ReplayService;
import com.reactive.platform.replay.StoredEvent;
import com.reactive.counter.serialization.AvroCounterEventCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
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
    private final SecureRandom secureRandom = new SecureRandom();

    private KafkaPublisher<CounterEvent> publisher;
    private ObservabilityFetcher observability;
    private EventStore eventStore;
    private ReplayService<CounterState> replayService;

    @PostConstruct
    void init() {
        publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(AvroCounterEventCodec.create())
                .keyExtractor(e -> buildKafkaKey(e.customerId(), e.sessionId())));

        observability = ObservabilityFetcher.withUrls(jaegerUrl, lokiUrl);

        eventStore = AvroKafkaEventStore.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic));

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

    /**
     * Generate a random 128-bit trace ID (32 hex chars).
     */
    private String generateTraceId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Generate a random 64-bit span ID (16 hex chars).
     */
    private String generateSpanId() {
        byte[] bytes = new byte[8];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Create a force-sampled traceparent header.
     * Format: 00-traceId-spanId-01 (01 = sampled flag)
     */
    private String createForceSampledTraceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
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
    // Replay with Full Tracing
    // ========================================================================

    /**
     * Replay a historical event with full tracing enabled.
     *
     * POST /api/forensic/replay/{requestId}
     *
     * This endpoint:
     * 1. Looks up the original event by requestId
     * 2. Creates a NEW event with the same action/value but new IDs
     * 3. Creates a FORCE-SAMPLED trace (bypassing probabilistic sampling)
     * 4. Sends it through the pipeline with full tracing (no side effects on original state)
     * 5. Waits for trace propagation
     * 6. Returns complete trace from Jaeger + original event context
     *
     * The replay uses a special "replay-" prefixed sessionId to avoid
     * affecting the original session's state.
     */
    @PostMapping("/replay/{requestId}")
    public Mono<ResponseEntity<ForensicResponse>> replayWithTrace(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "5000") long waitMs
    ) {
        log.info("[FORENSIC:HTTP] ========================================");
        log.info("[FORENSIC:HTTP] POST /api/forensic/replay/{}", requestId);
        log.info("[FORENSIC:HTTP] Parameters:");
        log.info("[FORENSIC:HTTP]   requestId={}", requestId);
        log.info("[FORENSIC:HTTP]   waitMs={}", waitMs);

        return Mono.fromCallable(() -> {
            // 1. Look up original event
            log.info("[FORENSIC:LOOKUP] Searching for original event in Kafka...");
            var eventResult = eventStore.getEventById(requestId);
            if (eventResult.isFailure()) {
                log.error("[FORENSIC:LOOKUP] Event store lookup failed");
                return null; // Will handle in flatMap
            }
            var maybeEvent = eventResult.getOrThrow();
            if (maybeEvent.isEmpty()) {
                log.warn("[FORENSIC:LOOKUP] Event not found: requestId={}", requestId);
                return null;
            }
            log.info("[FORENSIC:LOOKUP] Found original event");
            return maybeEvent.get();
        }).flatMap(originalEvent -> {
            if (originalEvent == null) {
                log.warn("[FORENSIC:RESPONSE] Returning 404 - event not found");
                return Mono.just(ResponseEntity.notFound().<ForensicResponse>build());
            }

            // 2. Extract original event data
            var payload = originalEvent.payload();
            String action = (String) payload.getOrDefault("action", "increment");
            int value = ((Number) payload.getOrDefault("value", 1)).intValue();
            String originalSessionId = originalEvent.aggregateId();
            String customerId = (String) payload.getOrDefault("customerId", "");

            log.info("[FORENSIC:ORIGINAL] Original event details:");
            log.info("[FORENSIC:ORIGINAL]   action={}", action);
            log.info("[FORENSIC:ORIGINAL]   value={}", value);
            log.info("[FORENSIC:ORIGINAL]   sessionId={}", originalSessionId);
            log.info("[FORENSIC:ORIGINAL]   customerId={}", customerId);
            log.info("[FORENSIC:ORIGINAL]   timestamp={}", originalEvent.timestamp());

            // 3. Create replay session (prefixed to avoid side effects)
            String replaySessionId = "replay-" + originalSessionId + "-" + System.currentTimeMillis();
            String newRequestId = idGenerator.generateRequestId();
            String newEventId = idGenerator.generateEventId();

            log.info("[FORENSIC:REPLAY] Creating replay event:");
            log.info("[FORENSIC:REPLAY]   newRequestId={}", newRequestId);
            log.info("[FORENSIC:REPLAY]   newEventId={}", newEventId);
            log.info("[FORENSIC:REPLAY]   replaySessionId={}", replaySessionId);

            // 4. Get the actual trace ID from the current OTel span context
            // The OTel Java agent auto-instruments HTTP requests, so we have an active trace
            // We use THIS trace ID (not a synthetic one) so the Jaeger link works
            String traceId = io.opentelemetry.api.trace.Span.current().getSpanContext().getTraceId();
            String spanId = io.opentelemetry.api.trace.Span.current().getSpanContext().getSpanId();

            log.info("[FORENSIC:TRACE] OpenTelemetry context:");
            log.info("[FORENSIC:TRACE]   traceId={}", traceId);
            log.info("[FORENSIC:TRACE]   spanId={}", spanId);

            // Check if valid trace ID (OTel uses all-zeros for invalid)
            if (traceId == null || traceId.equals("00000000000000000000000000000000")) {
                // Fallback to synthetic trace ID if no active span
                log.warn("[FORENSIC:TRACE] No valid OTel trace, generating synthetic traceId");
                traceId = generateTraceId();
                log.info("[FORENSIC:TRACE]   syntheticTraceId={}", traceId);
            }

            log.info("[FORENSIC:REPLAY] Replay forensic: original={}, action={}, value={}, newRequestId={}, traceId={}",
                    requestId, action, value, newRequestId, traceId);

            // 5. Add forensic attributes to the current span
            io.opentelemetry.api.trace.Span currentSpan = io.opentelemetry.api.trace.Span.current();
            currentSpan.setAttribute("forensic.original_request_id", requestId);
            currentSpan.setAttribute("forensic.replay_request_id", newRequestId);
            currentSpan.setAttribute("forensic.action", action);
            currentSpan.setAttribute("forensic.value", (long) value);

            // 6. Create and publish replay event
            // The OTel Java agent auto-instruments Kafka producers, so the current trace context
            // will be propagated to the Kafka message headers automatically
            log.info("[FORENSIC:KAFKA] Creating CounterEvent for Kafka publish:");
            CounterEvent replayEvent = CounterEvent.create(
                    newRequestId, customerId, newEventId, replaySessionId, action, value
            );
            log.info("[FORENSIC:KAFKA]   requestId={}", replayEvent.requestId());
            log.info("[FORENSIC:KAFKA]   customerId={}", replayEvent.customerId());
            log.info("[FORENSIC:KAFKA]   eventId={}", replayEvent.eventId());
            log.info("[FORENSIC:KAFKA]   sessionId={}", replayEvent.sessionId());
            log.info("[FORENSIC:KAFKA]   action={}", replayEvent.action());
            log.info("[FORENSIC:KAFKA]   value={}", replayEvent.value());

            // Use fire-and-forget since the OTel agent handles trace propagation
            log.info("[FORENSIC:KAFKA] Publishing to Kafka (fire-and-forget)...");
            publisher.publishFireAndForget(replayEvent);

            log.info("[FORENSIC:KAFKA] Replay published successfully with traceId={}", traceId);

            final String finalTraceId = traceId;
            final String finalNewRequestId = newRequestId;
            final StoredEvent finalOriginalEvent = originalEvent;

            // 7. Wait and fetch trace
            log.info("[FORENSIC:WAIT] Waiting {}ms for trace propagation...", waitMs);
            return Mono.delay(Duration.ofMillis(waitMs))
                    .flatMap(ignored -> Mono.fromCallable(() -> {
                        log.info("[FORENSIC:FETCH] Fetching trace data from Jaeger...");
                        // Fetch replay trace
                        TraceInfo trace = null;
                        if (finalTraceId != null && !finalTraceId.isEmpty()) {
                            // Try multiple times with backoff
                            for (int attempt = 0; attempt < 3 && trace == null; attempt++) {
                                log.info("[FORENSIC:FETCH] Attempt {} to fetch trace {}", attempt + 1, finalTraceId);
                                trace = observability.fetchTraceByOtelId(finalTraceId)
                                        .map(this::toTraceInfo)
                                        .orElse(null);
                                if (trace == null && attempt < 2) {
                                    log.info("[FORENSIC:FETCH] Trace not found yet, waiting 1s...");
                                    Thread.sleep(1000);
                                }
                            }
                            if (trace != null) {
                                log.info("[FORENSIC:TRACE] Trace found with {} spans, duration={}ms",
                                        trace.spanCount(), trace.durationMs());
                            } else {
                                log.warn("[FORENSIC:TRACE] Trace NOT found after 3 attempts");
                            }
                        }

                        // Fetch logs for replay
                        log.info("[FORENSIC:LOGS] Fetching logs from Loki...");
                        Instant now = Instant.now();
                        List<LogEntry> logEntries = observability.fetchLogsMulti(
                                finalTraceId, finalNewRequestId, now.minusSeconds(60), now);
                        List<LogInfo> logs = logEntries.stream()
                                .map(l -> new LogInfo(l.timestamp(),
                                        l.labels().getOrDefault("service", "unknown"), l.line()))
                                .toList();
                        log.info("[FORENSIC:LOGS] Found {} log entries", logs.size());

                        // Get original event history for context
                        var historyResult = replayService.getStateHistory(finalOriginalEvent.aggregateId());
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

                        // Calculate timing from trace
                        TimingBreakdown timing = trace != null ? extractTiming(trace) : null;

                        var originalPayload = finalOriginalEvent.payload();
                        return ResponseEntity.ok(new ForensicResponse(
                                true,
                                requestId, // Original request ID for reference
                                finalOriginalEvent.aggregateId(),
                                (String) originalPayload.getOrDefault("action", ""),
                                ((Number) originalPayload.getOrDefault("value", 0)).intValue(),
                                finalOriginalEvent.timestamp().toString(),
                                finalTraceId, // Force-sampled trace ID from forensic publish
                                targetTransition != null ? targetTransition.stateBefore() : null,
                                targetTransition != null ? targetTransition.stateAfter() : null,
                                transitions.size(),
                                transitions,
                                trace, // Full trace from replay!
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
