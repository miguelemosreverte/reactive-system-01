package com.reactive.drools.service;

import com.reactive.drools.model.Counter;
import com.reactive.drools.model.EvaluationRequest;
import com.reactive.drools.model.EvaluationResponse;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

@Service
public class RuleService {

    private final KieContainer kieContainer;

    public RuleService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public EvaluationResponse evaluate(EvaluationRequest request) {
        Counter counter = new Counter(request.getValue());

        KieSession kieSession = kieContainer.newKieSession();
        try {
            kieSession.insert(counter);
            kieSession.fireAllRules();
        } finally {
            kieSession.dispose();
        }

        EvaluationResponse response = new EvaluationResponse();
        response.setValue(counter.getValue());
        response.setAlert(counter.getAlert() != null ? counter.getAlert() : "NONE");
        response.setMessage(generateMessage(counter));

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
