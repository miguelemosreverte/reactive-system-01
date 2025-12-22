package com.reactive.flink.processor;

import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.EventTiming;
import com.reactive.flink.model.PreDroolsResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
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

/**
 * CQRS-based Counter Processor with timer-based snapshot evaluation.
 *
 * Clean processor - business logic separated from tracing infrastructure.
 * Manual span creation required since Flink has no OTel auto-instrumentation.
 */
public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 2L;
    private static final Logger log = LoggerFactory.getLogger(CounterProcessor.class);

    public static final OutputTag<PreDroolsResult> SNAPSHOT_OUTPUT =
            new OutputTag<PreDroolsResult>("snapshot-output") {};

    private final long minLatencyMs;
    private final long maxLatencyMs;

    // State
    private transient ValueState<Integer> counterState;
    private transient ValueState<Long> lastEvaluationTime;
    private transient ValueState<Long> pendingTimerTime;
    private transient ValueState<String> lastRequestId;
    private transient ValueState<String> lastCustomerId;
    private transient ValueState<String> lastEventId;
    private transient ValueState<EventTiming> lastTiming;
    private transient ValueState<String> lastTraceparent;
    private transient ValueState<String> lastTracestate;
    private transient Tracer tracer;

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }
    };

    public CounterProcessor(long minLatencyMs, long maxLatencyMs) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    @Override
    public void open(Configuration parameters) {
        counterState = getRuntimeContext().getState(new ValueStateDescriptor<>("counterValue", Types.INT));
        lastEvaluationTime = getRuntimeContext().getState(new ValueStateDescriptor<>("lastEvaluationTime", Types.LONG));
        pendingTimerTime = getRuntimeContext().getState(new ValueStateDescriptor<>("pendingTimerTime", Types.LONG));
        lastRequestId = getRuntimeContext().getState(new ValueStateDescriptor<>("lastRequestId", Types.STRING));
        lastCustomerId = getRuntimeContext().getState(new ValueStateDescriptor<>("lastCustomerId", Types.STRING));
        lastEventId = getRuntimeContext().getState(new ValueStateDescriptor<>("lastEventId", Types.STRING));
        lastTiming = getRuntimeContext().getState(new ValueStateDescriptor<>("lastTiming", EventTiming.class));
        lastTraceparent = getRuntimeContext().getState(new ValueStateDescriptor<>("lastTraceparent", Types.STRING));
        lastTracestate = getRuntimeContext().getState(new ValueStateDescriptor<>("lastTracestate", Types.STRING));
        tracer = GlobalOpenTelemetry.get().getTracer("flink-counter-processor");

        log.info("CounterProcessor initialized (minLatency={}ms, maxLatency={}ms)", minLatencyMs, maxLatencyMs);
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        long arrivalTime = System.currentTimeMillis();

        // Extract parent trace context (OTel context, not Flink context)
        io.opentelemetry.context.Context parentContext = extractTraceContext(event);

        // Create processing span
        Span span = tracer.spanBuilder("flink.process_counter")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            CounterResult result = processEvent(event, ctx, arrivalTime, span);
            out.collect(result);
        } finally {
            span.end();
        }
    }

    /**
     * Core business logic - separated from tracing ceremony.
     */
    private CounterResult processEvent(CounterEvent event, Context ctx, long arrivalTime, Span span) throws Exception {
        String sessionId = event.getSessionId();
        EventTiming timing = EventTiming.copyFrom(event.getTiming());
        timing.setFlinkReceivedAt(arrivalTime);

        // Add span attributes
        span.setAttribute("session.id", sessionId);
        span.setAttribute("counter.action", event.getAction());
        span.setAttribute("requestId", nullSafe(event.getRequestId()));
        span.setAttribute("customerId", nullSafe(event.getCustomerId()));
        span.setAttribute("eventId", nullSafe(event.getEventId()));

        // Get current value
        Integer currentValue = counterState.value();
        if (currentValue == null) currentValue = 0;
        span.setAttribute("counter.previous_value", currentValue);

        // Apply action
        currentValue = applyAction(event.getAction(), currentValue, event.getValue());
        timing.setFlinkProcessedAt(System.currentTimeMillis());

        // Update state
        counterState.update(currentValue);
        lastRequestId.update(event.getRequestId());
        lastCustomerId.update(event.getCustomerId());
        lastEventId.update(event.getEventId());
        lastTiming.update(timing);
        lastTraceparent.update(event.getTraceparent());
        lastTracestate.update(event.getTracestate());

        span.setAttribute("counter.new_value", currentValue);

        log.info("Processed: session={}, action={}, value={}", sessionId, event.getAction(), currentValue);

        // Schedule Drools evaluation
        scheduleSnapshotEvaluation(ctx, arrivalTime);

        // Build result with trace context
        CounterResult result = new CounterResult(
                sessionId, currentValue, "PENDING", "Alert evaluation pending",
                event.getRequestId(), event.getCustomerId(), event.getEventId(), timing);
        injectTraceContext(result);

        return result;
    }

    private int applyAction(String action, int currentValue, int delta) {
        return switch (action) {
            case "increment" -> currentValue + delta;
            case "decrement" -> currentValue - delta;
            case "set" -> delta;
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private io.opentelemetry.context.Context extractTraceContext(CounterEvent event) {
        if (event.getTraceparent() == null) return io.opentelemetry.context.Context.current();

        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", event.getTraceparent());
        if (event.getTracestate() != null) headers.put("tracestate", event.getTracestate());

        return GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(io.opentelemetry.context.Context.current(), headers, MAP_GETTER);
    }

    private void injectTraceContext(CounterResult result) {
        Map<String, String> headers = new HashMap<>();
        GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(io.opentelemetry.context.Context.current(), headers, Map::put);
        result.setTraceparent(headers.get("traceparent"));
        result.setTracestate(headers.get("tracestate"));
    }

    private void scheduleSnapshotEvaluation(Context ctx, long now) throws Exception {
        Long pendingTimer = pendingTimerTime.value();
        Long lastEval = lastEvaluationTime.value();

        if (pendingTimer != null && pendingTimer > now) return;

        long nextEvalTime = (lastEval == null)
                ? now + minLatencyMs
                : Math.min(Math.max(lastEval + minLatencyMs, now + 1), now + maxLatencyMs);

        ctx.timerService().registerProcessingTimeTimer(nextEvalTime);
        pendingTimerTime.update(nextEvalTime);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<CounterResult> out) throws Exception {
        String sessionId = (String) ctx.getCurrentKey();
        Integer currentValue = counterState.value();
        if (currentValue == null) {
            pendingTimerTime.clear();
            return;
        }

        PreDroolsResult snapshot = new PreDroolsResult(
                sessionId, currentValue,
                lastRequestId.value(), lastCustomerId.value(), lastEventId.value(),
                lastTiming.value(), System.currentTimeMillis(),
                lastTraceparent.value(), lastTracestate.value());

        ctx.output(SNAPSHOT_OUTPUT, snapshot);
        lastEvaluationTime.update(timestamp);
        pendingTimerTime.clear();
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
