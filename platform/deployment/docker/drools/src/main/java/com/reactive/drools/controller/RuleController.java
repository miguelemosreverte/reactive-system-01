package com.reactive.drools.controller;

import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import com.reactive.drools.service.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RuleController {
    private static final Logger LOG = LoggerFactory.getLogger(RuleController.class);

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody(required = false) EvaluationRequest request) {
        if (request == null) {
            LOG.warn("Received null evaluation request");
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", "Request body is required"
            ));
        }

        try {
            EvaluationResponse response = ruleService.evaluate(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOG.error("Error evaluating rules for value {}: {}", request.getValue(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "Failed to evaluate rules: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/evaluate/{value}")
    public ResponseEntity<?> evaluateGet(@PathVariable int value) {
        try {
            EvaluationRequest request = new EvaluationRequest(value);
            EvaluationResponse response = ruleService.evaluate(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOG.error("Error evaluating rules for value {}: {}", value, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal Server Error",
                "message", "Failed to evaluate rules: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getRulesInfo() {
        return ResponseEntity.ok(Map.of(
            "service", "drools-service",
            "version", "1.0.0",
            "rules", new String[] {
                "Counter Normal (1-10) -> NORMAL",
                "Counter Warning (11-100) -> WARNING",
                "Counter Critical (>100) -> CRITICAL",
                "Counter Reset (0) -> RESET",
                "Counter Negative (<0) -> INVALID"
            }
        ));
    }
}
