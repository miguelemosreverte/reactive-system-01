package com.reactive.platform.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.net.URI;

import static com.reactive.platform.observe.Log.*;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Fetches traces from Jaeger and logs from Loki.
 *
 * Static factory pattern - use create() or withUrls().
 */
public class ObservabilityFetcher {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String jaegerUrl;
    private final String lokiUrl;
    private final HttpClient client;

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static ObservabilityFetcher create() {
        String jaegerUrl = System.getenv().getOrDefault("JAEGER_QUERY_URL", "http://jaeger:16686");
        String lokiUrl = System.getenv().getOrDefault("LOKI_URL", "http://loki:3100");
        return new ObservabilityFetcher(jaegerUrl, lokiUrl);
    }

    public static ObservabilityFetcher withUrls(String jaegerUrl, String lokiUrl) {
        return new ObservabilityFetcher(jaegerUrl, lokiUrl);
    }

    private ObservabilityFetcher(String jaegerUrl, String lokiUrl) {
        this.jaegerUrl = jaegerUrl;
        this.lokiUrl = lokiUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ========================================================================
    // Jaeger Trace Fetching
    // ========================================================================

    /** Fetch trace by OTel trace ID (direct lookup). */
    public Optional<JaegerTrace> fetchTraceByOtelId(String otelTraceId) {
        if (otelTraceId == null || otelTraceId.isEmpty()) {
            return Optional.empty();
        }

        String url = jaegerUrl + "/api/traces/" + otelTraceId;

        // Quick lookup - only 2 attempts since direct lookups are fast
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                sleep(500);
            }

            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    break; // Direct lookup 404 means trace doesn't exist, no need to retry
                }

                JsonNode root = mapper.readTree(response.body());
                JsonNode data = root.get("data");
                if (data == null || !data.isArray() || data.isEmpty()) {
                    break;
                }

                JaegerTrace trace = parseJaegerTrace(data.get(0));
                info("Found trace by otelTraceId={} with {} spans", otelTraceId, trace.spans().size());
                return Optional.of(trace);

            } catch (Exception e) {
                // Trace not found, will retry
            }
        }

        return Optional.empty();
    }

    /** Fetch trace by requestId tag (searches both counter-application and flink-taskmanager). */
    public Optional<JaegerTrace> fetchTraceByAppId(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            return Optional.empty();
        }

        // Try both services - traces are split across gateway and flink
        String[] services = {"flink-taskmanager", "counter-application"};

        for (String service : services) {
            String url = jaegerUrl + "/api/traces?" +
                    "service=" + service +
                    "&tags=" + urlEncode("{\"requestId\":\"" + requestId + "\"}") +
                    "&limit=1" +
                    "&lookback=10m";

            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) {
                    sleep(500L * attempt);
                }

                try {
                    var request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();

                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() != 200) {
                        continue;
                    }

                    JsonNode root = mapper.readTree(response.body());
                    JsonNode data = root.get("data");
                    if (data == null || !data.isArray() || data.isEmpty()) {
                        continue;
                    }

                    JaegerTrace trace = parseJaegerTrace(data.get(0));
                    info("Found trace for requestId={} in service={} (Jaeger traceId={})", requestId, service, trace.traceId());
                    return Optional.of(trace);

                } catch (Exception e) {
                    // Trace not found in this service, will try next
                }
            }
        }

        return Optional.empty();
    }

    private JaegerTrace parseJaegerTrace(JsonNode node) {
        String traceId = node.path("traceID").asText();

        List<JaegerSpan> spans = new ArrayList<>();
        for (JsonNode spanNode : node.path("spans")) {
            spans.add(new JaegerSpan(
                    spanNode.path("traceID").asText(),
                    spanNode.path("spanID").asText(),
                    spanNode.path("operationName").asText(),
                    spanNode.path("startTime").asLong(),
                    spanNode.path("duration").asLong(),
                    spanNode.path("processID").asText(),
                    parseListOfMaps(spanNode.path("tags")),
                    parseListOfMaps(spanNode.path("references"))
            ));
        }

        Map<String, JaegerProcess> processes = new HashMap<>();
        JsonNode processesNode = node.path("processes");
        if (processesNode.isObject()) {
            var fields = processesNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String serviceName = entry.getValue().path("serviceName").asText();
                processes.put(entry.getKey(), new JaegerProcess(serviceName));
            }
        }

        return new JaegerTrace(traceId, spans, processes);
    }

    private List<Map<String, Object>> parseListOfMaps(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        try {
            return mapper.convertValue(node, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ========================================================================
    // Loki Log Fetching
    // ========================================================================

    /** Fetch logs by requestId - searches all services. */
    public List<LokiLogEntry> fetchLogs(String requestId, Instant start, Instant end) {
        if (requestId == null || requestId.isEmpty()) {
            return List.of();
        }

        // Extend time range slightly
        long startNs = start.minusSeconds(60).toEpochMilli() * 1_000_000;
        long endNs = end.plusSeconds(60).toEpochMilli() * 1_000_000;

        // Search ALL services for the requestId
        String query = "{service=~\".+\"} |= \"" + requestId + "\"";
        List<LokiLogEntry> logs = queryLoki(query, startNs, endNs);

        if (!logs.isEmpty()) {
            info("Found {} logs for requestId {}", logs.size(), requestId);
        }

        return logs;
    }

    /** Fetch logs by multiple IDs (requestId and otelTraceId) - searches all services. */
    public List<LokiLogEntry> fetchLogsMulti(String otelTraceId, String requestId, Instant start, Instant end) {
        long startNs = start.minusSeconds(60).toEpochMilli() * 1_000_000;
        long endNs = end.plusSeconds(60).toEpochMilli() * 1_000_000;

        Set<String> seenLines = new HashSet<>();
        List<LokiLogEntry> allLogs = new ArrayList<>();

        // Search ALL services by requestId
        if (requestId != null && !requestId.isEmpty()) {
            String query = "{service=~\".+\"} |= \"" + requestId + "\"";
            for (var logEntry : queryLoki(query, startNs, endNs)) {
                if (seenLines.add(logEntry.line())) {
                    allLogs.add(logEntry);
                }
            }
        }

        // Also search by otelTraceId if provided
        if (otelTraceId != null && !otelTraceId.isEmpty()) {
            String query = "{service=~\".+\"} |= \"" + otelTraceId + "\"";
            for (var logEntry : queryLoki(query, startNs, endNs)) {
                if (seenLines.add(logEntry.line())) {
                    allLogs.add(logEntry);
                }
            }
        }

        if (!allLogs.isEmpty()) {
            info("Found {} total logs (requestId={}, otelTraceId={})", allLogs.size(), requestId, otelTraceId);
        }

        // Sort by timestamp
        allLogs.sort(Comparator.comparing(LokiLogEntry::timestamp));
        return allLogs;
    }

    /** Extract OTel traceId from log entries. */
    public Optional<String> extractTraceIdFromLogs(List<LokiLogEntry> logs) {
        for (LokiLogEntry entry : logs) {
            // Try to extract traceId from parsed fields
            Object traceId = entry.fields().get("traceId");
            if (traceId != null && !traceId.toString().isEmpty() && !traceId.toString().equals("null")) {
                return Optional.of(traceId.toString());
            }
        }
        return Optional.empty();
    }

    private List<LokiLogEntry> queryLoki(String query, long startNs, long endNs) {
        String url = lokiUrl + "/loki/api/v1/query_range?" +
                "query=" + urlEncode(query) +
                "&start=" + startNs +
                "&end=" + endNs +
                "&limit=500";  // Increased limit to capture more logs

        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return List.of();
            }

            JsonNode root = mapper.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                return List.of();
            }

            List<LokiLogEntry> entries = new ArrayList<>();
            for (JsonNode stream : root.path("data").path("result")) {
                Map<String, String> labels = mapper.convertValue(
                        stream.path("stream"),
                        new TypeReference<>() {}
                );

                for (JsonNode value : stream.path("values")) {
                    if (value.isArray() && value.size() >= 2) {
                        String timestamp = value.get(0).asText();
                        String line = value.get(1).asText();

                        Map<String, Object> fields = Map.of();
                        try {
                            fields = mapper.readValue(line, new TypeReference<>() {});
                        } catch (Exception ignored) {}

                        entries.add(new LokiLogEntry(timestamp, line, labels, fields));
                    }
                }
            }

            entries.sort(Comparator.comparing(LokiLogEntry::timestamp));
            return entries;

        } catch (Exception e) {
            warn("Failed to query Loki: {}", e.getMessage());
            return List.of();
        }
    }

    // ========================================================================
    // Combined Fetch
    // ========================================================================

    /** Fetch both trace and logs for a trace ID. */
    public TraceData fetchTraceData(String otelTraceId, String traceId, Instant start, Instant end) {
        var otelId = Optional.ofNullable(otelTraceId).filter(s -> !s.isEmpty());
        var appId = Optional.ofNullable(traceId).filter(s -> !s.isEmpty());

        if (otelId.isEmpty() && appId.isEmpty()) {
            return TraceData.empty();
        }

        // First, fetch logs (we need them to extract traceId if not provided)
        final List<LokiLogEntry> initialLogs = fetchLogsMulti(otelTraceId, traceId, start, end);

        // If no otelTraceId provided, try to extract from logs
        String effectiveOtelTraceId = otelId
                .or(() -> initialLogs.isEmpty() ? Optional.empty() : extractTraceIdFromLogs(initialLogs)
                        .map(id -> { info("Extracted traceId from logs: {}", id); return id; }))
                .orElse("");

        // Fetch trace using OTel trace ID (direct lookup), then fallback to app.traceId
        Optional<JaegerTrace> trace = Optional.ofNullable(effectiveOtelTraceId)
                .filter(s -> !s.isEmpty())
                .flatMap(this::fetchTraceByOtelId)
                .or(() -> appId.flatMap(this::fetchTraceByAppId));

        // If we found the trace but had to extract traceId, also search for additional logs
        List<LokiLogEntry> finalLogs = initialLogs;
        if (trace.isPresent() && !effectiveOtelTraceId.isEmpty() && !effectiveOtelTraceId.equals(otelTraceId)) {
            List<LokiLogEntry> additionalLogs = fetchLogsMulti(effectiveOtelTraceId, "", start, end);
            Set<String> seenLines = new HashSet<>();
            List<LokiLogEntry> allLogs = new ArrayList<>();
            for (var entry : initialLogs) {
                if (seenLines.add(entry.line())) {
                    allLogs.add(entry);
                }
            }
            for (var entry : additionalLogs) {
                if (seenLines.add(entry.line())) {
                    allLogs.add(entry);
                }
            }
            allLogs.sort(Comparator.comparing(LokiLogEntry::timestamp));
            finalLogs = allLogs;
        }

        if (trace.isEmpty() && finalLogs.isEmpty()) {
            return TraceData.empty();
        }

        return new TraceData(trace.orElse(JaegerTrace.empty()), finalLogs);
    }

    /** Enrich sample events with trace and log data. */
    public List<SampleEvent> enrichSampleEvents(List<SampleEvent> events, Instant start, Instant end) {
        List<SampleEvent> enriched = new ArrayList<>();

        for (SampleEvent event : events) {
            if (event.otelTraceId() != null || event.traceId() != null) {
                TraceData data = fetchTraceData(event.otelTraceId(), event.traceId(), start, end);
                enriched.add(event.withTraceData(data));
            } else {
                enriched.add(event);
            }
        }

        return enriched;
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
