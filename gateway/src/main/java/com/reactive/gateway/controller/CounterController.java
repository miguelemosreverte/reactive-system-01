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
     * Counter endpoint with customer context.
     * POST /api/customers/{customerId}/counter
     */
    @PostMapping("/customers/{customerId}/counter")
    public Mono<ResponseEntity<Map<String, Object>>> counterWithCustomer(
            @PathVariable String customerId,
            @RequestBody CounterCommand command) {

        command.setCustomerId(customerId);
        return processCounter(command);
    }

    /**
     * Counter endpoint without customer (backwards compatible).
     * POST /api/counter
     */
    @PostMapping("/counter")
    public Mono<ResponseEntity<Map<String, Object>>> counter(@RequestBody CounterCommand command) {
        return processCounter(command);
    }

    private Mono<ResponseEntity<Map<String, Object>>> processCounter(CounterCommand command) {
        String sessionId = command.getSessionId() != null ? command.getSessionId() : "default";
        command.setSessionId(sessionId);

        CompletableFuture<CounterResult> future = kafkaService.publishAndWait(command, DEFAULT_TIMEOUT_MS);

        return Mono.fromFuture(future)
            .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                "success", true,
                "requestId", result.getRequestId(),
                "customerId", result.getCustomerId() != null ? result.getCustomerId() : "",
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
     * Fast counter endpoint with customer context - fire-and-forget.
     * POST /api/customers/{customerId}/counter/fast
     */
    @PostMapping("/customers/{customerId}/counter/fast")
    public ResponseEntity<Map<String, Object>> counterFastWithCustomer(
            @PathVariable String customerId,
            @RequestBody CounterCommand command) {

        command.setCustomerId(customerId);
        return processCounterFast(command);
    }

    /**
     * Fast counter endpoint - fire-and-forget (backwards compatible).
     * POST /api/counter/fast
     */
    @PostMapping("/counter/fast")
    public ResponseEntity<Map<String, Object>> counterFast(@RequestBody CounterCommand command) {
        return processCounterFast(command);
    }

    private ResponseEntity<Map<String, Object>> processCounterFast(CounterCommand command) {
        String sessionId = command.getSessionId() != null ? command.getSessionId() : "default";
        command.setSessionId(sessionId);

        try {
            var result = kafkaService.publishFireAndForget(command);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "requestId", result.requestId(),
                "customerId", command.getCustomerId() != null ? command.getCustomerId() : "",
                "eventId", result.eventId(),
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
