package com.reactive.counter.diagnostic;

import com.reactive.diagnostic.DiagnosticCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiagnosticConfig {

    @Value("${spring.application.name:counter-application}")
    private String applicationName;

    @Value("${HOSTNAME:unknown}")
    private String hostname;

    @Value("${SERVICE_VERSION:1.0.0}")
    private String version;

    @Bean
    public DiagnosticCollector diagnosticCollector() {
        return new DiagnosticCollector(applicationName, hostname, version);
    }
}
