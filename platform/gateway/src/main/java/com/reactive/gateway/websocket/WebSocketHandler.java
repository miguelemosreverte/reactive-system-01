package com.reactive.gateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.gateway.model.CounterResult;
import com.reactive.gateway.service.KafkaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketHandler implements org.springframework.web.reactive.socket.WebSocketHandler {

    private final KafkaService kafkaService;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket connected: {}", session.getId());

        return session.send(
            kafkaService.getResultStream()
                .map(result -> {
                    try {
                        String json = objectMapper.writeValueAsString(result);
                        return session.textMessage(json);
                    } catch (Exception e) {
                        log.error("Failed to serialize result", e);
                        return session.textMessage("{}");
                    }
                })
        )
        .doOnTerminate(() -> log.info("WebSocket disconnected: {}", session.getId()));
    }
}
