package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * Deserializer for CounterEvent messages from Kafka.
 *
 * Note: OpenTelemetry trace context propagation is handled automatically by the OTel agent
 * via W3C traceparent headers in Kafka messages. This deserializer only:
 * 1. Parses JSON to CounterEvent
 * 2. Records deserialization timestamp
 * 3. Sets MDC context for structured logging with requestId/customerId
 */
public class CounterEventDeserializer implements DeserializationSchema<CounterEvent> {
    private static final long serialVersionUID = 2L;
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
            // Deserialize JSON to CounterEvent
            CounterEvent event = objectMapper.readValue(message, CounterEvent.class);

            // Record deserialization timestamp for gap analysis
            long now = System.currentTimeMillis();
            event.setDeserializedAt(now);

            // Create deserialize span (OTel agent provides parent context from Kafka headers)
            Span deserializeSpan = tracer.spanBuilder("flink.deserialize")
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute("message.size", message.length)
                    .setAttribute("session.id", event.getSessionId() != null ? event.getSessionId() : "")
                    .setAttribute("counter.action", event.getAction() != null ? event.getAction() : "")
                    .setAttribute("requestId", event.getRequestId() != null ? event.getRequestId() : "")
                    .setAttribute("customerId", event.getCustomerId() != null ? event.getCustomerId() : "")
                    .setAttribute("eventId", event.getEventId() != null ? event.getEventId() : "")
                    .startSpan();

            try (Scope scope = deserializeSpan.makeCurrent()) {
                // Set MDC for log correlation with business IDs
                if (event.getRequestId() != null) {
                    MDC.put("requestId", event.getRequestId());
                }
                if (event.getCustomerId() != null) {
                    MDC.put("customerId", event.getCustomerId());
                }

                // Calculate kafka transit time if timestamp is available
                if (event.getTimestamp() > 0) {
                    long transitTimeMs = now - event.getTimestamp();
                    deserializeSpan.setAttribute("kafka.transit_time_ms", transitTimeMs);
                }

                deserializeSpan.addEvent("message_deserialized");

            } finally {
                MDC.clear();
                deserializeSpan.end();
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
