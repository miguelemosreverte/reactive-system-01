package com.reactive.flink.processor;

import com.reactive.flink.model.CounterEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-throughput Counter Processor.
 *
 * This processor ONLY handles counter state management - no HTTP calls.
 * Drools enrichment is handled separately by AsyncDroolsEnricher using
 * Flink's AsyncDataStream for maximum parallelism.
 *
 * Architecture for high throughput:
 * 1. CounterProcessor (this) - Fast state updates, no I/O
 * 2. AsyncDroolsEnricher - Non-blocking HTTP calls via AsyncDataStream
 *
 * This separation allows:
 * - Counter state updates to proceed at memory speed
 * - Thousands of concurrent Drools HTTP calls
 * - No blocking on I/O operations
 */
public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, PreDroolsResult> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterProcessor.class);

    private final String droolsUrl; // Kept for reference/config, actual calls done in AsyncDroolsEnricher
    private transient ValueState<Integer> counterState;
    private transient Tracer tracer;

    public CounterProcessor(String droolsUrl) {
        this.droolsUrl = droolsUrl;
    }

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Integer> counterDescriptor = new ValueStateDescriptor<>(
                "counterValue", Types.INT);
        counterState = getRuntimeContext().getState(counterDescriptor);
        tracer = GlobalOpenTelemetry.getTracer("flink-counter-processor");

        LOG.info("CounterProcessor initialized - high-throughput mode (async Drools enrichment)");
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<PreDroolsResult> out) throws Exception {
        long arrivalTime = System.currentTimeMillis();

        // Reconstruct parent context for trace linking
        io.opentelemetry.context.Context parentContext = reconstructParentContext(event);

        // Create processing span
        Span span = tracer.spanBuilder("flink.process_counter")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("session.id", event.getSessionId())
                .setAttribute("counter.action", event.getAction())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Get current counter value
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

            // Update state
            counterState.update(currentValue);
            span.setAttribute("counter.new_value", currentValue);

            // Emit intermediate result for async Drools enrichment
            PreDroolsResult result = new PreDroolsResult(
                    event.getSessionId(),
                    currentValue,
                    event.getTraceId(),
                    event.getSpanId(),
                    arrivalTime
            );

            out.collect(result);

            span.setStatus(StatusCode.OK);
            span.setAttribute("processing.latency_ms", System.currentTimeMillis() - arrivalTime);

        } finally {
            span.end();
        }
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
