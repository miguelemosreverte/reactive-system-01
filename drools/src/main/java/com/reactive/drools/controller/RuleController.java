package com.reactive.drools.controller;

import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import com.reactive.drools.service.RuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationResponse> evaluate(@RequestBody EvaluationRequest request) {
        EvaluationResponse response = ruleService.evaluate(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/evaluate/{value}")
    public ResponseEntity<EvaluationResponse> evaluateGet(@PathVariable int value) {
        EvaluationRequest request = new EvaluationRequest(value);
        EvaluationResponse response = ruleService.evaluate(request);
        return ResponseEntity.ok(response);
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
