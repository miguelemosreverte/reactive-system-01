package com.reactive.drools.service;

import com.reactive.drools.model.Counter;
import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import static com.reactive.platform.tracing.Tracing.span;

/**
 * Drools rule evaluation service.
 *
 * Receives requests from Flink with business IDs:
 * - requestId: Correlation ID for request tracking
 * - customerId: Customer/tenant ID for multi-tenancy
 * - eventId: Unique event ID
 *
 * Optimized for throughput using StatelessKieSession (no session state overhead).
 * Note: OpenTelemetry trace propagation is handled automatically via HTTP headers.
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);
    private static final String SERVICE_NAME = "drools-service";
    private final StatelessKieSession statelessSession;

    public RuleService(KieContainer kieContainer) {
        // Use stateless session for better throughput - no session state overhead
        this.statelessSession = kieContainer.newStatelessKieSession();
    }

    public EvaluationResponse evaluate(EvaluationRequest request) {
        return span(SERVICE_NAME, "drools.evaluate", s -> {
            Counter counter = new Counter(request.getValue());

            // Add span attributes
            s.setAttribute("drools.input_value", request.getValue());

            // Set business IDs as span attributes for Jaeger visibility
            if (request.getRequestId() != null) {
                s.setAttribute("requestId", request.getRequestId());
                MDC.put("requestId", request.getRequestId());
            }
            if (request.getCustomerId() != null) {
                s.setAttribute("customerId", request.getCustomerId());
                MDC.put("customerId", request.getCustomerId());
            }
            if (request.getEventId() != null) {
                s.setAttribute("eventId", request.getEventId());
            }
            if (request.getSessionId() != null) {
                s.setAttribute("session.id", request.getSessionId());
            }

            try {
                // Execute rules using stateless session (thread-safe, no dispose needed)
                statelessSession.execute(counter);
                s.setAttribute("drools.rules_fired", 1);
                log.info("Drools evaluated: inputValue={}, alert={}, requestId={}, customerId={}",
                        request.getValue(), counter.getAlert(),
                        request.getRequestId(), request.getCustomerId());

                EvaluationResponse response = new EvaluationResponse();
                response.setValue(counter.getValue());
                response.setAlert(counter.getAlert() != null ? counter.getAlert() : "NONE");
                response.setMessage(generateMessage(counter));

                // Include the OpenTelemetry trace ID in response for observability
                String traceId = s.getSpanContext().getTraceId();
                if (traceId != null && !traceId.equals("00000000000000000000000000000000")) {
                    response.setTraceId(traceId);
                }

                // Add result attributes
                s.setAttribute("drools.result_value", response.getValue());
                s.setAttribute("drools.alert_level", response.getAlert());

                return response;
            } finally {
                MDC.remove("requestId");
                MDC.remove("customerId");
            }
        });
    }

    private String generateMessage(Counter counter) {
        String alert = counter.getAlert();
        if (alert == null) {
            return "No rules matched";
        }

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
