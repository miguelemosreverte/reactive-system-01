package com.reactive.flink.processor;

import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.EventTiming;
import com.reactive.flink.model.PreDroolsResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
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
 * Architecture:
 * - WRITE SIDE (immediate): Every event updates state and emits CounterResult
 *   with alert="PENDING" immediately. This provides fast feedback to clients.
 *
 * - READ SIDE (bounded): Timer-based snapshot evaluation triggers Drools
 *   calls at bounded intervals (MIN/MAX latency). This ensures Drools load
 *   is bounded regardless of input throughput.
 *
 * The separation allows:
 * - Unlimited write throughput (memory-speed state updates)
 * - Bounded read throughput (Drools calls capped by latency bounds)
 * - Eventual consistency: Alerts arrive after state updates, within MAX_LATENCY
 */
public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 1L;
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
    private transient ValueState<String> lastTraceId;
    private transient ValueState<String> lastEventId;
    private transient ValueState<EventTiming> lastTiming;
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

        // Last trace ID for correlation
        lastTraceId = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastTraceId", Types.STRING));

        // Last event ID for transaction tracking
        lastEventId = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastEventId", Types.STRING));

        // Last timing for per-component latency tracking
        lastTiming = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastTiming", EventTiming.class));

        tracer = GlobalOpenTelemetry.getTracer("flink-counter-processor");

        LOG.info("CounterProcessor initialized - CQRS mode (minLatency={}ms, maxLatency={}ms)",
                minLatencyMs, maxLatencyMs);
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        long arrivalTime = System.currentTimeMillis();
        String sessionId = event.getSessionId();

        // Copy and update timing from event
        EventTiming timing = EventTiming.copyFrom(event.getTiming());
        timing.setFlinkReceivedAt(arrivalTime);

        // Reconstruct parent context for trace linking
        io.opentelemetry.context.Context parentContext = reconstructParentContext(event);

        // Create processing span
        Span span = tracer.spanBuilder("flink.process_counter")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("session.id", sessionId)
                .setAttribute("counter.action", event.getAction())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
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
            lastTraceId.update(event.getTraceId());
            lastEventId.update(event.getEventId());
            lastTiming.update(timing);
            span.setAttribute("counter.new_value", currentValue);

            // Emit immediate result (without alert - alert will come later)
            CounterResult result = new CounterResult(
                    sessionId,
                    currentValue,
                    "PENDING",  // Alert is pending (will be evaluated via timer)
                    "Alert evaluation pending",
                    event.getTraceId(),
                    event.getEventId(),
                    timing
            );
            out.collect(result);

            // ============================================
            // READ SIDE: Schedule snapshot evaluation
            // ============================================
            scheduleSnapshotEvaluation(ctx, arrivalTime);

            span.setStatus(StatusCode.OK);
            span.setAttribute("processing.latency_ms", processedAt - arrivalTime);

        } finally {
            span.end();
        }
    }

    /**
     * Schedule a timer for snapshot evaluation based on latency bounds.
     *
     * Logic:
     * - If no timer is pending, schedule one for MIN(now + maxLatency, lastEval + minLatency)
     * - This ensures:
     *   - We don't evaluate more frequently than minLatency (aggregate events)
     *   - We always evaluate within maxLatency (guaranteed SLA)
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
            // First event: evaluate after minLatency (or immediately if minLatency is 0)
            nextEvalTime = now + minLatencyMs;
        } else {
            // Subsequent events: respect both min and max latency
            long minAllowed = lastEval + minLatencyMs;
            long maxAllowed = now + maxLatencyMs;
            nextEvalTime = Math.max(minAllowed, now + 1); // At least 1ms from now
            nextEvalTime = Math.min(nextEvalTime, maxAllowed); // But within max latency
        }

        // Register timer
        ctx.timerService().registerProcessingTimeTimer(nextEvalTime);
        pendingTimerTime.update(nextEvalTime);

        LOG.debug("Scheduled snapshot evaluation for session {} at {}",
                ctx.getCurrentKey(), nextEvalTime);
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<CounterResult> out) throws Exception {
        String sessionId = (String) ctx.getCurrentKey();

        // Get current state snapshot
        Integer currentValue = counterState.value();
        if (currentValue == null) {
            // No state, nothing to evaluate
            pendingTimerTime.clear();
            return;
        }

        String traceId = lastTraceId.value();
        String eventId = lastEventId.value();
        EventTiming timing = lastTiming.value();

        // Create snapshot for Drools evaluation (side output)
        PreDroolsResult snapshot = new PreDroolsResult(
                sessionId,
                currentValue,
                traceId,
                null,  // No span ID for timer-based evaluation
                System.currentTimeMillis(),
                eventId,
                timing
        );

        // Emit to side output for async Drools evaluation
        ctx.output(SNAPSHOT_OUTPUT, snapshot);

        // Update evaluation time and clear pending timer
        lastEvaluationTime.update(timestamp);
        pendingTimerTime.clear();

        LOG.debug("Emitted snapshot for session {} with value {}", sessionId, currentValue);
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
}
