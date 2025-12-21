package com.reactive.drools.service;

import com.reactive.drools.model.Counter;
import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Drools rule evaluation service.
 *
 * Receives requests from Flink with business IDs:
 * - requestId: Correlation ID for request tracking
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 *
 * Note: OpenTelemetry trace propagation is handled automatically via HTTP headers.
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);
    private final KieContainer kieContainer;

    public RuleService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    @WithSpan("drools.evaluate")
    public EvaluationResponse evaluate(@SpanAttribute("counter.input_value") EvaluationRequest request) {
        Counter counter = new Counter(request.getValue());

        // Add custom span attributes with business IDs
        Span currentSpan = Span.current();
        currentSpan.setAttribute("drools.input_value", request.getValue());

        // Set business IDs as span attributes for Jaeger visibility
        if (request.getRequestId() != null) {
            currentSpan.setAttribute("requestId", request.getRequestId());
            MDC.put("requestId", request.getRequestId());
        }
        if (request.getCustomerId() != null) {
            currentSpan.setAttribute("customerId", request.getCustomerId());
            MDC.put("customerId", request.getCustomerId());
        }
        if (request.getEventId() != null) {
            currentSpan.setAttribute("eventId", request.getEventId());
        }
        if (request.getSessionId() != null) {
            currentSpan.setAttribute("session.id", request.getSessionId());
        }

        KieSession kieSession = kieContainer.newKieSession();
        try {
            kieSession.insert(counter);
            int rulesFired = kieSession.fireAllRules();
            currentSpan.setAttribute("drools.rules_fired", rulesFired);
            log.info("Drools evaluated: inputValue={}, rulesFired={}, alert={}, requestId={}, customerId={}",
                    request.getValue(), rulesFired, counter.getAlert(),
                    request.getRequestId(), request.getCustomerId());
        } finally {
            kieSession.dispose();
        }

        EvaluationResponse response = new EvaluationResponse();
        response.setValue(counter.getValue());
        response.setAlert(counter.getAlert() != null ? counter.getAlert() : "NONE");
        response.setMessage(generateMessage(counter));

        // Add result attributes
        currentSpan.setAttribute("drools.result_value", response.getValue());
        currentSpan.setAttribute("drools.alert_level", response.getAlert());

        // Cleanup MDC
        MDC.remove("requestId");
        MDC.remove("customerId");

        return response;
    }

    private String generateMessage(Counter counter) {
        String alert = counter.getAlert();
        if (alert == null) {
            return "No rules matched";
        }

        switch (alert) {
            case "NORMAL":
                return "Counter is within normal range";
            case "WARNING":
                return "Counter value is elevated";
            case "CRITICAL":
                return "Counter value is critically high!";
            case "RESET":
                return "Counter has been reset";
            case "INVALID":
                return "Counter value is invalid (negative)";
            default:
                return "Unknown alert level";
        }
    }
}
