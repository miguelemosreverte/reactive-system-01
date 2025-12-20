package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class CounterEventDeserializer implements DeserializationSchema<CounterEvent> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterEventDeserializer.class);

    private transient ObjectMapper objectMapper;
    private transient Tracer tracer;

    @Override
    public void open(InitializationContext context) {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        tracer = GlobalOpenTelemetry.getTracer("flink-deserializer");
    }

    @Override
    public CounterEvent deserialize(byte[] message) throws IOException {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
        if (tracer == null) {
            tracer = GlobalOpenTelemetry.getTracer("flink-deserializer");
        }

        try {
            // First deserialize to get trace context
            CounterEvent event = objectMapper.readValue(message, CounterEvent.class);

            // Record deserialization timestamp for gap analysis
            long now = System.currentTimeMillis();
            event.setDeserializedAt(now);

            // Create spans linked to parent trace to show full pipeline
            if (event.getTraceId() != null && event.getSpanId() != null) {
                Context parentContext = Context.current();
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
                    LOG.debug("Could not reconstruct parent context: {}", e.getMessage());
                }

                // Create kafka.transit span to show message transit time through Kafka
                // Use actual timestamps to represent the time message spent in Kafka
                if (event.getTimestamp() > 0) {
                    long transitTimeMs = now - event.getTimestamp();
                    // Convert milliseconds to nanoseconds for OpenTelemetry
                    long startTimeNanos = event.getTimestamp() * 1_000_000L;
                    long endTimeNanos = now * 1_000_000L;

                    Span kafkaTransitSpan = tracer.spanBuilder("kafka.transit")
                            .setParent(parentContext)
                            .setSpanKind(SpanKind.CONSUMER)
                            .setStartTimestamp(startTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
                            .setAttribute("kafka.transit_time_ms", transitTimeMs)
                            .setAttribute("session.id", event.getSessionId())
                            .startSpan();
                    kafkaTransitSpan.addEvent("message_in_kafka_queue");
                    // End at current time to show the full transit duration
                    kafkaTransitSpan.end(endTimeNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
                }

                // Create deserialize span
                Span deserializeSpan = tracer.spanBuilder("flink.deserialize")
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.CONSUMER)
                        .setAttribute("message.size", message.length)
                        .setAttribute("session.id", event.getSessionId())
                        .setAttribute("counter.action", event.getAction())
                        .startSpan();

                try (Scope scope = deserializeSpan.makeCurrent()) {
                    deserializeSpan.addEvent("message_deserialized");
                    // Store the current span's context for later linking
                    event.setSpanId(deserializeSpan.getSpanContext().getSpanId());
                } finally {
                    deserializeSpan.end();
                }
            }

            return event;
        } catch (Exception e) {
            LOG.error("Failed to deserialize message: {}", new String(message), e);
            return null;
        }
    }

    @Override
    public boolean isEndOfStream(CounterEvent nextElement) {
        return false;
    }

    @Override
    public TypeInformation<CounterEvent> getProducedType() {
        return TypeInformation.of(CounterEvent.class);
    }
}
