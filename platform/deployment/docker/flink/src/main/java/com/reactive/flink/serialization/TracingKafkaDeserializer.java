package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Kafka deserializer with OpenTelemetry trace context extraction.
 *
 * Extracts W3C trace context (traceparent, tracestate) from Kafka message headers
 * and continues the distributed trace started by the Gateway.
 */
public class TracingKafkaDeserializer implements KafkaRecordDeserializationSchema<CounterEvent> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(TracingKafkaDeserializer.class);

    private transient ObjectMapper objectMapper;
    private transient Tracer tracer;

    // Getter for extracting trace context from Kafka headers
    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            return () -> {
                java.util.Iterator<Header> it = headers.iterator();
                return new java.util.Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }
                    @Override
                    public String next() {
                        return it.next().key();
                    }
                };
            };
        }

        @Override
        public String get(Headers headers, String key) {
            Header header = headers.lastHeader(key);
            return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        }
    };

    @Override
    public void open(org.apache.flink.api.common.serialization.DeserializationSchema.InitializationContext context) {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        tracer = GlobalOpenTelemetry.getTracer("flink-kafka-consumer");
    }

    @Override
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<CounterEvent> out) throws IOException {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
        if (tracer == null) {
            tracer = GlobalOpenTelemetry.getTracer("flink-kafka-consumer");
        }

        byte[] value = record.value();
        if (value == null) {
            return;
        }

        try {
            // Extract trace context from Kafka headers
            Context extractedContext = GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .extract(Context.current(), record.headers(), HEADERS_GETTER);

            // Deserialize JSON to CounterEvent
            CounterEvent event = objectMapper.readValue(value, CounterEvent.class);

            // Record deserialization timestamp
            long now = System.currentTimeMillis();
            event.setDeserializedAt(now);

            // Create a span continuing from the extracted context
            Span consumerSpan = tracer.spanBuilder("kafka.consume")
                    .setParent(extractedContext)
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute("messaging.system", "kafka")
                    .setAttribute("messaging.destination", record.topic())
                    .setAttribute("messaging.kafka.partition", record.partition())
                    .setAttribute("messaging.kafka.offset", record.offset())
                    .setAttribute("requestId", event.getRequestId() != null ? event.getRequestId() : "")
                    .setAttribute("customerId", event.getCustomerId() != null ? event.getCustomerId() : "")
                    .setAttribute("session.id", event.getSessionId() != null ? event.getSessionId() : "")
                    .startSpan();

            try (Scope scope = consumerSpan.makeCurrent()) {
                // Set MDC for log correlation
                if (event.getRequestId() != null) {
                    MDC.put("requestId", event.getRequestId());
                }
                String traceId = Span.current().getSpanContext().getTraceId();
                String spanId = Span.current().getSpanContext().getSpanId();
                MDC.put("traceId", traceId);
                MDC.put("spanId", spanId);

                // Calculate kafka transit time
                if (event.getTimestamp() > 0) {
                    long transitTimeMs = now - event.getTimestamp();
                    consumerSpan.setAttribute("kafka.transit_time_ms", transitTimeMs);
                }

                // Store traceparent/tracestate for downstream processing
                Header traceparentHeader = record.headers().lastHeader("traceparent");
                Header tracestateHeader = record.headers().lastHeader("tracestate");
                if (traceparentHeader != null) {
                    event.setTraceparent(new String(traceparentHeader.value(), StandardCharsets.UTF_8));
                }
                if (tracestateHeader != null) {
                    event.setTracestate(new String(tracestateHeader.value(), StandardCharsets.UTF_8));
                }

                out.collect(event);

            } finally {
                MDC.clear();
                consumerSpan.end();
            }

        } catch (Exception e) {
            LOG.error("Failed to deserialize Kafka record: partition={}, offset={}",
                    record.partition(), record.offset(), e);
        }
    }

    @Override
    public TypeInformation<CounterEvent> getProducedType() {
        return TypeInformation.of(CounterEvent.class);
    }
}
