package com.reactive.drools.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${service.version:1.0.0}")
    private String serviceVersion;

    @Value("${build.time:unknown}")
    private String buildTime;

    @Value("${otel.enabled:true}")
    private String otelEnabled;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "drools");
        response.put("version", serviceVersion);
        response.put("buildTime", buildTime);
        response.put("timestamp", System.currentTimeMillis());

        Map<String, Object> observability = new HashMap<>();
        observability.put("otelEnabled", !"false".equals(otelEnabled));
        response.put("observability", observability);

        return ResponseEntity.ok(response);
    }
}
