package com.reactive.platform.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.platform.serialization.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Client for querying observability backends (Jaeger, Loki).
 *
 * Provides unified API for fetching traces and logs by trace ID.
 */
public class ObservabilityClient {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String jaegerUrl;
    private final String lokiUrl;

    private ObservabilityClient(String jaegerUrl, String lokiUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
        this.jaegerUrl = jaegerUrl;
        this.lokiUrl = lokiUrl;
    }

    public static ObservabilityClient create(String jaegerUrl, String lokiUrl) {
        return new ObservabilityClient(jaegerUrl, lokiUrl);
    }

    public static ObservabilityClient fromEnvironment() {
        String jaeger = System.getenv().getOrDefault("JAEGER_QUERY_URL", "http://jaeger:16686");
        String loki = System.getenv().getOrDefault("LOKI_URL", "http://loki:3100");
        return create(jaeger, loki);
    }

    // ========================================================================
    // Jaeger - Trace queries
    // ========================================================================

    /**
     * Fetch a trace by ID.
     */
    public CompletableFuture<Result<TraceData>> fetchTrace(String traceId) {
        String url = jaegerUrl + "/api/traces/" + traceId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Result.<TraceData>failure("Trace not found: " + traceId);
                    }
                    return parseTraceResponse(response.body(), traceId);
                })
                .exceptionally(e -> Result.failure(e));
    }

    /**
     * Search for traces by service name.
     */
    public CompletableFuture<Result<List<TraceData>>> searchTraces(
            String service,
            int limit,
            Duration lookback
    ) {
        String url = String.format(
                "%s/api/traces?service=%s&limit=%d&lookback=%dms",
                jaegerUrl, service, limit, lookback.toMillis()
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Result.<List<TraceData>>failure("Search failed");
                    }
                    return parseTracesResponse(response.body());
                })
                .exceptionally(e -> Result.failure(e));
    }

    // ========================================================================
    // Loki - Log queries
    // ========================================================================

    /**
     * Fetch logs for a trace ID.
     */
    public CompletableFuture<Result<List<LogEntry>>> fetchLogs(
            String traceId,
            Instant start,
            Instant end
    ) {
        // Try label filter first
        String query = String.format("{traceId=\"%s\"}", traceId);
        return queryLoki(query, start, end)
                .thenCompose(result -> {
                    if (result.isSuccess() && !result.getOrThrow().isEmpty()) {
                        return CompletableFuture.completedFuture(result);
                    }
                    // Fallback to line filter
                    String fallbackQuery = String.format("{service=~\".+\"} |= \"%s\"", traceId);
                    return queryLoki(fallbackQuery, start, end);
                });
    }

    /**
     * Query Loki with LogQL.
     */
    public CompletableFuture<Result<List<LogEntry>>> queryLoki(
            String query,
            Instant start,
            Instant end
    ) {
        String url = String.format(
                "%s/loki/api/v1/query_range?query=%s&start=%d&end=%d&limit=1000",
                lokiUrl,
                java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8),
                start.toEpochMilli() * 1_000_000,
                end.toEpochMilli() * 1_000_000
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return Result.<List<LogEntry>>failure("Loki query failed");
                    }
                    return parseLogsResponse(response.body());
                })
                .exceptionally(e -> Result.failure(e));
    }

    // ========================================================================
    // Parsing
    // ========================================================================

    private Result<TraceData> parseTraceResponse(String json, String traceId) {
        return Result.of(() -> {
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.path("data");

            if (!data.isArray() || data.isEmpty()) {
                throw new RuntimeException("No trace data found");
            }

            JsonNode trace = data.get(0);
            List<SpanData> spans = new ArrayList<>();

            JsonNode spansNode = trace.path("spans");
            for (JsonNode spanNode : spansNode) {
                spans.add(new SpanData(
                        spanNode.path("spanID").asText(),
                        spanNode.path("operationName").asText(),
                        spanNode.path("startTime").asLong(),
                        spanNode.path("duration").asLong(),
                        spanNode.path("processID").asText()
                ));
            }

            return new TraceData(traceId, spans);
        });
    }

    private Result<List<TraceData>> parseTracesResponse(String json) {
        return Result.of(() -> {
            JsonNode root = mapper.readTree(json);
            JsonNode data = root.path("data");

            List<TraceData> traces = new ArrayList<>();
            for (JsonNode trace : data) {
                String traceId = trace.path("traceID").asText();
                List<SpanData> spans = new ArrayList<>();

                for (JsonNode spanNode : trace.path("spans")) {
                    spans.add(new SpanData(
                            spanNode.path("spanID").asText(),
                            spanNode.path("operationName").asText(),
                            spanNode.path("startTime").asLong(),
                            spanNode.path("duration").asLong(),
                            spanNode.path("processID").asText()
                    ));
                }

                traces.add(new TraceData(traceId, spans));
            }

            return traces;
        });
    }

    private Result<List<LogEntry>> parseLogsResponse(String json) {
        return Result.of(() -> {
            JsonNode root = mapper.readTree(json);
            JsonNode result = root.path("data").path("result");

            List<LogEntry> logs = new ArrayList<>();

            for (JsonNode stream : result) {
                JsonNode labels = stream.path("stream");
                String service = labels.path("service").asText("");

                for (JsonNode value : stream.path("values")) {
                    long timestamp = Long.parseLong(value.get(0).asText()) / 1_000_000;
                    String message = value.get(1).asText();
                    logs.add(new LogEntry(timestamp, service, message));
                }
            }

            logs.sort((a, b) -> Long.compare(a.timestamp(), b.timestamp()));
            return logs;
        });
    }

    // ========================================================================
    // Data classes
    // ========================================================================

    public record TraceData(String traceId, List<SpanData> spans) {
        public long durationMs() {
            if (spans.isEmpty()) return 0;
            long minStart = spans.stream().mapToLong(SpanData::startTime).min().orElse(0);
            long maxEnd = spans.stream().mapToLong(s -> s.startTime() + s.duration()).max().orElse(0);
            return (maxEnd - minStart) / 1000;
        }
    }

    public record SpanData(
            String spanId,
            String operationName,
            long startTime,
            long duration,
            String processId
    ) {
        public long durationMs() {
            return duration / 1000;
        }
    }

    public record LogEntry(long timestamp, String service, String message) {}
}
