package com.reactive.drools.service;

import com.reactive.drools.model.Counter;
import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import com.reactive.platform.observe.Log;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.StatelessKieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static com.reactive.platform.Opt.or;

/**
 * Drools rule evaluation service.
 *
 * Uses platform Log API for observability - no third-party OTel types leak into this service.
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);
    private final StatelessKieSession statelessSession;

    public RuleService(KieContainer kieContainer) {
        this.statelessSession = kieContainer.newStatelessKieSession();
    }

    public EvaluationResponse evaluate(EvaluationRequest request) {
        addSpanAttributes(request);

        Counter counter = new Counter(request.getValue());
        statelessSession.execute(counter);

        String alert = or(counter.getAlert(), "NONE");
        log.debug("Evaluated: value={}, alert={}", request.getValue(), alert);

        EvaluationResponse response = new EvaluationResponse();
        response.setValue(counter.getValue());
        response.setAlert(alert);
        response.setMessage(alertMessage(alert));
        response.setTraceId(Log.traceId());

        Log.attr("drools.alert", alert);

        return response;
    }

    private void addSpanAttributes(EvaluationRequest request) {
        Log.attr("drools.input_value", request.getValue());
        Log.attr("requestId", or(request.getRequestId(), ""));
        Log.attr("customerId", or(request.getCustomerId(), ""));
        Log.attr("eventId", or(request.getEventId(), ""));
        Log.attr("session.id", or(request.getSessionId(), ""));
    }

    private String alertMessage(String alert) {
        return switch (alert) {
            case "NORMAL" -> "Counter is within normal range";
            case "WARNING" -> "Counter value is elevated";
            case "CRITICAL" -> "Counter value is critically high!";
            case "RESET" -> "Counter has been reset";
            case "INVALID" -> "Counter value is invalid (negative)";
            case "NONE" -> "No rules matched";
            default -> "Unknown alert level";
        };
    }
}
