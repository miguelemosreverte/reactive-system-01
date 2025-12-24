package com.reactive.flink.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.diagnostic.FlinkDiagnosticCollector;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.EventTiming;
import com.reactive.flink.model.PreDroolsResult;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async function to enrich PreDroolsResult with Drools evaluation.
 *
 * Uses Log.asyncTracedProcess for automatic business ID extraction.
 * Duplicated result creation logic extracted to helper method.
 */
public class AsyncDroolsEnricher extends RichAsyncFunction<PreDroolsResult, CounterResult> {
    private static final long serialVersionUID = 4L;
    private static final Logger LOG = LoggerFactory.getLogger(AsyncDroolsEnricher.class);

    private final String droolsUrl;

    private static final int MAX_CONNECTIONS = 200;  // Matches half of async capacity for balanced queueing
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1000);  // Connection timeout
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(5000);  // Request timeout

    private transient HttpClient httpClient;
    private transient ObjectMapper objectMapper;
    private transient ExecutorService executor;

    public AsyncDroolsEnricher(String droolsUrl) {
        this.droolsUrl = droolsUrl;
    }

    @Override
    public void open(Configuration parameters) {
        executor = Executors.newFixedThreadPool(MAX_CONNECTIONS);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)  // HTTP/1.1 for compatibility
                .build();
        objectMapper = new ObjectMapper();
        LOG.info("AsyncDroolsEnricher initialized: {} connections, HTTP/1.1, timeout={}ms",
                MAX_CONNECTIONS, REQUEST_TIMEOUT.toMillis());
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public void asyncInvoke(PreDroolsResult input, ResultFuture<CounterResult> resultFuture) {
        long droolsStartAt = System.currentTimeMillis();

        // Track async queue depth for diagnostics
        FlinkDiagnosticCollector.recordAsyncRequestStart();

        // Auto-extracts business IDs and connects to parent trace context from TracedMessage
        SpanHandle span = Log.asyncTracedConsume("async.drools.enrich", input, Log.SpanType.PRODUCER);
        span.attr("http.method", "POST");
        span.attr("http.url", droolsUrl + "/api/evaluate");
        span.attr("peer.service", "drools");
        span.attr("counter.value", input.counterValue());

        try {
            LOG.debug("Calling Drools: session={}, value={}", input.sessionId(), input.counterValue());

            HttpRequest request = buildRequest(input, span.headers());

            // Run HTTP call within span's context so OTel agent picks up trace context
            span.runInContext(() -> {
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(response -> handleResponse(response, input, droolsStartAt, span, resultFuture))
                        .exceptionally(error -> {
                            handleError(error, input, droolsStartAt, span, resultFuture);
                            return null;
                        });
                return null;
            });

        } catch (Exception e) {
            handleError(e, input, droolsStartAt, span, resultFuture);
        }
    }

    @Override
    public void timeout(PreDroolsResult input, ResultFuture<CounterResult> resultFuture) {
        LOG.warn("Drools call timed out: session={}", input.sessionId());
        FlinkDiagnosticCollector.recordAsyncRequestComplete();
        FlinkDiagnosticCollector.recordAsyncTimeout();
        complete(resultFuture, buildResult(input, input.arrivalTime(), "TIMEOUT", "Drools evaluation timed out"));
    }

    // ========================================================================
    // Helpers - Extract duplicated logic
    // ========================================================================

    private HttpRequest buildRequest(PreDroolsResult input, Map<String, String> traceHeaders) {
        String body = String.format(
                "{\"value\":%d,\"requestId\":\"%s\",\"customerId\":\"%s\",\"eventId\":\"%s\",\"sessionId\":\"%s\"}",
                input.counterValue(), input.requestId(), input.customerId(), input.eventId(), input.sessionId());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(droolsUrl + "/api/evaluate"))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);

        traceHeaders.forEach((k, v) -> { if (!v.isEmpty()) builder.header(k, v); });

        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private void handleResponse(HttpResponse<String> response, PreDroolsResult input,
                                long droolsStartAt, SpanHandle span, ResultFuture<CounterResult> resultFuture) {
        // Track async completion for diagnostics
        FlinkDiagnosticCollector.recordAsyncRequestComplete();

        long droolsEndAt = System.currentTimeMillis();
        span.attr("http.status_code", response.statusCode());
        span.attr("drools.latency_ms", droolsEndAt - droolsStartAt);

        try {
            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                String alert = json.path("alert").asText("NONE");
                String message = json.path("message").asText("No message");
                span.success();
                complete(resultFuture, buildResult(input, droolsStartAt, alert, message));
            } else {
                LOG.warn("Drools returned {}: {}", response.statusCode(), response.body());
                span.failure(new RuntimeException("Non-200: " + response.statusCode()));
                FlinkDiagnosticCollector.recordAsyncError();
                complete(resultFuture, buildResult(input, droolsStartAt, "ERROR", "Drools error: " + response.statusCode()));
            }
        } catch (Exception e) {
            LOG.error("Error parsing Drools response", e);
            span.failure(e);
            FlinkDiagnosticCollector.recordAsyncError();
            complete(resultFuture, buildResult(input, droolsStartAt, "ERROR", "Parse error: " + e.getMessage()));
        }
    }

    private void handleError(Throwable error, PreDroolsResult input, long droolsStartAt,
                             SpanHandle span, ResultFuture<CounterResult> resultFuture) {
        LOG.error("Drools call failed", error);
        FlinkDiagnosticCollector.recordAsyncRequestComplete();
        FlinkDiagnosticCollector.recordAsyncError();
        span.failure(error);
        complete(resultFuture, buildResult(input, droolsStartAt, "ERROR", "Call failed: " + error.getMessage()));
    }

    /** Build result with timing - single source of truth for result creation. */
    private CounterResult buildResult(PreDroolsResult input, long droolsStartAt, String alert, String message) {
        EventTiming timing = input.timing().withDroolsTiming(droolsStartAt, System.currentTimeMillis());
        return new CounterResult(
                input.sessionId(), input.counterValue(), alert, message,
                input.requestId(), input.customerId(), input.eventId(), timing);
    }

    private void complete(ResultFuture<CounterResult> future, CounterResult result) {
        future.complete(Collections.singletonList(result));
    }
}
