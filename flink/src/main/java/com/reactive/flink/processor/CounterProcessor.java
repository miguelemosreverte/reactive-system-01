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
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Counter Processor with Self-Tuning Latency Control.
 *
 * Like an automatic car transmission:
 * - Continuously measures actual performance (latency, throughput)
 * - Every 10 seconds, analyzes data and adjusts batch wait time
 * - Automatically finds the optimal balance between latency and throughput
 * - Respects SLA constraints at all times
 *
 * NO MANUAL TUNING REQUIRED - the system learns by itself.
 *
 * The only configuration needed: your SLA (max acceptable latency).
 */
public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterProcessor.class);

    // Self-tuning configuration - sensible defaults, no manual tuning needed
    private static final long SLA_LATENCY_MS = 200;      // Max acceptable latency (ms)
    private static final long TUNING_INTERVAL_MS = 10000; // Re-tune every 10 seconds

    private final String droolsUrl;
    private transient ValueState<Integer> counterState;
    private transient ListState<PendingEvent> pendingEvents;
    private transient ValueState<Long> batchStartTime;
    private transient CloseableHttpClient httpClient;
    private transient ObjectMapper objectMapper;
    private transient Tracer tracer;
    private transient AdaptiveLatencyController adaptiveController;

    public CounterProcessor(String droolsUrl) {
        this.droolsUrl = droolsUrl;
    }

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Integer> counterDescriptor = new ValueStateDescriptor<>(
                "counterValue", Types.INT);
        counterState = getRuntimeContext().getState(counterDescriptor);

        ListStateDescriptor<PendingEvent> pendingDescriptor = new ListStateDescriptor<>(
                "pendingEvents", PendingEvent.class);
        pendingEvents = getRuntimeContext().getListState(pendingDescriptor);

        ValueStateDescriptor<Long> batchStartDescriptor = new ValueStateDescriptor<>(
                "batchStartTime", Types.LONG);
        batchStartTime = getRuntimeContext().getState(batchStartDescriptor);

        httpClient = HttpClients.createDefault();
        objectMapper = new ObjectMapper();
        tracer = GlobalOpenTelemetry.getTracer("flink-counter-processor");

        // Initialize SELF-TUNING controller - only needs SLA, discovers everything else
        adaptiveController = new AdaptiveLatencyController(SLA_LATENCY_MS, TUNING_INTERVAL_MS);

        LOG.info("CounterProcessor initialized with SELF-TUNING latency control. " +
                 "SLA={}ms, tuning every {}s. System will automatically discover optimal settings.",
                 SLA_LATENCY_MS, TUNING_INTERVAL_MS / 1000);
    }

    @Override
    public void close() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        long arrivalTime = System.currentTimeMillis();

        // Record arrival for throughput tracking
        adaptiveController.recordEventArrival();

        // Reconstruct parent context for trace linking
        io.opentelemetry.context.Context parentContext = reconstructParentContext(event);

        // Create buffer span if deserialize timestamp is available
        createBufferSpan(event, arrivalTime, parentContext);

        // Get current recommended wait time (learned value)
        long recommendedWait = adaptiveController.getRecommendedWaitMs();

        // If wait is 0 (immediate mode), process right away
        if (recommendedWait == 0) {
            processImmediately(event, arrivalTime, parentContext, out);
        } else {
            // Batching mode - accumulate and flush based on learned timing
            addToBatchAndMaybeFlush(event, arrivalTime, parentContext, ctx, out);
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<CounterResult> out) throws Exception {
        flushPendingEvents(ctx, out);
    }

    private void processImmediately(CounterEvent event, long arrivalTime,
                                    io.opentelemetry.context.Context parentContext,
                                    Collector<CounterResult> out) throws Exception {
        Span span = tracer.spanBuilder("flink.process_event")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("session.id", event.getSessionId())
                .setAttribute("counter.action", event.getAction())
                .setAttribute("adaptive.mode", adaptiveController.getCurrentMode())
                .setAttribute("adaptive.learned_wait_ms", adaptiveController.getRecommendedWaitMs())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            CounterResult result = processEvent(event, span);
            out.collect(result);
            span.setStatus(StatusCode.OK);

            // Record latency for self-tuning (measure actual performance)
            long latency = System.currentTimeMillis() - arrivalTime;
            adaptiveController.recordEventLatency(latency);
            span.setAttribute("adaptive.measured_latency_ms", latency);

        } finally {
            span.end();
        }
    }

    private void addToBatchAndMaybeFlush(CounterEvent event, long arrivalTime,
                                         io.opentelemetry.context.Context parentContext,
                                         Context ctx, Collector<CounterResult> out) throws Exception {
        // Add to pending batch
        PendingEvent pending = new PendingEvent();
        pending.event = event;
        pending.arrivalTime = arrivalTime;
        pending.traceId = event.getTraceId();
        pending.spanId = event.getSpanId();
        pendingEvents.add(pending);

        // Track batch start time
        Long startTime = batchStartTime.value();
        if (startTime == null) {
            batchStartTime.update(arrivalTime);
            startTime = arrivalTime;
        }

        // Check if we should flush based on learned timing
        if (adaptiveController.shouldFlush(getPendingCount(), startTime)) {
            flushPendingEvents(ctx, out);
        } else {
            // Register timer for batch timeout (using learned wait time)
            long timerTime = startTime + adaptiveController.getRecommendedWaitMs();
            ctx.timerService().registerProcessingTimeTimer(timerTime);
        }
    }

    private int getPendingCount() throws Exception {
        int count = 0;
        for (PendingEvent p : pendingEvents.get()) {
            count++;
        }
        return count;
    }

    private void flushPendingEvents(Context ctx, Collector<CounterResult> out) throws Exception {
        List<PendingEvent> events = new ArrayList<>();
        long earliestArrival = Long.MAX_VALUE;
        for (PendingEvent p : pendingEvents.get()) {
            events.add(p);
            earliestArrival = Math.min(earliestArrival, p.arrivalTime);
        }

        if (events.isEmpty()) {
            return;
        }

        long batchStart = System.currentTimeMillis();

        // Create batch processing span
        Span batchSpan = tracer.spanBuilder("flink.process_batch")
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("batch.size", events.size())
                .setAttribute("adaptive.mode", adaptiveController.getCurrentMode())
                .setAttribute("adaptive.learned_wait_ms", adaptiveController.getRecommendedWaitMs())
                .setAttribute("adaptive.throughput", adaptiveController.getCurrentThroughput())
                .setAttribute("adaptive.last_p95_latency", adaptiveController.getLastP95Latency())
                .startSpan();

        try (Scope scope = batchSpan.makeCurrent()) {
            // Process all events in batch
            Integer currentValue = counterState.value();
            if (currentValue == null) {
                currentValue = 0;
            }

            for (PendingEvent pending : events) {
                CounterEvent event = pending.event;
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
                }
            }

            counterState.update(currentValue);

            // Call Drools once for the final state
            String alert = "NONE";
            String message = "No rules matched";
            try {
                DroolsResponse droolsResponse = callDrools(currentValue);
                alert = droolsResponse.alert;
                message = droolsResponse.message;
            } catch (Exception e) {
                LOG.error("Failed to call Drools: {}", e.getMessage());
                batchSpan.recordException(e);
            }

            // Emit results for all events
            for (PendingEvent pending : events) {
                CounterResult result = new CounterResult(
                        pending.event.getSessionId(),
                        currentValue,
                        alert,
                        message,
                        pending.traceId
                );
                out.collect(result);

                // Record latency for self-tuning (from arrival to now)
                long latency = System.currentTimeMillis() - pending.arrivalTime;
                adaptiveController.recordEventLatency(latency);
            }

            long batchDuration = System.currentTimeMillis() - batchStart;
            long totalLatency = System.currentTimeMillis() - earliestArrival;

            batchSpan.setAttribute("batch.duration_ms", batchDuration);
            batchSpan.setAttribute("batch.total_latency_ms", totalLatency);
            batchSpan.setAttribute("batch.drools_calls_saved", events.size() - 1);
            batchSpan.setStatus(StatusCode.OK);

            LOG.debug("Batch processed: {} events, latency={}ms, mode={}, learned_wait={}ms",
                    events.size(), totalLatency, adaptiveController.getCurrentMode(),
                    adaptiveController.getRecommendedWaitMs());

        } finally {
            batchSpan.end();
        }

        pendingEvents.clear();
        batchStartTime.clear();
    }

    private CounterResult processEvent(CounterEvent event, Span span) throws Exception {
        Integer currentValue = counterState.value();
        if (currentValue == null) {
            currentValue = 0;
        }
        span.setAttribute("counter.previous_value", currentValue);

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
                return new CounterResult(event.getSessionId(), currentValue, "ERROR",
                        "Unknown action", event.getTraceId());
        }

        counterState.update(currentValue);
        span.setAttribute("counter.new_value", currentValue);

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
        }

        return new CounterResult(event.getSessionId(), currentValue, alert, message, event.getTraceId());
    }

    private io.opentelemetry.context.Context reconstructParentContext(CounterEvent event) {
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
        return parentContext;
    }

    private void createBufferSpan(CounterEvent event, long processStartTime,
                                   io.opentelemetry.context.Context parentContext) {
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
    }

    private DroolsResponse callDrools(int value) throws Exception {
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

    public static class PendingEvent implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public CounterEvent event;
        public long arrivalTime;
        public String traceId;
        public String spanId;
    }

    private static class DroolsRequest {
        public int value;
        public DroolsRequest(int value) { this.value = value; }
    }

    private static class DroolsResponse {
        public String alert;
        public String message;
    }
}
