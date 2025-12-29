package com.reactive.counter.bff;

import com.reactive.counter.api.ActionRequest;
import com.reactive.counter.domain.CounterEvent;
import com.reactive.platform.benchmark.BenchmarkTypes.LogEntry;
import com.reactive.platform.benchmark.BenchmarkTypes.Trace;
import com.reactive.platform.benchmark.ObservabilityFetcher;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.counter.serialization.AvroCounterEventCodec;
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
import java.util.List;
import java.util.Map;

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

    private final IdGenerator idGenerator = IdGenerator.getInstance();

    private KafkaPublisher<CounterEvent> publisher;
    private ObservabilityFetcher observability;

    @PostConstruct
    void init() {
        publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(AvroCounterEventCodec.create())
                .keyExtractor(e -> buildKafkaKey(e.customerId(), e.sessionId())));
        observability = ObservabilityFetcher.withUrls(jaegerUrl, lokiUrl);
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
        return processAction(request, "", debugMode, debugWaitMs);
    }

    private Mono<ResponseEntity<ActionDebugResponse>> processAction(
            ActionRequest request,
            String customerId,
            boolean debugMode,
            long debugWaitMs
    ) {
        return Mono.fromCallable(() -> {
            String sessionId = request.sessionIdOrDefault();
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();

            log.info("BFF: action={}, value={}, debug={}, requestId={}",
                    request.action(), request.value(), debugMode, requestId);

            CounterEvent event = CounterEvent.create(
                    requestId, customerId, eventId, sessionId, request.action(), request.value()
            );
            publisher.publishFireAndForget(event);

            return new ActionDebugResponse(
                    true, requestId, customerId,
                    eventId, "", "accepted", null, null
            );
        })
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
        return Mono.fromCallable(() -> {
            // Fetch trace synchronously
            TraceInfo trace = observability.fetchTraceByOtelId(base.otelTraceId())
                    .map(TraceInfo::from)
                    .orElse(null);

            // Fetch logs synchronously
            Instant now = Instant.now();
            List<LogEntry> logEntries = observability.fetchLogs(base.requestId(), now.minusSeconds(60), now);
            List<LogInfo> logs = logEntries.stream()
                    .map(l -> new LogInfo(l.timestamp(), l.labels().getOrDefault("service", "unknown"), l.line()))
                    .toList();

            return new ActionDebugResponse(
                    base.success(), base.requestId(), base.customerId(),
                    base.eventId(), base.otelTraceId(), base.status(),
                    trace, logs.isEmpty() ? null : logs
            );
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
        return Mono.fromCallable(() ->
                observability.fetchTraceByOtelId(traceId)
                        .map(TraceInfo::from)
                        .map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.notFound().build())
        );
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
        return Mono.fromCallable(() -> {
            Instant now = Instant.now();
            Instant start = now.minusSeconds(lookbackSeconds);
            List<LogEntry> entries = observability.fetchLogs(requestId, start, now);
            List<LogInfo> logs = entries.stream()
                    .map(l -> new LogInfo(l.timestamp(), l.labels().getOrDefault("service", "unknown"), l.line()))
                    .toList();
            return ResponseEntity.ok(logs);
        });
    }

    /**
     * Search recent traces by requestId.
     * Note: Direct trace search by service is not supported - use requestId for lookup.
     * GET /api/bff/traces?requestId=xxx
     */
    @GetMapping("/traces")
    public Mono<ResponseEntity<List<TraceInfo>>> searchTraces(
            @RequestParam(required = false) String requestId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return Mono.fromCallable(() -> {
            if (requestId == null || requestId.isEmpty()) {
                return ResponseEntity.ok(List.<TraceInfo>of());
            }
            // Search by requestId tag
            List<TraceInfo> traces = observability.fetchTraceByAppId(requestId)
                    .map(TraceInfo::from)
                    .map(List::of)
                    .orElse(List.of());
            return ResponseEntity.ok(traces);
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
        /**
         * Convert from BenchmarkTypes.Trace to TraceInfo.
         */
        public static TraceInfo from(Trace trace) {
            // Calculate trace duration from spans (max end - min start)
            long minStart = trace.spans().stream().mapToLong(s -> s.startTime()).min().orElse(0);
            long maxEnd = trace.spans().stream().mapToLong(s -> s.startTime() + s.duration()).max().orElse(0);
            long durationMs = (maxEnd - minStart) / 1000; // microseconds to milliseconds

            return new TraceInfo(
                    trace.traceId(),
                    trace.spans().size(),
                    durationMs,
                    trace.spans().stream()
                            .map(s -> new SpanInfo(s.spanId(), s.operationName(), s.duration() / 1000))
                            .toList()
            );
        }
    }

    public record SpanInfo(String spanId, String operationName, long durationMs) {}

    public record LogInfo(String timestamp, String service, String message) {}
}
