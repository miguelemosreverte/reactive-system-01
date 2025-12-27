package com.reactive.counter.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health endpoint at root level for benchmark compatibility.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "counter-application",
            "timestamp", System.currentTimeMillis()
        ));
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> apiHealth() {
        return health();
    }
}
