package com.reactive.flink.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterProcessor.class);

    private final String droolsUrl;
    private transient ValueState<Integer> counterState;
    private transient CloseableHttpClient httpClient;
    private transient ObjectMapper objectMapper;
    private transient Tracer tracer;

    public CounterProcessor(String droolsUrl) {
        this.droolsUrl = droolsUrl;
    }

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Integer> descriptor = new ValueStateDescriptor<>(
                "counterValue",
                Types.INT
        );
        counterState = getRuntimeContext().getState(descriptor);
        httpClient = HttpClients.createDefault();
        objectMapper = new ObjectMapper();
        tracer = GlobalOpenTelemetry.getTracer("flink-counter-processor");
    }

    @Override
    public void close() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        long processStartTime = System.currentTimeMillis();

        // Reconstruct parent context from event's traceId and spanId for proper trace linking
        io.opentelemetry.context.Context parentContext = io.opentelemetry.context.Context.current();
        if (event.getTraceId() != null && event.getSpanId() != null) {
            try {
                SpanContext remoteContext = SpanContext.createFromRemoteParent(
                        event.getTraceId(),
                        event.getSpanId(),
                        TraceFlags.getSampled(),
                        TraceState.getDefault()
                );
                Span remoteParentSpan = Span.wrap(remoteContext);
                parentContext = parentContext.with(remoteParentSpan);
            } catch (Exception e) {
                LOG.warn("Failed to reconstruct parent span context: {}", e.getMessage());
            }
        }

        // Create a span to cover Flink's internal buffering time (gap between deserialize and process)
        // Use actual timestamps so the span visually covers the gap in the trace
        if (event.getDeserializedAt() > 0) {
            long bufferTimeMs = processStartTime - event.getDeserializedAt();
            long startTimeNanos = event.getDeserializedAt() * 1_000_000L;
            long endTimeNanos = processStartTime * 1_000_000L;

            Span bufferSpan = tracer.spanBuilder("flink.internal.buffer")
                    .setParent(parentContext)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setStartTimestamp(startTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
                    .setAttribute("buffer.time_ms", bufferTimeMs)
                    .setAttribute("session.id", event.getSessionId())
                    .startSpan();
            bufferSpan.addEvent("event_queued_in_flink_pipeline");
            bufferSpan.end(endTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }

        // Create span for processing, linked to parent trace
        Span span = tracer.spanBuilder("flink.process_event")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("session.id", event.getSessionId())
                .setAttribute("counter.action", event.getAction())
                .setAttribute("counter.input_value", event.getValue())
                .setAttribute("trace.id.propagated", event.getTraceId() != null ? event.getTraceId() : "none")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Get current counter value
            Integer currentValue = counterState.value();
            if (currentValue == null) {
                currentValue = 0;
            }
            span.setAttribute("counter.previous_value", currentValue);

            // Apply the action
            switch (event.getAction()) {
                case "increment":
                    currentValue += event.getValue();
                    break;
                case "decrement":
                    currentValue -= event.getValue();
                    break;
                case "set":
                    currentValue = event.getValue();
                    break;
                default:
                    LOG.warn("Unknown action: {}", event.getAction());
                    span.setStatus(StatusCode.ERROR, "Unknown action: " + event.getAction());
                    return;
            }

            // Update state
            counterState.update(currentValue);
            span.setAttribute("counter.new_value", currentValue);

            // Call Drools for rule evaluation
            String alert = "NONE";
            String message = "No rules matched";

            try {
                DroolsResponse droolsResponse = callDrools(currentValue);
                alert = droolsResponse.alert;
                message = droolsResponse.message;
                span.setAttribute("drools.alert", alert);
            } catch (Exception e) {
                LOG.error("Failed to call Drools: {}", e.getMessage());
                span.recordException(e);
                // Continue with default values
            }

            // Emit result with traceId propagation
            CounterResult result = new CounterResult(
                    event.getSessionId(),
                    currentValue,
                    alert,
                    message,
                    event.getTraceId()  // Propagate trace ID from input event
            );
            out.collect(result);

            span.setStatus(StatusCode.OK);
            LOG.info("Processed event: {} -> {} (traceId={})", event, result, event.getTraceId());
        } finally {
            span.end();
        }
    }

    private DroolsResponse callDrools(int value) throws Exception {
        // Create a child span for the Drools HTTP call
        Span droolsSpan = tracer.spanBuilder("http.client POST /api/evaluate")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.method", "POST")
                .setAttribute("http.url", droolsUrl + "/api/evaluate")
                .setAttribute("peer.service", "drools")
                .startSpan();

        try (Scope scope = droolsSpan.makeCurrent()) {
            String url = droolsUrl + "/api/evaluate";
            HttpPost post = new HttpPost(url);
            post.setHeader("Content-Type", "application/json");

            // Inject trace context into HTTP headers for propagation
            TextMapSetter<HttpPost> setter = (carrier, key, val) -> carrier.setHeader(key, val);
            GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                    .inject(io.opentelemetry.context.Context.current(), post, setter);

            String requestBody = objectMapper.writeValueAsString(new DroolsRequest(value));
            post.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                String responseBody = EntityUtils.toString(response.getEntity());
                JsonNode json = objectMapper.readTree(responseBody);

                DroolsResponse droolsResponse = new DroolsResponse();
                droolsResponse.alert = json.path("alert").asText("NONE");
                droolsResponse.message = json.path("message").asText("No message");

                droolsSpan.setAttribute("http.status_code", response.getCode());
                droolsSpan.setStatus(StatusCode.OK);
                return droolsResponse;
            }
        } catch (Exception e) {
            droolsSpan.recordException(e);
            droolsSpan.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            droolsSpan.end();
        }
    }

    private static class DroolsRequest {
        public int value;

        public DroolsRequest(int value) {
            this.value = value;
        }
    }

    private static class DroolsResponse {
        public String alert;
        public String message;
    }
}
