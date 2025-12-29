package com.reactive.gateway.config;

import com.reactive.gateway.model.CounterCommand;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka producer configuration.
 *
 * All settings are externalized to application.yml under spring.kafka.producer.*
 * This class simply wires up the Spring Boot auto-configured properties.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, CounterCommand> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties(null));
    }

    @Bean
    public KafkaTemplate<String, CounterCommand> kafkaTemplate(ProducerFactory<String, CounterCommand> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
