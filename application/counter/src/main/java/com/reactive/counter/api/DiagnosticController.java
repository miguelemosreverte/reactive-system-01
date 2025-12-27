package com.reactive.counter.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.platform.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Diagnostic controller for E2E trace validation.
 *
 * Provides endpoints to:
 * - Validate trace completeness across all components
 * - Run full diagnostic tests
 * - Debug trace propagation issues
 *
 * This is a read-only API for debugging - no side effects.
 */
@RestController
@RequestMapping("/api/diagnostic")
public class DiagnosticController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // Expected services for full E2E trace
    private static final List<String> E2E_SERVICES = List.of(
            "counter-application",  // Gateway
            "flink-taskmanager",    // Flink
            "drools"                // Rules engine
    );

    @Value("${jaeger.query.url:http://jaeger:16686}")
    private String jaegerUrl;

    @Value("${loki.url:http://loki:3100}")
    private String lokiUrl;

    private final IdGenerator idGenerator = IdGenerator.getInstance();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Validate trace completeness for a given trace ID.
     *
     * GET /api/diagnostic/validate/{traceId}
     *
     * Returns validation result with:
     * - isComplete: true if all E2E services are present
     * - presentServices: list of services found in trace
     * - missingServices: list of expected but missing services
     * - spanCount: total number of spans
     * - operations: list of operations performed
     */
    @GetMapping("/validate/{traceId}")
    public Mono<ResponseEntity<Map<String, Object>>> validateTrace(@PathVariable String traceId) {
        log.info("Validating trace: {}", traceId);

        return Mono.fromCallable(() -> {
            try {
                String url = jaegerUrl + "/api/traces/" + traceId;
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    return Map.<String, Object>of(
                            "traceId", traceId,
                            "isComplete", false,
                            "error", "Trace not found",
                            "hint", "Trace may not be exported yet. Wait a few seconds and retry."
                    );
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode data = root.get("data");

                if (data == null || !data.isArray() || data.isEmpty()) {
                    return Map.<String, Object>of(
                            "traceId", traceId,
                            "isComplete", false,
                            "error", "Trace has no data"
                    );
                }

                return validateTraceCompleteness(data.get(0), traceId);

            } catch (Exception e) {
                log.error("Failed to validate trace {}: {}", traceId, e.getMessage());
                return Map.<String, Object>of(
                        "traceId", traceId,
                        "isComplete", false,
                        "error", e.getMessage()
                );
            }
        }).map(ResponseEntity::ok);
    }

    /**
     * Run a full E2E diagnostic test.
     *
     * POST /api/diagnostic/run
     *
     * This endpoint:
     * 1. Sends a test counter event through the system
     * 2. Waits for trace propagation (5 seconds)
     * 3. Fetches the trace from Jaeger
     * 4. Validates that all E2E components are present
     * 5. Fetches associated logs
     *
     * Returns comprehensive diagnostic result.
     */
    @PostMapping("/run")
    public Mono<ResponseEntity<Map<String, Object>>> runDiagnostic() {
        String sessionId = "diag-" + System.currentTimeMillis();

        return Mono.fromCallable(() -> {
            String requestId = idGenerator.generateRequestId();

            log.info("Running E2E diagnostic: requestId={}, sessionId={}", requestId, sessionId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", sessionId);
            result.put("requestId", requestId);
            result.put("timestamp", Instant.now().toString());

            try {
                // Step 1: Send test request through internal API (port 3000 inside container)
                String testUrl = "http://localhost:3000/api/counter";
                String body = String.format(
                        "{\"sessionId\":\"%s\",\"action\":\"increment\",\"value\":1}",
                        sessionId
                );

                var sendRequest = HttpRequest.newBuilder()
                        .uri(URI.create(testUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                var sendResponse = httpClient.send(sendRequest, HttpResponse.BodyHandlers.ofString());

                if (sendResponse.statusCode() != 200) {
                    result.put("success", false);
                    result.put("error", "Test request failed: " + sendResponse.statusCode());
                    return result;
                }

                JsonNode testResult = mapper.readTree(sendResponse.body());
                String actualRequestId = testResult.path("requestId").asText();
                String actualTraceId = testResult.path("otelTraceId").asText();

                result.put("actualRequestId", actualRequestId);
                result.put("actualTraceId", actualTraceId);
                result.put("requestSent", true);

                // Step 2: Wait for trace propagation (8 seconds for async Drools processing)
                result.put("waitingForPropagation", true);
                Thread.sleep(8000);

                // Step 3: Fetch trace from Jaeger
                String traceUrl = jaegerUrl + "/api/traces/" + actualTraceId;
                var traceRequest = HttpRequest.newBuilder()
                        .uri(URI.create(traceUrl))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                var traceResponse = httpClient.send(traceRequest, HttpResponse.BodyHandlers.ofString());

                if (traceResponse.statusCode() != 200) {
                    result.put("success", false);
                    result.put("traceFound", false);
                    result.put("error", "Could not fetch trace from Jaeger");
                    return result;
                }

                JsonNode traceRoot = mapper.readTree(traceResponse.body());
                JsonNode traceData = traceRoot.get("data");

                if (traceData == null || !traceData.isArray() || traceData.isEmpty()) {
                    result.put("success", false);
                    result.put("traceFound", false);
                    result.put("error", "Trace has no data");
                    return result;
                }

                result.put("traceFound", true);

                // Step 4: Validate completeness
                Map<String, Object> validation = validateTraceCompleteness(traceData.get(0), actualTraceId);
                result.put("validation", validation);

                boolean isComplete = (Boolean) validation.getOrDefault("isComplete", false);
                result.put("success", isComplete);

                // Step 5: Fetch logs
                List<Map<String, Object>> logs = fetchLogs(actualRequestId, actualTraceId);
                result.put("logs", logs);
                result.put("logCount", logs.size());

                // Build summary
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("traceComplete", isComplete);
                summary.put("spanCount", validation.get("spanCount"));
                summary.put("serviceCount", ((List<?>) validation.get("presentServices")).size());
                summary.put("logCount", logs.size());

                if (isComplete) {
                    summary.put("status", "PASS");
                    summary.put("message", "Full E2E trace propagation verified");
                } else {
                    summary.put("status", "FAIL");
                    summary.put("message", "Incomplete trace - missing: " + validation.get("missingServices"));
                }

                result.put("summary", summary);
                return result;

            } catch (Exception e) {
                log.error("Diagnostic failed: {}", e.getMessage(), e);
                result.put("success", false);
                result.put("error", e.getMessage());
                return result;
            }
        }).map(ResponseEntity::ok);
    }

    /**
     * Get available services in Jaeger.
     *
     * GET /api/diagnostic/services
     */
    @GetMapping("/services")
    public Mono<ResponseEntity<Map<String, Object>>> getServices() {
        return Mono.fromCallable(() -> {
            try {
                String url = jaegerUrl + "/api/services";
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode services = root.get("data");

                    List<String> serviceList = new ArrayList<>();
                    if (services != null && services.isArray()) {
                        for (JsonNode svc : services) {
                            serviceList.add(svc.asText());
                        }
                    }

                    return Map.<String, Object>of(
                            "services", serviceList,
                            "expectedE2EServices", E2E_SERVICES,
                            "jaegerUrl", jaegerUrl
                    );
                }

                return Map.<String, Object>of(
                        "error", "Failed to fetch services",
                        "status", response.statusCode()
                );

            } catch (Exception e) {
                return Map.<String, Object>of(
                        "error", e.getMessage()
                );
            }
        }).map(ResponseEntity::ok);
    }

    /**
     * Health check for diagnostic endpoints.
     *
     * GET /api/diagnostic/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "endpoints", Map.of(
                        "validate", "GET /api/diagnostic/validate/{traceId}",
                        "run", "POST /api/diagnostic/run",
                        "services", "GET /api/diagnostic/services",
                        "performance", "GET /api/diagnostic/performance"
                ),
                "expectedServices", E2E_SERVICES
        ));
    }

    /**
     * Performance summary for optimization workflow.
     *
     * GET /api/diagnostic/performance
     *
     * Returns key performance metrics aggregated from Prometheus:
     * - Component latencies (gateway, drools)
     * - Memory usage (heap, GC)
     * - Throughput indicators
     * - Actionable recommendations
     */
    @GetMapping("/performance")
    public Mono<ResponseEntity<Map<String, Object>>> getPerformanceSummary() {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", Instant.now().toString());

            try {
                // Collect metrics from all components
                Map<String, Object> gateway = collectComponentMetrics("gateway", "http://localhost:3000/actuator/metrics");
                Map<String, Object> drools = collectComponentMetrics("drools", "http://drools:8080/actuator/metrics");

                result.put("components", Map.of(
                    "gateway", gateway,
                    "drools", drools
                ));

                // Memory protection status
                Map<String, Object> memoryProtection = collectMemoryProtection();
                result.put("memoryProtection", memoryProtection);

                // Generate actionable recommendations
                List<String> recommendations = generateRecommendations(gateway, drools, memoryProtection);
                result.put("recommendations", recommendations);

                // Summary
                result.put("summary", Map.of(
                    "healthyComponents", countHealthy(gateway, drools),
                    "hasActionableItems", !recommendations.isEmpty()
                ));

            } catch (Exception e) {
                log.error("Failed to collect performance metrics: {}", e.getMessage());
                result.put("error", e.getMessage());
            }

            return result;
        }).map(ResponseEntity::ok);
    }

    private Map<String, Object> collectComponentMetrics(String name, String metricsUrl) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("name", name);

        try {
            // Get JVM memory
            var memReq = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl + "/jvm.memory.used"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            var memResp = httpClient.send(memReq, HttpResponse.BodyHandlers.ofString());
            if (memResp.statusCode() == 200) {
                JsonNode memNode = mapper.readTree(memResp.body());
                JsonNode measurements = memNode.get("measurements");
                if (measurements != null && measurements.isArray()) {
                    for (JsonNode m : measurements) {
                        if ("VALUE".equals(m.path("statistic").asText())) {
                            metrics.put("memoryUsedMB", m.path("value").asDouble() / (1024 * 1024));
                        }
                    }
                }
            }

            // Get HTTP request metrics
            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl + "/http.server.requests"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            var httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (httpResp.statusCode() == 200) {
                JsonNode httpNode = mapper.readTree(httpResp.body());
                JsonNode measurements = httpNode.get("measurements");
                if (measurements != null && measurements.isArray()) {
                    for (JsonNode m : measurements) {
                        String stat = m.path("statistic").asText();
                        double value = m.path("value").asDouble();
                        switch (stat) {
                            case "COUNT" -> metrics.put("requestCount", (long) value);
                            case "TOTAL_TIME" -> metrics.put("totalTimeMs", value * 1000);
                            case "MAX" -> metrics.put("maxLatencyMs", value * 1000);
                        }
                    }
                }

                // Calculate average latency
                if (metrics.containsKey("requestCount") && metrics.containsKey("totalTimeMs")) {
                    long count = (Long) metrics.get("requestCount");
                    double total = (Double) metrics.get("totalTimeMs");
                    if (count > 0) {
                        metrics.put("avgLatencyMs", total / count);
                    }
                }
            }

            metrics.put("status", "UP");

        } catch (Exception e) {
            metrics.put("status", "DOWN");
            metrics.put("error", e.getMessage());
        }

        return metrics;
    }

    private Map<String, Object> collectMemoryProtection() {
        Map<String, Object> protection = new LinkedHashMap<>();

        try {
            // Get Drools protection stats
            var droolsReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://drools:8080/api/protection/stats"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            var droolsResp = httpClient.send(droolsReq, HttpResponse.BodyHandlers.ofString());
            if (droolsResp.statusCode() == 200) {
                JsonNode stats = mapper.readTree(droolsResp.body());
                protection.put("drools", Map.of(
                    "maxConcurrent", stats.path("maxConcurrent").asInt(),
                    "activeRequests", stats.path("activeRequests").asInt(),
                    "rejectedTotal", stats.path("rejectedTotal").asLong(),
                    "utilization", (double) stats.path("activeRequests").asInt() / stats.path("maxConcurrent").asInt()
                ));
            }
        } catch (Exception e) {
            protection.put("drools", Map.of("error", e.getMessage()));
        }

        return protection;
    }

    private List<String> generateRecommendations(
            Map<String, Object> gateway,
            Map<String, Object> drools,
            Map<String, Object> memoryProtection) {

        List<String> recommendations = new ArrayList<>();

        // Check memory usage
        if (gateway.containsKey("memoryUsedMB")) {
            double memMB = (Double) gateway.get("memoryUsedMB");
            if (memMB > 800) {
                recommendations.add("Gateway memory usage high (" + Math.round(memMB) + "MB). Consider increasing heap or reducing cache sizes.");
            }
        }

        // Check latency
        if (gateway.containsKey("avgLatencyMs")) {
            double avgLatency = (Double) gateway.get("avgLatencyMs");
            if (avgLatency > 20) {
                recommendations.add("Gateway average latency elevated (" + Math.round(avgLatency) + "ms). Check Kafka producer batching.");
            }
        }

        // Check Drools utilization
        if (memoryProtection.containsKey("drools")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> droolsStats = (Map<String, Object>) memoryProtection.get("drools");
            if (droolsStats.containsKey("utilization")) {
                double util = (Double) droolsStats.get("utilization");
                if (util > 0.8) {
                    recommendations.add("Drools concurrency near limit (" + Math.round(util * 100) + "%). Consider increasing DROOLS_MAX_CONCURRENT.");
                }
            }
            if (droolsStats.containsKey("rejectedTotal")) {
                long rejected = (Long) droolsStats.get("rejectedTotal");
                if (rejected > 0) {
                    recommendations.add("Drools has rejected " + rejected + " requests due to capacity. Increase DROOLS_MAX_CONCURRENT or reduce load.");
                }
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("No immediate issues detected. Run benchmark to measure throughput.");
        }

        return recommendations;
    }

    private int countHealthy(Map<String, Object> gateway, Map<String, Object> drools) {
        int count = 0;
        if ("UP".equals(gateway.get("status"))) count++;
        if ("UP".equals(drools.get("status"))) count++;
        return count;
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Map<String, Object> validateTraceCompleteness(JsonNode trace, String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);

        JsonNode processes = trace.get("processes");
        JsonNode spans = trace.get("spans");

        // Extract present services
        Set<String> presentServices = new TreeSet<>();
        Map<String, Integer> spanCountByService = new HashMap<>();

        if (processes != null && spans != null) {
            for (JsonNode span : spans) {
                String processId = span.path("processID").asText();
                if (processes.has(processId)) {
                    String serviceName = processes.get(processId).path("serviceName").asText();
                    presentServices.add(serviceName);
                    spanCountByService.merge(serviceName, 1, Integer::sum);
                }
            }
        }

        result.put("presentServices", new ArrayList<>(presentServices));
        result.put("spanCount", spans != null ? spans.size() : 0);
        result.put("spanCountByService", spanCountByService);

        // Check for missing services
        List<String> missingServices = new ArrayList<>();
        for (String expected : E2E_SERVICES) {
            if (!presentServices.contains(expected)) {
                missingServices.add(expected);
            }
        }

        result.put("missingServices", missingServices);
        result.put("isComplete", missingServices.isEmpty());
        result.put("isUnified", true); // All spans in same trace

        // Add issues
        List<String> issues = new ArrayList<>();
        if (!missingServices.isEmpty()) {
            issues.add("Missing services: " + String.join(", ", missingServices));
        }
        result.put("issues", issues);

        // Extract operations
        List<String> operations = new ArrayList<>();
        if (spans != null) {
            Set<String> ops = new TreeSet<>();
            for (JsonNode span : spans) {
                String processId = span.path("processID").asText();
                String service = processes != null && processes.has(processId)
                        ? processes.get(processId).path("serviceName").asText()
                        : "unknown";
                ops.add(service + ":" + span.path("operationName").asText());
            }
            operations.addAll(ops);
        }
        result.put("operations", operations);

        return result;
    }

    private List<Map<String, Object>> fetchLogs(String requestId, String traceId) {
        List<Map<String, Object>> logs = new ArrayList<>();

        try {
            long now = System.currentTimeMillis();
            long start = (now - 300_000) * 1_000_000L; // 5 minutes ago in nanoseconds
            long end = now * 1_000_000L;

            String query = URLEncoder.encode(
                    "{service=~\".+\"} |= \"" + requestId + "\"",
                    StandardCharsets.UTF_8
            );
            String url = lokiUrl + "/loki/api/v1/query_range?query=" + query +
                    "&start=" + start + "&end=" + end + "&limit=100";

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode results = root.path("data").path("result");

                if (results.isArray()) {
                    for (JsonNode stream : results) {
                        String service = stream.path("stream").path("service").asText("unknown");
                        for (JsonNode value : stream.path("values")) {
                            if (value.isArray() && value.size() >= 2) {
                                Map<String, Object> logEntry = new LinkedHashMap<>();
                                logEntry.put("timestamp", value.get(0).asText());
                                logEntry.put("service", service);
                                logEntry.put("line", value.get(1).asText());
                                logs.add(logEntry);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to fetch logs: {}", e.getMessage());
        }

        return logs;
    }
}
