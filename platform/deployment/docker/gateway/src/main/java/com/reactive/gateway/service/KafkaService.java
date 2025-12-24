package com.reactive.gateway.service;

import com.reactive.diagnostic.DiagnosticCollector;
import com.reactive.gateway.model.CounterCommand;
import com.reactive.gateway.model.CounterResult;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.reactive.platform.Opt.or;

/**
 * Kafka service for command publishing and result consumption.
 *
 * Uses platform Log API for observability - no third-party OTel types leak into this service.
 */
@Service
@Slf4j
public class KafkaService {

    private final KafkaTemplate<String, CounterCommand> kafkaTemplate;
    private final IdGenerator idGenerator;
    private final DiagnosticCollector diagnosticCollector;

    @Value("${app.kafka.topics.commands}")
    private String commandsTopic;

    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer();

    public KafkaService(KafkaTemplate<String, CounterCommand> kafkaTemplate,
                        IdGenerator idGenerator,
                        DiagnosticCollector diagnosticCollector) {
        this.kafkaTemplate = kafkaTemplate;
        this.idGenerator = idGenerator;
        this.diagnosticCollector = diagnosticCollector;
    }

    public record PublishResult(String requestId, String eventId) {}

    /**
     * Publish command fire-and-forget.
     */
    public PublishResult publishFireAndForget(CounterCommand command) {
        long startTime = System.nanoTime();
        String requestId = idGenerator.generateRequestId();
        String eventId = or(command.getEventId(), idGenerator.generateEventId());
        long now = System.currentTimeMillis();

        // Record http_receive stage
        diagnosticCollector.recordStageEvent("http_receive", (System.nanoTime() - startTime) / 1_000_000.0);
        long validationStart = System.nanoTime();

        // Add business attributes to current span using platform Log API
        addSpanAttributes(requestId, command.getCustomerId(), eventId);

        // Prepare command - normalize optional fields
        command.setRequestId(requestId);
        command.setEventId(eventId);
        command.setTimestamp(now);
        command.setAction(or(command.getAction(), "increment").toLowerCase());
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        // Record validation stage
        diagnosticCollector.recordStageEvent("validation", (System.nanoTime() - validationStart) / 1_000_000.0);
        long serializationStart = System.nanoTime();

        log.info("Publishing: session={}, action={}", command.getSessionId(), command.getAction());

        // Record serialization and kafka_produce stages
        ProducerRecord<String, CounterCommand> record = createRecordWithTraceContext(commandsTopic, kafkaKey(command), command);
        diagnosticCollector.recordStageEvent("serialization", (System.nanoTime() - serializationStart) / 1_000_000.0);
        long kafkaStart = System.nanoTime();

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    double kafkaLatency = (System.nanoTime() - kafkaStart) / 1_000_000.0;
                    diagnosticCollector.recordStageEvent("kafka_produce", kafkaLatency);
                    diagnosticCollector.recordDependencyCall("kafka", kafkaLatency, ex == null);

                    if (ex != null) {
                        diagnosticCollector.recordError("kafka_publish", ex.getMessage());
                    }
                });

        // Record full event latency (approximate, doesn't include kafka ack)
        double totalLatency = (System.nanoTime() - startTime) / 1_000_000.0;
        diagnosticCollector.recordEvent(100, totalLatency); // 100 bytes estimate

        return new PublishResult(requestId, eventId);
    }

    /**
     * Publish command and wait for result.
     */
    public CompletableFuture<CounterResult> publishAndWait(CounterCommand command, long timeoutMs) {
        long startTime = System.nanoTime();
        String requestId = idGenerator.generateRequestId();
        String eventId = or(command.getEventId(), idGenerator.generateEventId());
        long now = System.currentTimeMillis();

        // Record http_receive stage
        diagnosticCollector.recordStageEvent("http_receive", (System.nanoTime() - startTime) / 1_000_000.0);
        long validationStart = System.nanoTime();

        addSpanAttributes(requestId, command.getCustomerId(), eventId);

        command.setRequestId(requestId);
        command.setEventId(eventId);
        command.setTimestamp(now);
        command.setAction(or(command.getAction(), "increment").toLowerCase());
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        // Record validation stage
        diagnosticCollector.recordStageEvent("validation", (System.nanoTime() - validationStart) / 1_000_000.0);
        long serializationStart = System.nanoTime();

        log.info("Publishing (wait): session={}, action={}", command.getSessionId(), command.getAction());

        CompletableFuture<CounterResult> future = new CompletableFuture<>();
        pendingTransactions.put(eventId, future);

        // Record serialization
        ProducerRecord<String, CounterCommand> record = createRecordWithTraceContext(commandsTopic, kafkaKey(command), command);
        diagnosticCollector.recordStageEvent("serialization", (System.nanoTime() - serializationStart) / 1_000_000.0);
        long kafkaStart = System.nanoTime();

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    double kafkaLatency = (System.nanoTime() - kafkaStart) / 1_000_000.0;
                    diagnosticCollector.recordStageEvent("kafka_produce", kafkaLatency);
                    diagnosticCollector.recordDependencyCall("kafka", kafkaLatency, ex == null);

                    if (ex != null) {
                        diagnosticCollector.recordError("kafka_publish", ex.getMessage());
                        pendingTransactions.remove(eventId);
                        future.completeExceptionally(ex);
                    }
                });

        // Record event and track full round-trip when result arrives
        final long eventStartTime = startTime;
        future.whenComplete((result, ex) -> {
            double totalLatency = (System.nanoTime() - eventStartTime) / 1_000_000.0;
            diagnosticCollector.recordEvent(100, totalLatency);

            if (ex != null && !(ex instanceof java.util.concurrent.TimeoutException)) {
                diagnosticCollector.recordError("request_failed", ex.getMessage());
            }
        });

        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> pendingTransactions.remove(eventId));

        return future;
    }

    /**
     * Kafka result consumer - uses platform Log API for consumer span.
     */
    @KafkaListener(topics = "${app.kafka.topics.results}", groupId = "gateway-java")
    public void onResult(ConsumerRecord<String, CounterResult> record) {
        CounterResult result = record.value();
        if (result == null) return;
        if (result.eventId().isEmpty()) return;

        // Auto-extracts business IDs and manages MDC
        SpanHandle span = Log.tracedReceive("counter-results consume", result, Log.SpanType.CONSUMER);
        span.attr("messaging.system", "kafka");
        span.attr("messaging.destination", record.topic());

        try {
            log.info("Result: eventId={}, value={}", result.eventId(), result.getNewValue());

            // Complete pending transaction
            CompletableFuture<CounterResult> future = pendingTransactions.remove(result.eventId());
            if (future != null) future.complete(result);

            // Broadcast to WebSocket
            resultSink.tryEmitNext(result);
            span.success();
        } catch (Exception e) {
            span.failure(e);
            throw e;
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Add business attributes to current span using platform Log API.
     * No third-party OTel types leak into this service.
     */
    private void addSpanAttributes(String requestId, String customerId, String eventId) {
        Log.attr("requestId", requestId);
        Log.attr("customerId", or(customerId, ""));
        Log.attr("eventId", eventId);
    }

    private String kafkaKey(CounterCommand command) {
        String customerId = or(command.getCustomerId(), "");
        String sessionId = or(command.getSessionId(), "default");
        return customerId.isEmpty() ? sessionId : customerId + ":" + sessionId;
    }

    private ProducerRecord<String, CounterCommand> createRecordWithTraceContext(
            String topic, String key, CounterCommand value) {
        RecordHeaders headers = new RecordHeaders();
        // Inject trace context using platform Log API
        Map<String, String> traceHeaders = Log.context().traceparent().isEmpty()
                ? Map.of()
                : Map.of("traceparent", Log.context().traceparent(),
                         "tracestate", Log.context().tracestate());
        traceHeaders.forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.add(k, v.getBytes(StandardCharsets.UTF_8));
            }
        });
        return new ProducerRecord<>(topic, null, key, value, headers);
    }

    public Flux<CounterResult> getResultStream() {
        return resultSink.asFlux();
    }

    public int getPendingCount() {
        return pendingTransactions.size();
    }
}
