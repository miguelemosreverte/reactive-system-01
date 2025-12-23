package com.reactive.counter.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.counter.service.ResultConsumerService;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket handler for streaming counter results to UI clients.
 * Broadcasts all results from the Kafka consumer to connected clients.
 */
@Component
@ConditionalOnProperty(name = "app.websocket-enabled", havingValue = "true", matchIfMissing = true)
public class CounterWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CounterWebSocketHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger connectionCount = new AtomicInteger(0);

    private final ResultConsumerService resultConsumerService;

    public CounterWebSocketHandler(ResultConsumerService resultConsumerService) {
        this.resultConsumerService = resultConsumerService;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        int connNum = connectionCount.incrementAndGet();

        SpanHandle connectSpan = Log.consumerSpan("websocket.connect");
        connectSpan.attr("websocket.session_id", sessionId);
        connectSpan.attr("websocket.connection_count", connNum);

        log.info("WebSocket connected: sessionId={}, totalConnections={}", sessionId, connNum);

        // Send initial connection message
        String welcomeMessage;
        try {
            welcomeMessage = mapper.writeValueAsString(Map.of(
                    "type", "connected",
                    "sessionId", sessionId,
                    "message", "Connected to counter stream"
            ));
        } catch (Exception e) {
            welcomeMessage = "{\"type\":\"connected\"}";
        }

        connectSpan.success();

        // Stream results to this client
        return session.send(
                Mono.just(session.textMessage(welcomeMessage))
                        .concatWith(
                                resultConsumerService.getResultStream()
                                        .map(result -> {
                                            try {
                                                String json = mapper.writeValueAsString(Map.of(
                                                        "type", "result",
                                                        "data", result
                                                ));
                                                return session.textMessage(json);
                                            } catch (Exception e) {
                                                log.error("Failed to serialize result for WebSocket", e);
                                                return session.textMessage("{\"type\":\"error\"}");
                                            }
                                        })
                        )
        ).doOnTerminate(() -> {
            int remaining = connectionCount.decrementAndGet();
            log.info("WebSocket disconnected: sessionId={}, remainingConnections={}", sessionId, remaining);
        });
    }

    /**
     * Get current connection count (for monitoring).
     */
    public static int getConnectionCount() {
        return connectionCount.get();
    }
}
