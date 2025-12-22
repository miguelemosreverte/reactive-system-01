package com.reactive.flink.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.EventTiming;
import com.reactive.flink.model.PreDroolsResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async function to enrich PreDroolsResult with Drools evaluation.
 * Uses Java 11 HttpClient with sendAsync for non-blocking HTTP calls.
 *
 * This allows Flink to process thousands of concurrent Drools calls
 * without blocking, maximizing throughput.
 *
 * Business IDs:
 * - requestId: Correlation ID for request tracking
 * - customerId: Customer/tenant ID for multi-tenancy
 *
 * Note: OpenTelemetry trace propagation is handled automatically.
 */
public class AsyncDroolsEnricher extends RichAsyncFunction<PreDroolsResult, CounterResult> {
    private static final long serialVersionUID = 2L;
    private static final Logger LOG = LoggerFactory.getLogger(AsyncDroolsEnricher.class);

    private final String droolsUrl;

    // Connection pool size
    private static final int MAX_CONNECTIONS = 100;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1000);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(5000);

    private transient HttpClient httpClient;
    private transient ObjectMapper objectMapper;
    private transient Tracer tracer;
    private transient ExecutorService executor;

    // TextMapGetter for extracting trace context from a Map
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }
    };

    public AsyncDroolsEnricher(String droolsUrl) {
        this.droolsUrl = droolsUrl;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        // Create executor for async HTTP calls
        executor = Executors.newFixedThreadPool(MAX_CONNECTIONS);

        // Create async HTTP client with connection pooling
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        objectMapper = new ObjectMapper();
        tracer = GlobalOpenTelemetry.getTracer("flink-async-drools");

        LOG.info("AsyncDroolsEnricher initialized with {} max concurrent connections", MAX_CONNECTIONS);
    }

    @Override
    public void close() throws Exception {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public void asyncInvoke(PreDroolsResult input, ResultFuture<CounterResult> resultFuture) {
        // Track Drools start time
        final long droolsStartAt = System.currentTimeMillis();

        // Extract parent trace context from PreDroolsResult (propagated through Flink)
        Context parentContext = Context.current();
        if (input.getTraceparent() != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", input.getTraceparent());
            if (input.getTracestate() != null) {
                headers.put("tracestate", input.getTracestate());
            }
            parentContext = GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .extract(Context.current(), headers, MAP_GETTER);
        }

        // Create tracing span with parent context from Gateway/Flink
        Span span = tracer.spanBuilder("async.drools.enrich")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", "POST")
                .setAttribute("http.url", droolsUrl + "/api/evaluate")
                .setAttribute("peer.service", "drools")
                .setAttribute("counter.value", input.getCounterValue())
                .setAttribute("requestId", input.getRequestId() != null ? input.getRequestId() : "")
                .setAttribute("customerId", input.getCustomerId() != null ? input.getCustomerId() : "")
                .setAttribute("eventId", input.getEventId() != null ? input.getEventId() : "")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Set MDC for log correlation with business IDs
            if (input.getRequestId() != null) {
                MDC.put("requestId", input.getRequestId());
            }
            if (input.getCustomerId() != null) {
                MDC.put("customerId", input.getCustomerId());
            }

            LOG.info("Calling Drools for evaluation: sessionId={}, value={}, customerId={}",
                    input.getSessionId(), input.getCounterValue(), input.getCustomerId());

            // Build request body with business IDs
            String requestId = input.getRequestId() != null ? input.getRequestId() : "";
            String customerId = input.getCustomerId() != null ? input.getCustomerId() : "";
            String eventId = input.getEventId() != null ? input.getEventId() : "";
            String requestBody = String.format(
                    "{\"value\":%d,\"requestId\":\"%s\",\"customerId\":\"%s\",\"eventId\":\"%s\",\"sessionId\":\"%s\"}",
                    input.getCounterValue(), requestId, customerId, eventId, input.getSessionId());

            // Build request with W3C trace context headers (injected by OTel)
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(droolsUrl + "/api/evaluate"))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT);

            // Inject trace context headers
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(
                    Context.current(),
                    requestBuilder,
                    (carrier, key, value) -> carrier.header(key, value)
            );

            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            // Send async request
            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // Handle response asynchronously
            future.thenAccept(response -> {
                long droolsEndAt = System.currentTimeMillis();
                try {
                    String alert = "NONE";
                    String message = "No rules matched";

                    if (response.statusCode() == 200) {
                        JsonNode json = objectMapper.readTree(response.body());
                        alert = json.path("alert").asText("NONE");
                        message = json.path("message").asText("No message");
                        span.setAttribute("http.status_code", response.statusCode());
                        span.setStatus(StatusCode.OK);
                    } else {
                        LOG.warn("Drools returned status {}: {}", response.statusCode(), response.body());
                        span.setAttribute("http.status_code", response.statusCode());
                        span.setStatus(StatusCode.ERROR, "Non-200 response");
                    }

                    // Record latency
                    long latency = droolsEndAt - input.getArrivalTime();
                    span.setAttribute("total.latency_ms", latency);
                    span.setAttribute("drools.latency_ms", droolsEndAt - droolsStartAt);

                    // Copy and update timing
                    EventTiming timing = EventTiming.copyFrom(input.getTiming());
                    timing.setDroolsStartAt(droolsStartAt);
                    timing.setDroolsEndAt(droolsEndAt);

                    // Emit result with timing
                    CounterResult result = new CounterResult(
                            input.getSessionId(),
                            input.getCounterValue(),
                            alert,
                            message,
                            input.getRequestId(),
                            input.getCustomerId(),
                            input.getEventId(),
                            timing
                    );
                    resultFuture.complete(Collections.singletonList(result));

                } catch (Exception e) {
                    LOG.error("Error parsing Drools response", e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());

                    // Copy timing even on error
                    EventTiming timing = EventTiming.copyFrom(input.getTiming());
                    timing.setDroolsStartAt(droolsStartAt);
                    timing.setDroolsEndAt(droolsEndAt);

                    // Return result with default alert on error
                    CounterResult result = new CounterResult(
                            input.getSessionId(),
                            input.getCounterValue(),
                            "ERROR",
                            "Drools call failed: " + e.getMessage(),
                            input.getRequestId(),
                            input.getCustomerId(),
                            input.getEventId(),
                            timing
                    );
                    resultFuture.complete(Collections.singletonList(result));
                } finally {
                    span.end();
                }
            }).exceptionally(throwable -> {
                long droolsEndAt = System.currentTimeMillis();
                LOG.error("Async Drools call failed", throwable);
                span.recordException(throwable);
                span.setStatus(StatusCode.ERROR, throwable.getMessage());
                span.end();

                // Copy timing even on error
                EventTiming timing = EventTiming.copyFrom(input.getTiming());
                timing.setDroolsStartAt(droolsStartAt);
                timing.setDroolsEndAt(droolsEndAt);

                // Return result with error alert
                CounterResult result = new CounterResult(
                        input.getSessionId(),
                        input.getCounterValue(),
                        "ERROR",
                        "Async Drools call failed: " + throwable.getMessage(),
                        input.getRequestId(),
                        input.getCustomerId(),
                        input.getEventId(),
                        timing
                );
                resultFuture.complete(Collections.singletonList(result));
                return null;
            });

        } catch (Exception e) {
            long droolsEndAt = System.currentTimeMillis();
            LOG.error("Error invoking async Drools", e);
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.end();

            // Copy timing even on error
            EventTiming timing = EventTiming.copyFrom(input.getTiming());
            timing.setDroolsStartAt(droolsStartAt);
            timing.setDroolsEndAt(droolsEndAt);

            // Return result with error
            CounterResult result = new CounterResult(
                    input.getSessionId(),
                    input.getCounterValue(),
                    "ERROR",
                    "Failed to invoke Drools: " + e.getMessage(),
                    input.getRequestId(),
                    input.getCustomerId(),
                    input.getEventId(),
                    timing
            );
            resultFuture.complete(Collections.singletonList(result));
        }
    }

    @Override
    public void timeout(PreDroolsResult input, ResultFuture<CounterResult> resultFuture) {
        LOG.warn("Async Drools call timed out for session {}", input.getSessionId());

        // Copy timing with timeout indicator
        long now = System.currentTimeMillis();
        EventTiming timing = EventTiming.copyFrom(input.getTiming());
        timing.setDroolsStartAt(input.getArrivalTime());
        timing.setDroolsEndAt(now);

        // Return result with timeout alert
        CounterResult result = new CounterResult(
                input.getSessionId(),
                input.getCounterValue(),
                "TIMEOUT",
                "Drools evaluation timed out",
                input.getRequestId(),
                input.getCustomerId(),
                input.getEventId(),
                timing
        );
        resultFuture.complete(Collections.singletonList(result));
    }
}
