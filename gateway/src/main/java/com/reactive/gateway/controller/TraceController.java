package com.reactive.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
 * Debug endpoint for trace inspection and diagnostics.
 *
 * Provides read-only access to distributed traces and logs for debugging
 * without executing any side effects.
 *
 * Endpoints:
 * - GET /api/debug/trace/{traceId} - Fetch trace by OTel trace ID
 * - GET /api/debug/request/{requestId} - Search trace by requestId tag
 * - GET /api/debug/validate/{traceId} - Validate trace completeness
 * - GET /api/debug/diagnose - Run full E2E diagnostic
 */
@RestController
@RequestMapping("/api/debug")
@Slf4j
public class TraceController {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${observability.jaeger.url:http://jaeger:16686}")
    private String jaegerUrl;

    @Value("${observability.loki.url:http://loki:3100}")
    private String lokiUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Expected services for E2E trace
    private static final List<String> E2E_SERVICES = List.of(
            "counter-application", "flink-taskmanager", "drools"
    );

    /**
     * Fetch a trace by its OTel trace ID.
     * This is a read-only operation - no side effects.
     */
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<?> getTrace(@PathVariable String traceId) {
        log.info("Fetching trace: {}", traceId);

        try {
            String url = jaegerUrl + "/api/traces/" + traceId;
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Trace not found",
                        "traceId", traceId,
                        "hint", "Trace may not be exported yet. Wait a few seconds and retry."
                ));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.get("data");

            if (data == null || !data.isArray() || data.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of(
                        "error", "Trace has no data",
                        "traceId", traceId
                ));
            }

            // Parse and enrich the trace
            JsonNode trace = data.get(0);
            ObjectNode result = buildTraceResponse(trace);
            result.put("traceId", traceId);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Failed to fetch trace {}: {}", traceId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch trace",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Search for a trace by requestId.
     * Searches across counter-application and flink-taskmanager services.
     */
    @GetMapping("/request/{requestId}")
    public ResponseEntity<?> getByRequestId(@PathVariable String requestId) {
        log.info("Searching trace by requestId: {}", requestId);

        String[] services = {"counter-application", "flink-taskmanager"};

        for (String service : services) {
            try {
                String tags = URLEncoder.encode("{\"requestId\":\"" + requestId + "\"}", StandardCharsets.UTF_8);
                String url = jaegerUrl + "/api/traces?service=" + service +
                        "&tags=" + tags + "&limit=1&lookback=1h";

                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode data = root.get("data");

                    if (data != null && data.isArray() && !data.isEmpty()) {
                        JsonNode trace = data.get(0);
                        String traceId = trace.path("traceID").asText();

                        ObjectNode result = buildTraceResponse(trace);
                        result.put("requestId", requestId);
                        result.put("traceId", traceId);
                        result.put("foundInService", service);

                        // Also fetch logs
                        ArrayNode logs = fetchLogs(requestId, traceId);
                        result.set("logs", logs);

                        return ResponseEntity.ok(result);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to search in {}: {}", service, e.getMessage());
            }
        }

        return ResponseEntity.status(404).body(Map.of(
                "error", "No trace found for requestId",
                "requestId", requestId,
                "hint", "Ensure the request was processed and traces were exported."
        ));
    }

    /**
     * Validate trace completeness - checks if all E2E components are present.
     */
    @GetMapping("/validate/{traceId}")
    public ResponseEntity<?> validateTrace(@PathVariable String traceId) {
        log.info("Validating trace: {}", traceId);

        try {
            String url = jaegerUrl + "/api/traces/" + traceId;
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ResponseEntity.ok(Map.of(
                        "traceId", traceId,
                        "isComplete", false,
                        "isValid", false,
                        "error", "Trace not found"
                ));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.get("data");

            if (data == null || !data.isArray() || data.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "traceId", traceId,
                        "isComplete", false,
                        "isValid", false,
                        "error", "Trace has no data"
                ));
            }

            JsonNode trace = data.get(0);
            return ResponseEntity.ok(validateTraceCompleteness(trace, traceId));

        } catch (Exception e) {
            log.error("Failed to validate trace {}: {}", traceId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Validation failed",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Run a full E2E diagnostic - sends a test request and validates the trace.
     * This is the main diagnostic endpoint.
     */
    @PostMapping("/diagnose")
    public ResponseEntity<?> runDiagnostic() {
        String sessionId = "diag-" + System.currentTimeMillis();
        log.info("Running E2E diagnostic with session: {}", sessionId);

        ObjectNode result = mapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("timestamp", Instant.now().toString());

        try {
            // Step 1: Send test request (internal call to our own counter endpoint)
            String testUrl = "http://localhost:8080/api/counter";
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
                return ResponseEntity.ok(result);
            }

            JsonNode testResult = mapper.readTree(sendResponse.body());
            String requestId = testResult.path("requestId").asText();
            String otelTraceId = testResult.path("otelTraceId").asText();

            result.put("requestId", requestId);
            result.put("otelTraceId", otelTraceId);

            // Step 2: Wait for trace propagation
            result.put("waitingForPropagation", true);
            Thread.sleep(5000); // Wait 5 seconds for full propagation

            // Step 3: Fetch and validate trace
            String traceUrl = jaegerUrl + "/api/traces/" + otelTraceId;
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
                return ResponseEntity.ok(result);
            }

            JsonNode traceRoot = mapper.readTree(traceResponse.body());
            JsonNode traceData = traceRoot.get("data");

            if (traceData == null || !traceData.isArray() || traceData.isEmpty()) {
                result.put("success", false);
                result.put("traceFound", false);
                result.put("error", "Trace has no data");
                return ResponseEntity.ok(result);
            }

            result.put("traceFound", true);

            // Step 4: Validate completeness
            JsonNode trace = traceData.get(0);
            ObjectNode validation = validateTraceCompleteness(trace, otelTraceId);
            result.set("validation", validation);

            boolean isComplete = validation.path("isComplete").asBoolean(false);
            result.put("success", isComplete);

            // Step 5: Fetch logs
            ArrayNode logs = fetchLogs(requestId, otelTraceId);
            result.set("logs", logs);
            result.put("logCount", logs.size());

            // Step 6: Build summary
            ObjectNode summary = mapper.createObjectNode();
            summary.put("traceComplete", isComplete);
            summary.put("spanCount", validation.path("spanCount").asInt(0));
            summary.put("serviceCount", validation.path("presentServices").size());
            summary.put("logCount", logs.size());

            if (isComplete) {
                summary.put("status", "PASS");
                summary.put("message", "Full E2E trace propagation verified");
            } else {
                summary.put("status", "FAIL");
                ArrayNode missing = (ArrayNode) validation.get("missingServices");
                summary.put("message", "Incomplete trace - missing: " +
                        (missing != null ? missing.toString() : "unknown"));
            }

            result.set("summary", summary);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Diagnostic failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Get available services in Jaeger.
     */
    @GetMapping("/services")
    public ResponseEntity<?> getServices() {
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
                return ResponseEntity.ok(Map.of(
                        "services", root.get("data"),
                        "expectedE2EServices", E2E_SERVICES
                ));
            }

            return ResponseEntity.status(response.statusCode()).body(Map.of(
                    "error", "Failed to fetch services"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", e.getMessage()
            ));
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private ObjectNode buildTraceResponse(JsonNode trace) {
        ObjectNode result = mapper.createObjectNode();

        // Extract spans
        ArrayNode spans = mapper.createArrayNode();
        JsonNode spansNode = trace.get("spans");
        JsonNode processes = trace.get("processes");

        if (spansNode != null && spansNode.isArray()) {
            for (JsonNode span : spansNode) {
                ObjectNode spanInfo = mapper.createObjectNode();
                spanInfo.put("spanId", span.path("spanID").asText());
                spanInfo.put("operationName", span.path("operationName").asText());
                spanInfo.put("startTime", span.path("startTime").asLong());
                spanInfo.put("duration", span.path("duration").asLong());

                String processId = span.path("processID").asText();
                if (processes != null && processes.has(processId)) {
                    spanInfo.put("service", processes.get(processId).path("serviceName").asText());
                }

                spans.add(spanInfo);
            }
        }

        result.set("spans", spans);
        result.put("spanCount", spans.size());

        // Extract services
        Set<String> services = new TreeSet<>();
        if (processes != null) {
            processes.fieldNames().forEachRemaining(key -> {
                services.add(processes.get(key).path("serviceName").asText());
            });
        }
        result.set("services", mapper.valueToTree(services));

        return result;
    }

    private ObjectNode validateTraceCompleteness(JsonNode trace, String traceId) {
        ObjectNode result = mapper.createObjectNode();
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

        result.set("presentServices", mapper.valueToTree(presentServices));
        result.put("spanCount", spans != null ? spans.size() : 0);
        result.set("spanCountByService", mapper.valueToTree(spanCountByService));

        // Check for missing services
        List<String> missingServices = new ArrayList<>();
        for (String expected : E2E_SERVICES) {
            if (!presentServices.contains(expected)) {
                missingServices.add(expected);
            }
        }

        result.set("missingServices", mapper.valueToTree(missingServices));
        result.put("isComplete", missingServices.isEmpty());
        result.put("isUnified", true); // All spans in same trace

        // Add issues
        ArrayNode issues = mapper.createArrayNode();
        if (!missingServices.isEmpty()) {
            issues.add("Missing services: " + String.join(", ", missingServices));
        }
        result.set("issues", issues);

        // Extract operations
        ArrayNode operations = mapper.createArrayNode();
        if (spans != null) {
            Set<String> ops = new TreeSet<>();
            for (JsonNode span : spans) {
                String processId = span.path("processID").asText();
                String service = processes != null && processes.has(processId)
                        ? processes.get(processId).path("serviceName").asText()
                        : "unknown";
                ops.add(service + ":" + span.path("operationName").asText());
            }
            ops.forEach(operations::add);
        }
        result.set("operations", operations);

        return result;
    }

    private ArrayNode fetchLogs(String requestId, String traceId) {
        ArrayNode logs = mapper.createArrayNode();

        try {
            long now = System.currentTimeMillis();
            long start = (now - 300_000) * 1_000_000L; // 5 minutes ago in nanoseconds
            long end = now * 1_000_000L;

            // Search for logs by requestId
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
                                ObjectNode logEntry = mapper.createObjectNode();
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
