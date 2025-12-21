package com.reactive.gateway.service;

import com.reactive.gateway.model.CounterCommand;
import com.reactive.gateway.model.CounterResult;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class KafkaService {

    private final KafkaTemplate<String, CounterCommand> kafkaTemplate;
    private final IdGenerator idGenerator;

    @Value("${app.kafka.topics.commands}")
    private String commandsTopic;

    // Pending transactions awaiting results
    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();

    // Sink for broadcasting results to WebSocket clients
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer();

    public KafkaService(KafkaTemplate<String, CounterCommand> kafkaTemplate, IdGenerator idGenerator) {
        this.kafkaTemplate = kafkaTemplate;
        this.idGenerator = idGenerator;
    }

    /**
     * Result containing requestId and eventId for correlation
     */
    public record PublishResult(String requestId, String eventId) {}

    /**
     * Publish event fire-and-forget (for benchmarks)
     */
    public PublishResult publishFireAndForget(CounterCommand command) {
        long now = System.currentTimeMillis();

        // Generate correlation IDs
        String requestId = idGenerator.generateRequestId();
        String eventId = command.getEventId() != null ? command.getEventId() : idGenerator.generateEventId();

        // Set span attributes for Jaeger visibility
        Span currentSpan = Span.current();
        currentSpan.setAttribute("requestId", requestId);
        currentSpan.setAttribute("customerId", command.getCustomerId() != null ? command.getCustomerId() : "");
        currentSpan.setAttribute("eventId", eventId);

        // Add to MDC for structured logging
        MDC.put("requestId", requestId);
        if (command.getCustomerId() != null) {
            MDC.put("customerId", command.getCustomerId());
        }

        // Populate command
        command.setRequestId(requestId);
        command.setEventId(eventId);
        command.setTimestamp(now);

        // Normalize action to lowercase
        if (command.getAction() != null) {
            command.setAction(command.getAction().toLowerCase());
        }

        // Set timing information
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        log.info("Publishing event: requestId={}, customerId={}, sessionId={}, action={}",
                requestId, command.getCustomerId(), command.getSessionId(), command.getAction());

        // Use customerId:sessionId as Kafka key for partitioning
        String kafkaKey = buildKafkaKey(command.getCustomerId(), command.getSessionId());
        kafkaTemplate.send(commandsTopic, kafkaKey, command);

        MDC.remove("requestId");
        MDC.remove("customerId");
        return new PublishResult(requestId, eventId);
    }

    /**
     * Publish event and wait for result
     */
    public CompletableFuture<CounterResult> publishAndWait(CounterCommand command, long timeoutMs) {
        long now = System.currentTimeMillis();

        // Generate correlation IDs
        String requestId = idGenerator.generateRequestId();
        String eventId = command.getEventId() != null ? command.getEventId() : idGenerator.generateEventId();

        // Set span attributes for Jaeger visibility
        Span currentSpan = Span.current();
        currentSpan.setAttribute("requestId", requestId);
        currentSpan.setAttribute("customerId", command.getCustomerId() != null ? command.getCustomerId() : "");
        currentSpan.setAttribute("eventId", eventId);

        // Add to MDC for structured logging
        MDC.put("requestId", requestId);
        if (command.getCustomerId() != null) {
            MDC.put("customerId", command.getCustomerId());
        }

        // Populate command
        command.setRequestId(requestId);
        command.setEventId(eventId);
        command.setTimestamp(now);

        // Normalize action to lowercase
        if (command.getAction() != null) {
            command.setAction(command.getAction().toLowerCase());
        }

        // Set timing information
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        log.info("Publishing event: requestId={}, customerId={}, sessionId={}, action={}",
                requestId, command.getCustomerId(), command.getSessionId(), command.getAction());

        CompletableFuture<CounterResult> future = new CompletableFuture<>();
        pendingTransactions.put(eventId, future);

        // Use customerId:sessionId as Kafka key for partitioning
        String kafkaKey = buildKafkaKey(command.getCustomerId(), command.getSessionId());
        kafkaTemplate.send(commandsTopic, kafkaKey, command)
            .whenComplete((result, ex) -> {
                MDC.remove("requestId");
                MDC.remove("customerId");
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
     * Build Kafka key for partitioning: customerId:sessionId
     */
    private String buildKafkaKey(String customerId, String sessionId) {
        if (customerId != null && !customerId.isEmpty()) {
            return customerId + ":" + sessionId;
        }
        return sessionId;
    }

    /**
     * Listen for results from Flink
     */
    @KafkaListener(topics = "${app.kafka.topics.results}", groupId = "gateway-java")
    public void onResult(CounterResult result) {
        if (result == null || result.getEventId() == null) {
            log.debug("Skipping result with null eventId");
            return;
        }

        log.debug("Received result: eventId={}, customerId={}, value={}",
                result.getEventId(), result.getCustomerId(), result.getNewValue());

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
