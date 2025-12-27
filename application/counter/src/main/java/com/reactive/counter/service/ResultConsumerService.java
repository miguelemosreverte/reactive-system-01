package com.reactive.counter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.platform.kafka.TracedKafka;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import com.reactive.platform.observe.Traceable;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.reactive.platform.Opt.or;

/**
 * Consumes results from Kafka and broadcasts to WebSocket clients.
 * Supports both fire-and-forget and wait-for-result delivery modes.
 */
@Service
public class ResultConsumerService {

    private static final Logger log = LoggerFactory.getLogger(ResultConsumerService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // Pending transactions awaiting results (for wait-for-result mode)
    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();

    // Sink for broadcasting results to WebSocket clients (bounded buffer to prevent OOM)
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer(1000);

    // Callback for state updates (set by CounterController)
    private java.util.function.Consumer<CounterResult> stateUpdateCallback;

    @Value("${app.result-timeout-ms:5000}")
    private long resultTimeoutMs;

    /**
     * Register callback for state updates.
     */
    public void setStateUpdateCallback(java.util.function.Consumer<CounterResult> callback) {
        this.stateUpdateCallback = callback;
    }

    /**
     * Listen for results from Flink with full trace context extraction.
     */
    @KafkaListener(topics = "${app.kafka.topics.alerts}", groupId = "counter-app-alerts")
    public void onResult(ConsumerRecord<String, byte[]> record) {
        CounterResult result;
        try {
            result = mapper.readValue(record.value(), CounterResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize result: {}", e.getMessage());
            return;
        }

        if (result == null || result.eventId().isEmpty()) {
            log.debug("Skipping result with empty eventId");
            return;
        }

        // All Kafka ceremony hidden in TracedKafka.receive
        SpanHandle span = TracedKafka.receive(
                TracedKafka.Context.of(record.topic(), record.partition(), record.offset(),
                        TracedKafka.header(record.headers(), "traceparent"),
                        TracedKafka.header(record.headers(), "tracestate")),
                result);

        try {
            log.debug("Received result from Flink: eventId={}, sessionId={}, value={}, alert={}",
                    result.eventId(), result.sessionId(), result.currentValue(), result.alert());

            // Complete pending transaction if any (for wait-for-result mode)
            CompletableFuture<CounterResult> future = pendingTransactions.remove(result.eventId());
            if (future != null) {
                span.attr("delivery.mode", "wait-for-result");
                span.attr("transaction.completed", "true");
                future.complete(result);
            }

            // Broadcast to WebSocket clients
            broadcastToWebSocket(span, result);

            // Update local state cache
            if (stateUpdateCallback != null) {
                stateUpdateCallback.accept(result);
            }

            span.success();
        } catch (Exception e) {
            span.failure(e);
            log.error("Error processing result: {}", e.getMessage(), e);
        } finally {
            Log.clearMdc();
        }
    }

    private void broadcastToWebSocket(SpanHandle parentSpan, CounterResult result) {
        SpanHandle wsSpan = parentSpan.childSpan("websocket.broadcast", Log.SpanType.PRODUCER);
        wsSpan.attr("websocket.operation", "broadcast");

        try {
            Sinks.EmitResult emitResult = resultSink.tryEmitNext(result);
            if (!emitResult.isSuccess()) {
                wsSpan.attr("emit.result", emitResult.name());
            }
            wsSpan.success();
        } catch (Exception e) {
            wsSpan.failure(e);
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
     * Record with compact constructor for boundary normalization.
     * Implements Traceable for automatic span attribute extraction.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CounterResult(
            String requestId,
            String customerId,
            String eventId,
            String sessionId,
            int currentValue,
            String alert,
            String message,
            long timestamp,
            String traceparent,
            String tracestate
    ) implements Traceable {
        public CounterResult {
            requestId = or(requestId, "");
            customerId = or(customerId, "");
            eventId = or(eventId, "");
            sessionId = or(sessionId, "");
            alert = or(alert, "");
            message = or(message, "");
            traceparent = or(traceparent, "");
            tracestate = or(tracestate, "");
        }
    }
}
