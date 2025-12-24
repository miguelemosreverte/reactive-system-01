package com.reactive.gateway.config;

import com.reactive.diagnostic.DiagnosticCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for diagnostic data collection.
 *
 * Creates a DiagnosticCollector that tracks:
 * - Throughput (events/sec, batches)
 * - Latency (percentiles, breakdown)
 * - Resource saturation (JVM metrics)
 * - Error patterns
 * - Pipeline stage performance
 * - External dependency health
 */
@Configuration
public class DiagnosticConfig {

    @Value("${spring.application.name:gateway}")
    private String applicationName;

    @Value("${POD_NAME:${HOSTNAME:localhost}}")
    private String instanceId;

    @Value("${app.version:1.0.0}")
    private String version;

    @Bean
    public DiagnosticCollector diagnosticCollector() {
        DiagnosticCollector collector = new DiagnosticCollector(applicationName, instanceId, version);

        // Register pipeline stages for the gateway
        collector.registerStage("http_receive");
        collector.registerStage("validation");
        collector.registerStage("serialization");
        collector.registerStage("kafka_produce");

        // Register dependencies
        collector.registerDependency("kafka", "message_broker");

        // Configure capacity based on expected throughput
        collector.setTheoreticalMaxThroughput(5000.0);
        collector.setQueueCapacity(10000);

        return collector;
    }
}
