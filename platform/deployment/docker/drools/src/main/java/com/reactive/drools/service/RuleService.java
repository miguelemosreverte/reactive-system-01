package com.reactive.drools.service;

import com.reactive.drools.model.Counter;
import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import io.opentelemetry.api.trace.Span;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drools rule evaluation service.
 *
 * Clean service - business logic only.
 * Tracing is handled automatically by OTel agent (HTTP instrumentation).
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);
    private final StatelessKieSession statelessSession;

    public RuleService(KieContainer kieContainer) {
        this.statelessSession = kieContainer.newStatelessKieSession();
    }

    public EvaluationResponse evaluate(EvaluationRequest request) {
        // Add business context to current span (created by OTel HTTP instrumentation)
        addSpanAttributes(request);

        // Execute rules
        Counter counter = new Counter(request.getValue());
        statelessSession.execute(counter);

        log.info("Evaluated: value={}, alert={}", request.getValue(), counter.getAlert());

        // Build response
        EvaluationResponse response = new EvaluationResponse();
        response.setValue(counter.getValue());
        response.setAlert(counter.getAlert() != null ? counter.getAlert() : "NONE");
        response.setMessage(alertMessage(counter.getAlert()));
        response.setTraceId(traceId());

        // Add result to span
        Span.current().setAttribute("drools.alert", response.getAlert());

        return response;
    }

    private void addSpanAttributes(EvaluationRequest request) {
        Span span = Span.current();
        span.setAttribute("drools.input_value", request.getValue());
        if (request.getRequestId() != null) span.setAttribute("requestId", request.getRequestId());
        if (request.getCustomerId() != null) span.setAttribute("customerId", request.getCustomerId());
        if (request.getEventId() != null) span.setAttribute("eventId", request.getEventId());
        if (request.getSessionId() != null) span.setAttribute("session.id", request.getSessionId());
    }

    private String traceId() {
        String id = Span.current().getSpanContext().getTraceId();
        return "00000000000000000000000000000000".equals(id) ? null : id;
    }

    private String alertMessage(String alert) {
        if (alert == null) return "No rules matched";
        return switch (alert) {
            case "NORMAL" -> "Counter is within normal range";
            case "WARNING" -> "Counter value is elevated";
            case "CRITICAL" -> "Counter value is critically high!";
            case "RESET" -> "Counter has been reset";
            case "INVALID" -> "Counter value is invalid (negative)";
            default -> "Unknown alert level";
        };
    }
}
