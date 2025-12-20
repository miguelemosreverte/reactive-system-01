package com.reactive.gateway.service;

import com.reactive.gateway.model.CounterCommand;
import com.reactive.gateway.model.CounterResult;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaService {

    private final KafkaTemplate<String, CounterCommand> kafkaTemplate;

    @Value("${app.kafka.topics.commands}")
    private String commandsTopic;

    // Pending transactions awaiting results
    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();

    // Sink for broadcasting results to WebSocket clients
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Publish event fire-and-forget (for benchmarks)
     */
    public String publishFireAndForget(CounterCommand command) {
        long now = System.currentTimeMillis();
        String eventId = command.getEventId() != null ? command.getEventId() : UUID.randomUUID().toString();

        // Get trace context from current span (auto-instrumented by Java Agent)
        Span currentSpan = Span.current();
        String traceId = currentSpan.getSpanContext().getTraceId();
        String spanId = currentSpan.getSpanContext().getSpanId();

        command.setEventId(eventId);
        command.setTraceId(traceId);
        command.setSpanId(spanId);
        command.setTimestamp(now);

        // Convert action to lowercase for Flink compatibility
        if (command.getAction() != null) {
            command.setAction(command.getAction().toLowerCase());
        }

        // Set timing information
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        log.info("Publishing event: eventId={}, sessionId={}, action={}, traceId={}",
                eventId, command.getSessionId(), command.getAction(), traceId);

        kafkaTemplate.send(commandsTopic, command.getSessionId(), command);
        return traceId;
    }

    /**
     * Publish event and wait for result
     */
    public CompletableFuture<CounterResult> publishAndWait(CounterCommand command, long timeoutMs) {
        long now = System.currentTimeMillis();
        String eventId = command.getEventId() != null ? command.getEventId() : UUID.randomUUID().toString();

        // Get trace context from current span (auto-instrumented by Java Agent)
        Span currentSpan = Span.current();
        String traceId = currentSpan.getSpanContext().getTraceId();
        String spanId = currentSpan.getSpanContext().getSpanId();

        command.setEventId(eventId);
        command.setTraceId(traceId);
        command.setSpanId(spanId);
        command.setTimestamp(now);

        // Convert action to lowercase for Flink compatibility
        if (command.getAction() != null) {
            command.setAction(command.getAction().toLowerCase());
        }

        // Set timing information
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        log.info("Publishing event: eventId={}, sessionId={}, action={}, traceId={}",
                eventId, command.getSessionId(), command.getAction(), traceId);

        CompletableFuture<CounterResult> future = new CompletableFuture<>();
        pendingTransactions.put(eventId, future);

        kafkaTemplate.send(commandsTopic, command.getSessionId(), command)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    pendingTransactions.remove(eventId);
                    future.completeExceptionally(ex);
                }
            });

        // Timeout handling
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .whenComplete((result, ex) -> pendingTransactions.remove(eventId));

        return future;
    }

    /**
     * Listen for results from Flink
     */
    @KafkaListener(topics = "${app.kafka.topics.results}", groupId = "gateway-java")
    public void onResult(CounterResult result) {
        // Skip messages with null eventId (old format messages)
        if (result == null || result.getEventId() == null) {
            log.debug("Skipping result with null eventId");
            return;
        }

        log.debug("Received result: eventId={}, newValue={}", result.getEventId(), result.getNewValue());

        // Complete pending transaction if any
        CompletableFuture<CounterResult> future = pendingTransactions.remove(result.getEventId());
        if (future != null) {
            future.complete(result);
        }

        // Broadcast to WebSocket clients
        resultSink.tryEmitNext(result);
    }

    /**
     * Get flux of results for WebSocket streaming
     */
    public Flux<CounterResult> getResultStream() {
        return resultSink.asFlux();
    }

    /**
     * Get pending transaction count (for monitoring)
     */
    public int getPendingCount() {
        return pendingTransactions.size();
    }
}
