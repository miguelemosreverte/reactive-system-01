package com.reactive.gateway.config;

import com.reactive.gateway.model.CounterCommand;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, CounterCommand> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Optimized for throughput with acceptable latency
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 0);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);  // 32KB batch for better batching
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);       // Wait up to 5ms to batch messages
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864);  // 64MB buffer

        // Performance tuning
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 10);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");  // Fast compression for network efficiency

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, CounterCommand> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
