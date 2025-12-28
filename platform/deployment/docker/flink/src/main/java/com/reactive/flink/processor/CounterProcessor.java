package com.reactive.flink.processor;

import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import com.reactive.flink.model.EventTiming;
import com.reactive.flink.model.PreDroolsResult;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.reactive.platform.Opt.or;

/**
 * CQRS-based Counter Processor with timer-based snapshot evaluation.
 *
 * INTERNAL: Receives events from deserializer where fields are already normalized.
 * Only Flink state access is a boundary (state.value() can return null).
 *
 * Feature flags (passed via constructor):
 * - skipTracing: Disable trace span creation for maximum throughput benchmarks
 */
public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 4L;  // Bumped for new field
    private static final Logger log = LoggerFactory.getLogger(CounterProcessor.class);

    public static final OutputTag<PreDroolsResult> SNAPSHOT_OUTPUT =
            new OutputTag<PreDroolsResult>("snapshot-output") {};

    private final long minLatencyMs;
    private final long maxLatencyMs;
    private final boolean skipTracing;
    private final boolean benchmarkMode;

    private transient ValueState<Integer> counterState;
    private transient ValueState<Long> lastEvaluationTime;
    private transient ValueState<Long> pendingTimerTime;
    private transient ValueState<String> lastRequestId;
    private transient ValueState<String> lastCustomerId;
    private transient ValueState<String> lastEventId;
    private transient ValueState<EventTiming> lastTiming;
    private transient ValueState<String> lastTraceparent;
    private transient ValueState<String> lastTracestate;

    public CounterProcessor(long minLatencyMs, long maxLatencyMs) {
        this(minLatencyMs, maxLatencyMs, false, false);
    }

    public CounterProcessor(long minLatencyMs, long maxLatencyMs, boolean skipTracing) {
        this(minLatencyMs, maxLatencyMs, skipTracing, false);
    }

    public CounterProcessor(long minLatencyMs, long maxLatencyMs, boolean skipTracing, boolean benchmarkMode) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.skipTracing = skipTracing;
        this.benchmarkMode = benchmarkMode;
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

        log.info("CounterProcessor initialized (minLatency={}ms, maxLatency={}ms, skipTracing={}, benchmarkMode={})",
                minLatencyMs, maxLatencyMs, skipTracing, benchmarkMode);
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        if (benchmarkMode) {
            // Ultra-fast path: no state, no tracing, pure passthrough
            out.collect(processEventBenchmark(event));
            return;
        }
        if (skipTracing) {
            // Fast path: no tracing overhead
            CounterResult result = processEventNoTrace(event, ctx);
            out.collect(result);
        } else {
            // Normal path with tracing
            SpanHandle span = Log.asyncTracedConsume("flink.process_counter", event, Log.SpanType.CONSUMER);
            try {
                CounterResult result = processEvent(event, ctx, span);
                out.collect(result);
                span.success();
            } catch (Exception e) {
                span.failure(e);
                throw e;
            }
        }
    }

    private CounterResult processEvent(CounterEvent event, Context ctx, SpanHandle span) throws Exception {
        long arrivalTime = System.currentTimeMillis();

        // TRUST: Event fields are normalized by deserializer - record accessors return guaranteed values
        String sessionId = event.sessionId();
        String action = event.action();

        // Immutable timing - use with-prefixed methods
        EventTiming timing = event.timing().withFlinkReceivedAt(arrivalTime);

        // Only operation-specific attrs - business IDs auto-extracted by asyncTracedConsume
        span.attr("counter.action", action);

        // BOUNDARY: Flink state can return null for uninitialized state
        int currentValue = or(counterState.value(), 0);
        span.attr("counter.previous_value", currentValue);

        String effectiveAction = action.isEmpty() ? "increment" : action;
        currentValue = applyAction(effectiveAction, currentValue, event.value());
        timing = timing.withFlinkProcessedAt(System.currentTimeMillis());

        // Update state
        counterState.update(currentValue);
        lastRequestId.update(event.requestId());
        lastCustomerId.update(event.customerId());
        lastEventId.update(event.eventId());
        lastTiming.update(timing);
        lastTraceparent.update(event.traceparent());
        lastTracestate.update(event.tracestate());

        span.attr("counter.new_value", currentValue);

        // Removed per-event logging for throughput (was: log.info("Processed: ..."))

        scheduleSnapshotEvaluation(ctx, arrivalTime);

        CounterResult result = new CounterResult(
                sessionId, currentValue, "PENDING", "Alert evaluation pending",
                event.requestId(), event.customerId(), event.eventId(), timing);

        return withTraceContext(result, span);
    }

    /**
     * Ultra-fast benchmark path: pure passthrough with NO state access.
     * Bypasses all Flink state, timers, and object creation.
     * Used to measure maximum theoretical Flink throughput (Kafka-to-Kafka).
     */
    private CounterResult processEventBenchmark(CounterEvent event) {
        return new CounterResult(
                event.sessionId(),
                event.value(),
                "BENCHMARK",
                "Benchmark mode - no state",
                event.requestId(),
                event.customerId(),
                event.eventId(),
                event.timing());
    }

    /**
     * Fast path processing without any tracing overhead.
     * Used for maximum throughput benchmark mode.
     */
    private CounterResult processEventNoTrace(CounterEvent event, Context ctx) throws Exception {
        long arrivalTime = System.currentTimeMillis();

        String sessionId = event.sessionId();
        String action = event.action();

        EventTiming timing = event.timing().withFlinkReceivedAt(arrivalTime);

        int currentValue = or(counterState.value(), 0);

        String effectiveAction = action.isEmpty() ? "increment" : action;
        currentValue = applyAction(effectiveAction, currentValue, event.value());
        timing = timing.withFlinkProcessedAt(System.currentTimeMillis());

        // Update state
        counterState.update(currentValue);
        lastRequestId.update(event.requestId());
        lastCustomerId.update(event.customerId());
        lastEventId.update(event.eventId());
        lastTiming.update(timing);
        lastTraceparent.update(event.traceparent());
        lastTracestate.update(event.tracestate());

        scheduleSnapshotEvaluation(ctx, arrivalTime);

        return new CounterResult(
                sessionId, currentValue, "PENDING", "Alert evaluation pending",
                event.requestId(), event.customerId(), event.eventId(), timing);
    }

    private int applyAction(String action, int currentValue, int delta) {
        return switch (action.toLowerCase()) {
            case "increment" -> currentValue + delta;
            case "decrement" -> currentValue - delta;
            case "set" -> delta;
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private CounterResult withTraceContext(CounterResult result, SpanHandle span) {
        Map<String, String> headers = span.headers();
        return result.withTraceContext(
                headers.getOrDefault("traceparent", ""),
                headers.getOrDefault("tracestate", ""));
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

        // BOUNDARY: Flink state can return null
        Integer currentValue = counterState.value();
        if (currentValue == null) {
            pendingTimerTime.clear();
            return;
        }

        // BOUNDARY: Flink state values can be null if never set - use or()
        PreDroolsResult snapshot = new PreDroolsResult(
                sessionId, currentValue,
                or(lastRequestId.value(), ""),
                or(lastCustomerId.value(), ""),
                or(lastEventId.value(), ""),
                EventTiming.from(lastTiming.value()),
                System.currentTimeMillis(),
                or(lastTraceparent.value(), ""),
                or(lastTracestate.value(), ""));

        ctx.output(SNAPSHOT_OUTPUT, snapshot);
        lastEvaluationTime.update(timestamp);
        pendingTimerTime.clear();
    }
}
