package com.reactive.gateway.controller;

import com.reactive.gateway.model.CounterCommand;
import com.reactive.gateway.model.CounterResult;
import com.reactive.gateway.service.KafkaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class CounterController {

    private final KafkaService kafkaService;

    private static final long DEFAULT_TIMEOUT_MS = 30000;

    /**
     * Standard counter endpoint - publishes to Kafka and waits for result.
     * This is the TRUE end-to-end path: HTTP → Kafka → Flink → Response
     */
    @PostMapping("/counter")
    public Mono<ResponseEntity<Map<String, Object>>> counter(@RequestBody CounterCommand command) {
        String sessionId = command.getSessionId() != null ? command.getSessionId() : "default";
        command.setSessionId(sessionId);

        // Always wait for full processing - no shortcuts
        CompletableFuture<CounterResult> future = kafkaService.publishAndWait(command, DEFAULT_TIMEOUT_MS);

        return Mono.fromFuture(future)
            .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                "success", true,
                "traceId", result.getTraceId(),
                "eventId", result.getEventId(),
                "result", result
            )))
            .onErrorResume(ex -> {
                log.error("Counter operation failed", ex);
                String errorMessage = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                return Mono.just(ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", errorMessage
                )));
            });
    }

    /**
     * Fast counter endpoint - fire-and-forget, no tracing overhead
     */
    @PostMapping("/counter/fast")
    public ResponseEntity<Map<String, Object>> counterFast(@RequestBody CounterCommand command) {
        String sessionId = command.getSessionId() != null ? command.getSessionId() : "default";
        command.setSessionId(sessionId);

        try {
            String traceId = kafkaService.publishFireAndForget(command);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "traceId", traceId,
                "status", "accepted"
            ));
        } catch (Exception ex) {
            log.error("Fast counter failed", ex);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", "Failed to publish"
            ));
        }
    }

    /**
     * Get service status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "service", "gateway-java",
            "status", "running",
            "pendingTransactions", kafkaService.getPendingCount(),
            "timestamp", System.currentTimeMillis()
        ));
    }
}
