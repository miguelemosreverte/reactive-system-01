package com.reactive.counter.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * WebSocket configuration for real-time result streaming.
 * Can be disabled via app.websocket-enabled=false for pure fire-and-forget mode.
 */
@Configuration
@ConditionalOnProperty(name = "app.websocket-enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketHandlerMapping(CounterWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws", handler));
        mapping.setOrder(-1); // High priority
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
