package com.reactive.counter.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.serialization.AvroCounterEventCodec;
import com.reactive.counter.service.ResultConsumerService;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import reactor.core.Disposable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bidirectional WebSocket handler for the Counter UI.
 *
 * Receives: counter-action messages from UI
 * Sends: action-ack (immediate), counter-update (from Kafka results)
 *
 * This enables the Event Flow Visualization in the UI to work end-to-end.
 */
@Component
@ConditionalOnProperty(name = "app.websocket-enabled", havingValue = "true", matchIfMissing = true)
public class CounterWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CounterWebSocketHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final IdGenerator idGenerator = IdGenerator.getInstance();

    private final ResultConsumerService resultConsumerService;

    @Value("${spring.kafka.bootstrap-servers:kafka:29092}")
    private String kafkaBootstrap;

    @Value("${app.kafka.topics.events:counter-events}")
    private String eventsTopic;

    private KafkaPublisher<CounterEvent> publisher;

    public CounterWebSocketHandler(ResultConsumerService resultConsumerService) {
        this.resultConsumerService = resultConsumerService;
    }

    @PostConstruct
    void init() {
        publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(eventsTopic)
                .codec(AvroCounterEventCodec.create())
                .keyExtractor(CounterEvent::eventId)
                .fireAndForget());
        log.info("WebSocket handler initialized with Kafka publisher to {}", eventsTopic);
    }

    @PreDestroy
    void cleanup() {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String wsSessionId = UUID.randomUUID().toString().substring(0, 8);
        int connNum = connectionCount.incrementAndGet();

        SpanHandle connectSpan = Log.consumerSpan("websocket.connect");
        connectSpan.attr("websocket.session_id", wsSessionId);
        connectSpan.attr("websocket.connection_count", connNum);

        log.info("WebSocket connected: sessionId={}, totalConnections={}", wsSessionId, connNum);
        connectSpan.success();

        // Sink for outgoing messages (ack + results)
        Sinks.Many<String> outgoingSink = Sinks.many().unicast().onBackpressureBuffer();

        // Send welcome message
        try {
            String welcomeMessage = mapper.writeValueAsString(Map.of(
                    "type", "connected",
                    "sessionId", wsSessionId,
                    "message", "Connected to counter stream"
            ));
            outgoingSink.tryEmitNext(welcomeMessage);
        } catch (Exception e) {
            outgoingSink.tryEmitNext("{\"type\":\"connected\"}");
        }

        // EAGERLY subscribe to result stream and push through outgoingSink
        // This ensures results are consumed immediately regardless of WebSocket send backpressure
        log.info("Setting up eager subscription to result stream: sessionId={}", wsSessionId);
        Disposable resultSubscription = resultConsumerService.getResultStream()
                .doOnSubscribe(s -> log.info("WebSocket eagerly subscribed to result stream: sessionId={}", wsSessionId))
                .filter(result -> {
                    boolean matches = wsSessionId.equals(result.sessionId());
                    log.info("WebSocket filter check: wsSessionId={}, resultSessionId={}, matches={}",
                            wsSessionId, result.sessionId(), matches);
                    return matches;
                })
                .subscribe(result -> {
                    try {
                        log.info("Pushing counter-update to outgoing sink: sessionId={}, value={}, alert={}",
                                wsSessionId, result.currentValue(), result.alert());
                        String message = mapper.writeValueAsString(Map.of(
                                "type", "counter-update",
                                "sessionId", result.sessionId(),
                                "data", Map.of(
                                        "currentValue", result.currentValue(),
                                        "alert", result.alert(),
                                        "message", result.message(),
                                        "traceId", result.traceparent() != null && result.traceparent().length() > 36
                                                ? result.traceparent().substring(3, 35) : ""
                                )
                        ));
                        outgoingSink.tryEmitNext(message);
                    } catch (Exception e) {
                        log.error("Failed to serialize result for WebSocket", e);
                        outgoingSink.tryEmitNext("{\"type\":\"error\"}");
                    }
                }, error -> log.error("Result stream error for session {}: {}", wsSessionId, error.getMessage()));

        // Process incoming messages from UI
        Mono<Void> input = session.receive()
                .doOnNext(msg -> handleIncomingMessage(msg, wsSessionId, outgoingSink))
                .doOnError(e -> log.error("WebSocket receive error: {}", e.getMessage()))
                .then();

        // Stream outgoing messages (acks + results pushed via eager subscription) to UI
        Mono<Void> output = session.send(
                outgoingSink.asFlux().map(session::textMessage)
        );

        // Run input and output in parallel, complete when either finishes
        return Mono.zip(input, output)
                .doOnTerminate(() -> {
                    // Clean up the eager subscription
                    resultSubscription.dispose();
                    int remaining = connectionCount.decrementAndGet();
                    log.info("WebSocket disconnected: sessionId={}, remainingConnections={}", wsSessionId, remaining);
                })
                .then();
    }

    private void handleIncomingMessage(WebSocketMessage msg, String wsSessionId, Sinks.Many<String> outgoingSink) {
        if (msg.getType() != WebSocketMessage.Type.TEXT) {
            return;
        }

        String payload = msg.getPayloadAsText();
        log.debug("WebSocket received: sessionId={}, message={}", wsSessionId, payload);

        try {
            JsonNode json = mapper.readTree(payload);
            String type = json.has("type") ? json.get("type").asText() : "";

            if ("counter-action".equals(type)) {
                String action = json.has("action") ? json.get("action").asText() : "increment";
                int value = json.has("value") ? json.get("value").asInt() : 1;

                // Generate IDs
                String requestId = idGenerator.generateRequestId();
                String eventId = idGenerator.generateEventId();
                String traceId = Log.traceId();

                // Create and publish event to Kafka
                CounterEvent event = CounterEvent.create(
                        requestId, "", eventId, wsSessionId, action, value);
                publisher.publishFireAndForget(event);

                log.debug("Published counter event: action={}, value={}, eventId={}", action, value, eventId);

                // Send immediate acknowledgment
                String ack = mapper.writeValueAsString(Map.of(
                        "type", "action-ack",
                        "traceId", traceId != null ? traceId : eventId
                ));
                outgoingSink.tryEmitNext(ack);
            }
        } catch (Exception e) {
            log.error("Failed to process WebSocket message: {}", e.getMessage());
        }
    }

    /**
     * Get current connection count (for monitoring).
     */
    public static int getConnectionCount() {
        return connectionCount.get();
    }
}
