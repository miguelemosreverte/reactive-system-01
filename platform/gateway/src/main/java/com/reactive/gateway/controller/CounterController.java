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

import static com.reactive.platform.Opt.or;
import com.reactive.platform.observe.Log;

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
        command.setSessionId(or(command.getSessionId(), "default"));

        CompletableFuture<CounterResult> future = kafkaService.publishAndWait(command, DEFAULT_TIMEOUT_MS);

        return Mono.fromFuture(future)
            .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                "success", true,
                "requestId", result.getRequestId(),
                "customerId", or(result.getCustomerId(), ""),
                "eventId", result.getEventId(),
                "traceId", or(Log.traceId(), ""),
                "result", result
            )))
            .onErrorResume(ex -> {
                log.error("Counter operation failed", ex);
                return Mono.just(ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", or(ex.getMessage(), ex.getClass().getSimpleName())
                )));
            });
    }

    /**
     * Fast counter endpoint with customer context - fire-and-forget.
     * POST /api/customers/{customerId}/counter/fast
     *
     * Headers:
     * - X-Debug: true - Enable full tracing for this request
     */
    @PostMapping("/customers/{customerId}/counter/fast")
    public ResponseEntity<Map<String, Object>> counterFastWithCustomer(
            @PathVariable String customerId,
            @RequestBody CounterCommand command,
            @RequestHeader(value = "X-Debug", defaultValue = "false") boolean debugMode) {

        command.setCustomerId(customerId);
        return processCounterFast(command, debugMode);
    }

    /**
     * Fast counter endpoint - fire-and-forget (backwards compatible).
     * POST /api/counter/fast
     *
     * Headers:
     * - X-Debug: true - Enable full tracing for this request
     */
    @PostMapping("/counter/fast")
    public ResponseEntity<Map<String, Object>> counterFast(
            @RequestBody CounterCommand command,
            @RequestHeader(value = "X-Debug", defaultValue = "false") boolean debugMode) {
        return processCounterFast(command, debugMode);
    }

    private ResponseEntity<Map<String, Object>> processCounterFast(CounterCommand command, boolean debugMode) {
        command.setSessionId(or(command.getSessionId(), "default"));

        // Enable investigation mode for X-Debug requests - forces full tracing
        if (debugMode) {
            Log.enableInvestigation();
        }

        try {
            var result = kafkaService.publishFireAndForget(command);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "requestId", result.requestId(),
                "customerId", or(command.getCustomerId(), ""),
                "eventId", result.eventId(),
                "status", "accepted",
                "traceId", or(Log.traceId(), ""),
                "debug", debugMode
            ));
        } catch (Exception ex) {
            log.error("Fast counter failed", ex);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", "Failed to publish"
            ));
        } finally {
            // Always clean up investigation mode
            if (debugMode) {
                Log.disableInvestigation();
            }
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
