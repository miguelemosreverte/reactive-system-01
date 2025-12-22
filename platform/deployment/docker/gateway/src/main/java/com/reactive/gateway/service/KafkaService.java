package com.reactive.gateway.service;

import com.reactive.gateway.model.CounterCommand;
import com.reactive.gateway.model.CounterResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
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

/**
 * Kafka service for command publishing and result consumption.
 *
 * Clean service - business logic only.
 * Manual span creation only where OTel auto-instrumentation doesn't cover (Kafka consumer).
 */
@Service
@Slf4j
public class KafkaService {

    private final KafkaTemplate<String, CounterCommand> kafkaTemplate;
    private final IdGenerator idGenerator;
    private final Tracer tracer;

    @Value("${app.kafka.topics.commands}")
    private String commandsTopic;

    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer();

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            return () -> java.util.stream.StreamSupport.stream(headers.spliterator(), false)
                    .map(Header::key).iterator();
        }
        @Override
        public String get(Headers headers, String key) {
            Header header = headers.lastHeader(key);
            return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
        }
    };

    public KafkaService(KafkaTemplate<String, CounterCommand> kafkaTemplate, IdGenerator idGenerator) {
        this.kafkaTemplate = kafkaTemplate;
        this.idGenerator = idGenerator;
        this.tracer = GlobalOpenTelemetry.get().getTracer("gateway-kafka");
    }

    public record PublishResult(String requestId, String eventId) {}

    /**
     * Publish command fire-and-forget.
     */
    public PublishResult publishFireAndForget(CounterCommand command) {
        String requestId = idGenerator.generateRequestId();
        String eventId = command.getEventId() != null ? command.getEventId() : idGenerator.generateEventId();
        long now = System.currentTimeMillis();

        // Add business attributes to current span
        addSpanAttributes(requestId, command.getCustomerId(), eventId);

        // Prepare command
        command.setRequestId(requestId);
        command.setEventId(eventId);
        command.setTimestamp(now);
        if (command.getAction() != null) command.setAction(command.getAction().toLowerCase());
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        log.info("Publishing: session={}, action={}", command.getSessionId(), command.getAction());

        kafkaTemplate.send(createRecordWithTraceContext(commandsTopic, kafkaKey(command), command));

        return new PublishResult(requestId, eventId);
    }

    /**
     * Publish command and wait for result.
     */
    public CompletableFuture<CounterResult> publishAndWait(CounterCommand command, long timeoutMs) {
        String requestId = idGenerator.generateRequestId();
        String eventId = command.getEventId() != null ? command.getEventId() : idGenerator.generateEventId();
        long now = System.currentTimeMillis();

        addSpanAttributes(requestId, command.getCustomerId(), eventId);

        command.setRequestId(requestId);
        command.setEventId(eventId);
        command.setTimestamp(now);
        if (command.getAction() != null) command.setAction(command.getAction().toLowerCase());
        command.setTiming(CounterCommand.EventTiming.builder()
                .gatewayReceivedAt(now)
                .gatewayPublishedAt(System.currentTimeMillis())
                .build());

        log.info("Publishing (wait): session={}, action={}", command.getSessionId(), command.getAction());

        CompletableFuture<CounterResult> future = new CompletableFuture<>();
        pendingTransactions.put(eventId, future);

        kafkaTemplate.send(createRecordWithTraceContext(commandsTopic, kafkaKey(command), command))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        pendingTransactions.remove(eventId);
                        future.completeExceptionally(ex);
                    }
                });

        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> pendingTransactions.remove(eventId));

        return future;
    }

    /**
     * Kafka result consumer - creates span since OTel doesn't auto-instrument @KafkaListener.
     */
    @KafkaListener(topics = "${app.kafka.topics.results}", groupId = "gateway-java")
    public void onResult(ConsumerRecord<String, CounterResult> record) {
        CounterResult result = record.value();
        if (result == null || result.getEventId() == null) return;

        // Extract trace context and create consumer span
        Context parentContext = GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), record.headers(), HEADERS_GETTER);

        Span span = tracer.spanBuilder("counter-results consume")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", record.topic())
                .setAttribute("eventId", result.getEventId())
                .startSpan();

        try (var scope = span.makeCurrent()) {
            log.info("Result: eventId={}, value={}", result.getEventId(), result.getNewValue());

            // Complete pending transaction
            CompletableFuture<CounterResult> future = pendingTransactions.remove(result.getEventId());
            if (future != null) future.complete(result);

            // Broadcast to WebSocket
            resultSink.tryEmitNext(result);
        } finally {
            span.end();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void addSpanAttributes(String requestId, String customerId, String eventId) {
        Span span = Span.current();
        span.setAttribute("requestId", requestId);
        span.setAttribute("customerId", customerId != null ? customerId : "");
        span.setAttribute("eventId", eventId);
    }

    private String kafkaKey(CounterCommand command) {
        String customerId = command.getCustomerId();
        String sessionId = command.getSessionId();
        return (customerId != null && !customerId.isEmpty())
                ? customerId + ":" + sessionId
                : sessionId;
    }

    private ProducerRecord<String, CounterCommand> createRecordWithTraceContext(
            String topic, String key, CounterCommand value) {
        RecordHeaders headers = new RecordHeaders();
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(
                Context.current(), headers,
                (carrier, k, v) -> {
                    if (carrier != null && k != null && v != null) {
                        carrier.add(k, v.getBytes(StandardCharsets.UTF_8));
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
