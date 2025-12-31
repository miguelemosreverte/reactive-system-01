package com.reactive.drools.config;

import com.reactive.platform.observe.InvestigationContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * HTTP filter that enables InvestigationContext when X-Investigation header is present.
 *
 * This completes the investigation mode propagation chain:
 * - ForensicController → publishWithInvestigation (injects x-investigation to Kafka)
 * - Flink TracingKafkaDeserializer reads x-investigation, enables InvestigationContext
 * - Flink AsyncDroolsEnricher propagates X-Investigation header to HTTP
 * - This filter reads X-Investigation, enables InvestigationContext
 * - RuleService's Log.traced("drools.evaluate") creates span when investigation active
 */
@Component
@Order(1)  // Run early in filter chain
public class InvestigationFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(InvestigationFilter.class);
    private static final String INVESTIGATION_HEADER = "X-Investigation";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String investigationHeader = httpRequest.getHeader(INVESTIGATION_HEADER);
        boolean investigationEnabled = "true".equalsIgnoreCase(investigationHeader);

        if (investigationEnabled) {
            InvestigationContext.enable();
            LOG.info("[INVESTIGATION] Investigation mode enabled for request: {} {}",
                    httpRequest.getMethod(), httpRequest.getRequestURI());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            if (investigationEnabled) {
                InvestigationContext.disable();
            }
        }
    }
}
