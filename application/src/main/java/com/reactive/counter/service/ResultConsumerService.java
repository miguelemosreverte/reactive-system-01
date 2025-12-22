package com.reactive.counter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Consumes results from Kafka and broadcasts to WebSocket clients.
 * Supports both fire-and-forget and wait-for-result delivery modes.
 */
@Service
public class ResultConsumerService {

    private static final Logger log = LoggerFactory.getLogger(ResultConsumerService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Tracer tracer;

    // Pending transactions awaiting results (for wait-for-result mode)
    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();

    // Sink for broadcasting results to WebSocket clients
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer();

    @Value("${app.result-timeout-ms:5000}")
    private long resultTimeoutMs;

    // TextMapGetter for extracting trace context from Kafka headers
    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            return () -> java.util.stream.StreamSupport.stream(headers.spliterator(), false)
                    .map(Header::key)
                    .iterator();
        }

        @Override
        public String get(Headers headers, String key) {
            Header header = headers.lastHeader(key);
            return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        }
    };

    public ResultConsumerService() {
        this.tracer = GlobalOpenTelemetry.getTracer("result-consumer");
    }

    /**
     * Listen for results from Flink with full trace context extraction.
     */
    @KafkaListener(topics = "${app.kafka.topics.results}", groupId = "counter-app-results")
    public void onResult(ConsumerRecord<String, byte[]> record) {
        CounterResult result;
        try {
            result = mapper.readValue(record.value(), CounterResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize result: {}", e.getMessage());
            return;
        }

        if (result == null || result.getEventId() == null) {
            log.debug("Skipping result with null eventId");
            return;
        }

        // Extract trace context from Kafka headers
        Context extractedContext = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), record.headers(), HEADERS_GETTER);

        // Create consumer span with extracted context as parent
        Span consumerSpan = tracer.spanBuilder("counter-results consume")
                .setParent(extractedContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", record.topic())
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.kafka.partition", record.partition())
                .setAttribute("messaging.kafka.offset", record.offset())
                .setAttribute("eventId", result.getEventId())
                .setAttribute("requestId", result.getRequestId() != null ? result.getRequestId() : "")
                .startSpan();

        try {
            // Set MDC for log correlation
            if (result.getRequestId() != null) {
                MDC.put("requestId", result.getRequestId());
            }
            MDC.put("traceId", consumerSpan.getSpanContext().getTraceId());

            log.info("Received result from Flink: eventId={}, sessionId={}, value={}, alert={}",
                    result.getEventId(), result.getSessionId(), result.getCurrentValue(), result.getAlert());

            // Complete pending transaction if any (for wait-for-result mode)
            CompletableFuture<CounterResult> future = pendingTransactions.remove(result.getEventId());
            if (future != null) {
                consumerSpan.setAttribute("delivery.mode", "wait-for-result");
                consumerSpan.setAttribute("transaction.completed", true);
                future.complete(result);
            }

            // Broadcast to WebSocket clients
            Span wsSpan = tracer.spanBuilder("websocket.broadcast")
                    .setParent(Context.current().with(consumerSpan))
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("websocket.operation", "broadcast")
                    .setAttribute("eventId", result.getEventId())
                    .setAttribute("sessionId", result.getSessionId())
                    .startSpan();

            try {
                Sinks.EmitResult emitResult = resultSink.tryEmitNext(result);
                if (emitResult.isSuccess()) {
                    wsSpan.setStatus(StatusCode.OK);
                } else {
                    wsSpan.setAttribute("emit.result", emitResult.name());
                }
            } finally {
                wsSpan.end();
            }

            consumerSpan.setStatus(StatusCode.OK);

        } catch (Exception e) {
            consumerSpan.setStatus(StatusCode.ERROR, e.getMessage());
            consumerSpan.recordException(e);
            log.error("Error processing result: {}", e.getMessage(), e);
        } finally {
            MDC.remove("requestId");
            MDC.remove("traceId");
            consumerSpan.end();
        }
    }

    /**
     * Register a pending transaction for wait-for-result mode.
     */
    public CompletableFuture<CounterResult> registerPendingTransaction(String eventId) {
        CompletableFuture<CounterResult> future = new CompletableFuture<>();
        pendingTransactions.put(eventId, future);

        // Timeout handling
        future.orTimeout(resultTimeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> pendingTransactions.remove(eventId));

        return future;
    }

    /**
     * Get flux of results for WebSocket streaming.
     */
    public Flux<CounterResult> getResultStream() {
        return resultSink.asFlux();
    }

    /**
     * Get pending transaction count (for monitoring).
     */
    public int getPendingCount() {
        return pendingTransactions.size();
    }

    /**
     * Result model matching Flink's CounterResult.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class CounterResult {
        private String requestId;
        private String customerId;
        private String eventId;
        private String sessionId;
        private int currentValue;
        private String alert;
        private String message;
        private long timestamp;
        private String traceparent;
        private String tracestate;

        // Getters and setters
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getCustomerId() { return customerId; }
        public void setCustomerId(String customerId) { this.customerId = customerId; }
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public int getCurrentValue() { return currentValue; }
        public void setCurrentValue(int currentValue) { this.currentValue = currentValue; }
        public String getAlert() { return alert; }
        public void setAlert(String alert) { this.alert = alert; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getTraceparent() { return traceparent; }
        public void setTraceparent(String traceparent) { this.traceparent = traceparent; }
        public String getTracestate() { return tracestate; }
        public void setTracestate(String tracestate) { this.tracestate = tracestate; }
    }
}
