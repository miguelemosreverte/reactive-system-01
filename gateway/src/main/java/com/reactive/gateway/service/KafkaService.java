package com.reactive.gateway.service;

import com.reactive.gateway.model.CounterCommand;
import com.reactive.gateway.model.CounterResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.MDC;
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

@Service
@Slf4j
public class KafkaService {

    private final KafkaTemplate<String, CounterCommand> kafkaTemplate;
    private final IdGenerator idGenerator;
    private final Tracer tracer;

    @Value("${app.kafka.topics.commands}")
    private String commandsTopic;

    // Pending transactions awaiting results
    private final Map<String, CompletableFuture<CounterResult>> pendingTransactions = new ConcurrentHashMap<>();

    // Sink for broadcasting results to WebSocket clients
    private final Sinks.Many<CounterResult> resultSink = Sinks.many().multicast().onBackpressureBuffer();

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

    public KafkaService(KafkaTemplate<String, CounterCommand> kafkaTemplate, IdGenerator idGenerator) {
        this.kafkaTemplate = kafkaTemplate;
        this.idGenerator = idGenerator;
        this.tracer = GlobalOpenTelemetry.getTracer("gateway-consumer");
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

        // Create ProducerRecord with trace context headers
        ProducerRecord<String, CounterCommand> record = createRecordWithTraceContext(
                commandsTopic, kafkaKey, command);
        kafkaTemplate.send(record);

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

        // Create ProducerRecord with trace context headers
        ProducerRecord<String, CounterCommand> record = createRecordWithTraceContext(
                commandsTopic, kafkaKey, command);
        kafkaTemplate.send(record)
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
     * Create a ProducerRecord with W3C trace context headers injected.
     * This ensures trace propagation through Kafka to Flink.
     */
    private ProducerRecord<String, CounterCommand> createRecordWithTraceContext(
            String topic, String key, CounterCommand value) {
        RecordHeaders headers = new RecordHeaders();

        // Inject trace context using OTel propagator
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(
                Context.current(),
                headers,
                (carrier, headerKey, headerValue) -> {
                    if (carrier != null && headerKey != null && headerValue != null) {
                        carrier.add(headerKey, headerValue.getBytes(StandardCharsets.UTF_8));
                    }
                }
        );

        return new ProducerRecord<>(topic, null, key, value, headers);
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
     * Listen for results from Flink with full trace context extraction.
     * This creates a CONSUMER span that continues the distributed trace.
     */
    @KafkaListener(topics = "${app.kafka.topics.results}", groupId = "gateway-java")
    public void onResult(ConsumerRecord<String, CounterResult> record) {
        CounterResult result = record.value();
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

            log.info("Received result from Flink: eventId={}, customerId={}, value={}, sessionId={}",
                    result.getEventId(), result.getCustomerId(), result.getNewValue(), result.getSessionId());

            // Complete pending transaction if any
            CompletableFuture<CounterResult> future = pendingTransactions.remove(result.getEventId());
            if (future != null) {
                consumerSpan.setAttribute("transaction.pending", true);
                future.complete(result);
            }

            // Broadcast to WebSocket clients
            Span wsSpan = tracer.spanBuilder("websocket.broadcast")
                    .setParent(Context.current().with(consumerSpan))
                    .setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("websocket.operation", "broadcast")
                    .setAttribute("eventId", result.getEventId())
                    .startSpan();

            try {
                resultSink.tryEmitNext(result);
                wsSpan.setStatus(StatusCode.OK);
            } finally {
                wsSpan.end();
            }

            consumerSpan.setStatus(StatusCode.OK);

        } catch (Exception e) {
            consumerSpan.setStatus(StatusCode.ERROR, e.getMessage());
            consumerSpan.recordException(e);
            throw e;
        } finally {
            MDC.remove("requestId");
            MDC.remove("traceId");
            consumerSpan.end();
        }
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
