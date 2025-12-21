package com.reactive.counter.bff;

import com.reactive.counter.api.ActionRequest;
import com.reactive.counter.domain.CounterEvent;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.observability.ObservabilityClient;
import com.reactive.platform.observability.ObservabilityClient.LogEntry;
import com.reactive.platform.observability.ObservabilityClient.TraceData;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.JsonCodec;
import com.reactive.platform.serialization.Result;
import com.reactive.platform.tracing.Tracing;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backend-for-Frontend (BFF) API.
 *
 * Provides aggregated endpoints for the frontend that combine multiple operations:
 * - Traced actions with automatic trace/log retrieval
 * - Debug mode for verbose observability
 *
 * Debug Mode (X-Debug: true header):
 * - Waits for trace propagation
 * - Fetches full trace from Jaeger
 * - Fetches logs from Loki
 * - Returns comprehensive debug response
 */
@RestController
@RequestMapping("/api/bff")
public class BffController {

    private static final Logger log = LoggerFactory.getLogger(BffController.class);

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String kafkaBootstrap;

    @Value("${app.kafka.topics.events:counter-events}")
    private String eventsTopic;

    @Value("${jaeger.query.url:http://jaeger:16686}")
    private String jaegerUrl;

    @Value("${loki.url:http://loki:3100}")
    private String lokiUrl;

    private final Tracing tracing = Tracing.create("bff-api");
    private final IdGenerator idGenerator = IdGenerator.getInstance();

    private KafkaPublisher<CounterEvent> publisher;
    private ObservabilityClient observability;

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

        observability = ObservabilityClient.create(jaegerUrl, lokiUrl);

        log.info("BFF initialized with Jaeger={}, Loki={}", jaegerUrl, lokiUrl);
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

    // ========================================================================
    // Traced Counter Action (with optional debug mode)
    // ========================================================================

    /**
     * Submit counter action with optional debug mode.
     *
     * Headers:
     * - X-Debug: true - Wait for trace/logs and return comprehensive debug data
     * - X-Debug-Wait-Ms: 2000 - How long to wait for trace propagation (default: 2000ms)
     *
     * POST /api/bff/customers/{customerId}/counter
     */
    @PostMapping("/customers/{customerId}/counter")
    public Mono<ResponseEntity<ActionDebugResponse>> submitWithDebug(
            @PathVariable String customerId,
            @RequestBody ActionRequest request,
            @RequestHeader(value = "X-Debug", defaultValue = "false") boolean debugMode,
            @RequestHeader(value = "X-Debug-Wait-Ms", defaultValue = "2000") long debugWaitMs
    ) {
        return processAction(request, customerId, debugMode, debugWaitMs);
    }

    /**
     * Submit counter action without customer (backwards compatible).
     * POST /api/bff/counter
     */
    @PostMapping("/counter")
    public Mono<ResponseEntity<ActionDebugResponse>> submitWithDebugNoCustomer(
            @RequestBody ActionRequest request,
            @RequestHeader(value = "X-Debug", defaultValue = "false") boolean debugMode,
            @RequestHeader(value = "X-Debug-Wait-Ms", defaultValue = "2000") long debugWaitMs
    ) {
        return processAction(request, null, debugMode, debugWaitMs);
    }

    private Mono<ResponseEntity<ActionDebugResponse>> processAction(
            ActionRequest request,
            String customerId,
            boolean debugMode,
            long debugWaitMs
    ) {
        return Mono.fromCallable(() -> tracing.span("bff.submit", span -> {
            String sessionId = request.sessionIdOrDefault();
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();
            String otelTraceId = span.getSpanContext().getTraceId();

            span.setAttribute("requestId", requestId);
            span.setAttribute("customerId", customerId != null ? customerId : "");
            span.setAttribute("eventId", eventId);
            span.setAttribute("session.id", sessionId);
            span.setAttribute("debug.mode", debugMode);

            try {
                MDC.put("requestId", requestId);
                if (customerId != null) MDC.put("customerId", customerId);

                log.info("BFF: action={}, value={}, debug={}", request.action(), request.value(), debugMode);

                CounterEvent event = CounterEvent.create(
                        requestId, customerId, eventId, sessionId, request.action(), request.value()
                );
                publisher.publishFireAndForget(event);

                return new ActionDebugResponse(
                        true, requestId, customerId != null ? customerId : "",
                        eventId, otelTraceId, "accepted", null, null
                );
            } finally {
                MDC.remove("requestId");
                MDC.remove("customerId");
            }
        }))
        .flatMap(baseResponse -> {
            if (!debugMode) {
                return Mono.just(ResponseEntity.ok(baseResponse));
            }

            // Debug mode: wait and fetch traces/logs
            return Mono.delay(Duration.ofMillis(debugWaitMs))
                    .flatMap(ignored -> fetchDebugData(baseResponse))
                    .map(ResponseEntity::ok);
        });
    }

    private Mono<ActionDebugResponse> fetchDebugData(ActionDebugResponse base) {
        return Mono.fromFuture(() -> {
            CompletableFuture<Result<TraceData>> traceFuture =
                    observability.fetchTrace(base.otelTraceId());

            Instant now = Instant.now();
            CompletableFuture<Result<List<LogEntry>>> logsFuture =
                    observability.fetchLogs(base.requestId(), now.minusSeconds(60), now);

            return traceFuture.thenCombine(logsFuture, (traceResult, logsResult) -> {
                TraceInfo trace = traceResult.isSuccess()
                        ? TraceInfo.from(traceResult.getOrThrow())
                        : null;

                List<LogInfo> logs = logsResult.isSuccess()
                        ? logsResult.getOrThrow().stream()
                            .map(l -> new LogInfo(l.timestamp(), l.service(), l.message()))
                            .toList()
                        : null;

                return new ActionDebugResponse(
                        base.success(), base.requestId(), base.customerId(),
                        base.eventId(), base.otelTraceId(), base.status(),
                        trace, logs
                );
            });
        });
    }

    // ========================================================================
    // Observability Endpoints
    // ========================================================================

    /**
     * Fetch trace by OTel trace ID.
     * GET /api/bff/traces/{traceId}
     */
    @GetMapping("/traces/{traceId}")
    public Mono<ResponseEntity<TraceInfo>> fetchTrace(@PathVariable String traceId) {
        return Mono.fromFuture(() -> observability.fetchTrace(traceId))
                .map(result -> {
                    if (result.isSuccess()) {
                        return ResponseEntity.ok(TraceInfo.from(result.getOrThrow()));
                    }
                    return ResponseEntity.notFound().<TraceInfo>build();
                });
    }

    /**
     * Fetch logs by requestId.
     * GET /api/bff/logs/{requestId}
     */
    @GetMapping("/logs/{requestId}")
    public Mono<ResponseEntity<List<LogInfo>>> fetchLogs(
            @PathVariable String requestId,
            @RequestParam(defaultValue = "60") int lookbackSeconds
    ) {
        Instant now = Instant.now();
        Instant start = now.minusSeconds(lookbackSeconds);

        return Mono.fromFuture(() -> observability.fetchLogs(requestId, start, now))
                .map(result -> {
                    if (result.isSuccess()) {
                        List<LogInfo> logs = result.getOrThrow().stream()
                                .map(l -> new LogInfo(l.timestamp(), l.service(), l.message()))
                                .toList();
                        return ResponseEntity.ok(logs);
                    }
                    return ResponseEntity.ok(List.<LogInfo>of());
                });
    }

    /**
     * Search recent traces.
     * GET /api/bff/traces?service=counter-application&limit=10
     */
    @GetMapping("/traces")
    public Mono<ResponseEntity<List<TraceInfo>>> searchTraces(
            @RequestParam(defaultValue = "counter-application") String service,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "60") int lookbackSeconds
    ) {
        return Mono.fromFuture(() ->
                observability.searchTraces(service, limit, Duration.ofSeconds(lookbackSeconds)))
                .map(result -> {
                    if (result.isSuccess()) {
                        List<TraceInfo> traces = result.getOrThrow().stream()
                                .map(TraceInfo::from)
                                .toList();
                        return ResponseEntity.ok(traces);
                    }
                    return ResponseEntity.ok(List.<TraceInfo>of());
                });
    }

    /**
     * Get observability status.
     * GET /api/bff/observability/status
     */
    @GetMapping("/observability/status")
    public ResponseEntity<Map<String, Object>> observabilityStatus() {
        return ResponseEntity.ok(Map.of(
                "jaegerUrl", jaegerUrl,
                "lokiUrl", lokiUrl,
                "status", "configured",
                "endpoints", Map.of(
                        "traces", "/api/bff/traces/{traceId}",
                        "logs", "/api/bff/logs/{requestId}",
                        "search", "/api/bff/traces?service=<name>&limit=<n>"
                )
        ));
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    public record ActionDebugResponse(
            boolean success,
            String requestId,
            String customerId,
            String eventId,
            String otelTraceId,
            String status,
            TraceInfo trace,
            List<LogInfo> logs
    ) {}

    public record TraceInfo(
            String traceId,
            int spanCount,
            long durationMs,
            List<SpanInfo> spans
    ) {
        public static TraceInfo from(TraceData trace) {
            return new TraceInfo(
                    trace.traceId(),
                    trace.spans().size(),
                    trace.durationMs(),
                    trace.spans().stream()
                            .map(s -> new SpanInfo(s.spanId(), s.operationName(), s.durationMs()))
                            .toList()
            );
        }
    }

    public record SpanInfo(String spanId, String operationName, long durationMs) {}

    public record LogInfo(long timestamp, String service, String message) {}
}
