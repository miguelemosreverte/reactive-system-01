package com.reactive.flink.processor;

import com.reactive.flink.model.CounterEvent;
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

import java.util.HashMap;
import java.util.Map;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * CQRS-based Counter Processor with timer-based snapshot evaluation.
 *
 * Architecture:
 * - WRITE SIDE (immediate): Every event updates state and emits CounterResult
 *   with alert="PENDING" immediately. This provides fast feedback to clients.
 *
 * - READ SIDE (bounded): Timer-based snapshot evaluation triggers Drools
 *   calls at bounded intervals (MIN/MAX latency). This ensures Drools load
 *   is bounded regardless of input throughput.
 *
 * Business IDs:
 * - requestId: Correlation ID for request tracking
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 *
 * Note: OpenTelemetry trace propagation is handled automatically by the OTel agent.
 */
public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 2L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterProcessor.class);

    // Output tag for snapshot evaluation (side output)
    public static final OutputTag<PreDroolsResult> SNAPSHOT_OUTPUT =
            new OutputTag<PreDroolsResult>("snapshot-output") {};

    // System-wide latency bounds
    private final long minLatencyMs;
    private final long maxLatencyMs;

    // State
    private transient ValueState<Integer> counterState;
    private transient ValueState<Long> lastEvaluationTime;
    private transient ValueState<Long> pendingTimerTime;
    private transient ValueState<String> lastRequestId;      // Correlation ID
    private transient ValueState<String> lastCustomerId;     // Customer/tenant ID
    private transient ValueState<String> lastEventId;
    private transient ValueState<EventTiming> lastTiming;
    private transient ValueState<String> lastTraceparent;
    private transient ValueState<String> lastTracestate;
    private transient Tracer tracer;

    public CounterProcessor(long minLatencyMs, long maxLatencyMs) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    @Override
    public void open(Configuration parameters) {
        // Counter value state
        counterState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("counterValue", Types.INT));

        // Last evaluation timestamp (for MIN latency enforcement)
        lastEvaluationTime = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastEvaluationTime", Types.LONG));

        // Pending timer timestamp (to avoid duplicate timers)
        pendingTimerTime = getRuntimeContext().getState(
                new ValueStateDescriptor<>("pendingTimerTime", Types.LONG));

        // Business ID states
        lastRequestId = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastRequestId", Types.STRING));

        lastCustomerId = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastCustomerId", Types.STRING));

        lastEventId = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastEventId", Types.STRING));

        // Last timing for per-component latency tracking
        lastTiming = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastTiming", EventTiming.class));

        // Trace context state for propagation to Drools
        lastTraceparent = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastTraceparent", Types.STRING));
        lastTracestate = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastTracestate", Types.STRING));

        tracer = GlobalOpenTelemetry.getTracer("flink-counter-processor");

        LOG.info("CounterProcessor initialized - CQRS mode (minLatency={}ms, maxLatency={}ms)",
                minLatencyMs, maxLatencyMs);
    }

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

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        long arrivalTime = System.currentTimeMillis();
        String sessionId = event.getSessionId();

        // Copy and update timing from event
        EventTiming timing = EventTiming.copyFrom(event.getTiming());
        timing.setFlinkReceivedAt(arrivalTime);

        // Extract parent trace context from event (propagated through Kafka headers)
        io.opentelemetry.context.Context parentContext = io.opentelemetry.context.Context.current();
        if (event.getTraceparent() != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", event.getTraceparent());
            if (event.getTracestate() != null) {
                headers.put("tracestate", event.getTracestate());
            }
            parentContext = GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .extract(io.opentelemetry.context.Context.current(), headers, MAP_GETTER);
        }

        // Create processing span with parent context from Gateway
        Span span = tracer.spanBuilder("flink.process_counter")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("session.id", sessionId)
                .setAttribute("counter.action", event.getAction())
                .setAttribute("requestId", event.getRequestId() != null ? event.getRequestId() : "")
                .setAttribute("customerId", event.getCustomerId() != null ? event.getCustomerId() : "")
                .setAttribute("eventId", event.getEventId() != null ? event.getEventId() : "")
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Set MDC for log correlation with business IDs
            if (event.getRequestId() != null) {
                MDC.put("requestId", event.getRequestId());
            }
            if (event.getCustomerId() != null) {
                MDC.put("customerId", event.getCustomerId());
            }

            // ============================================
            // WRITE SIDE: Immediate state update
            // ============================================
            Integer currentValue = counterState.value();
            if (currentValue == null) {
                currentValue = 0;
            }
            span.setAttribute("counter.previous_value", currentValue);

            // Process the action
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
            }

            // Mark processing complete time
            long processedAt = System.currentTimeMillis();
            timing.setFlinkProcessedAt(processedAt);

            // Update state
            counterState.update(currentValue);
            lastRequestId.update(event.getRequestId());
            lastCustomerId.update(event.getCustomerId());
            lastEventId.update(event.getEventId());
            lastTiming.update(timing);
            lastTraceparent.update(event.getTraceparent());
            lastTracestate.update(event.getTracestate());
            span.setAttribute("counter.new_value", currentValue);

            // Emit immediate result (without alert - alert will come later)
            CounterResult result = new CounterResult(
                    sessionId,
                    currentValue,
                    "PENDING",  // Alert is pending (will be evaluated via timer)
                    "Alert evaluation pending",
                    event.getRequestId(),
                    event.getCustomerId(),
                    event.getEventId(),
                    timing
            );

            // Inject current trace context for downstream propagation
            Map<String, String> traceHeaders = new HashMap<>();
            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(io.opentelemetry.context.Context.current(), traceHeaders, Map::put);
            result.setTraceparent(traceHeaders.get("traceparent"));
            result.setTracestate(traceHeaders.get("tracestate"));

            out.collect(result);

            LOG.info("Flink processed event: sessionId={}, action={}, newValue={}, requestId={}, customerId={}",
                    sessionId, event.getAction(), currentValue, event.getRequestId(), event.getCustomerId());

            // ============================================
            // READ SIDE: Schedule snapshot evaluation
            // ============================================
            scheduleSnapshotEvaluation(ctx, arrivalTime);

            span.setStatus(StatusCode.OK);
            span.setAttribute("processing.latency_ms", processedAt - arrivalTime);

        } finally {
            MDC.clear();
            span.end();
        }
    }

    /**
     * Schedule a timer for snapshot evaluation based on latency bounds.
     */
    private void scheduleSnapshotEvaluation(Context ctx, long now) throws Exception {
        Long pendingTimer = pendingTimerTime.value();
        Long lastEval = lastEvaluationTime.value();

        // If timer already pending, no need to schedule another
        if (pendingTimer != null && pendingTimer > now) {
            return;
        }

        // Calculate next evaluation time
        long nextEvalTime;
        if (lastEval == null) {
            // First event: evaluate after minLatency
            nextEvalTime = now + minLatencyMs;
        } else {
            // Subsequent events: respect both min and max latency
            long minAllowed = lastEval + minLatencyMs;
            long maxAllowed = now + maxLatencyMs;
            nextEvalTime = Math.max(minAllowed, now + 1);
            nextEvalTime = Math.min(nextEvalTime, maxAllowed);
        }

        // Register timer
        ctx.timerService().registerProcessingTimeTimer(nextEvalTime);
        pendingTimerTime.update(nextEvalTime);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<CounterResult> out) throws Exception {
        String sessionId = (String) ctx.getCurrentKey();

        // Get current state snapshot
        Integer currentValue = counterState.value();
        if (currentValue == null) {
            pendingTimerTime.clear();
            return;
        }

        String requestId = lastRequestId.value();
        String customerId = lastCustomerId.value();
        String eventId = lastEventId.value();
        EventTiming timing = lastTiming.value();
        String traceparent = lastTraceparent.value();
        String tracestate = lastTracestate.value();

        // Create snapshot for Drools evaluation (side output)
        PreDroolsResult snapshot = new PreDroolsResult(
                sessionId,
                currentValue,
                requestId,
                customerId,
                eventId,
                timing,
                System.currentTimeMillis(),
                traceparent,
                tracestate
        );

        // Emit to side output for async Drools evaluation
        ctx.output(SNAPSHOT_OUTPUT, snapshot);

        // Update evaluation time and clear pending timer
        lastEvaluationTime.update(timestamp);
        pendingTimerTime.clear();
    }
}
