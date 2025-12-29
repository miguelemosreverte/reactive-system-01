package com.reactive.platform.kafka.benchmark;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Factory for creating Kafka producers with consistent configuration.
 *
 * Consolidates 37+ duplicate producer setup methods across benchmark files.
 * See tasks/benchmark-refactoring-tasks.md for the full refactoring plan.
 */
public final class ProducerFactory {

    private ProducerFactory() {} // Utility class

    /**
     * Base producer properties with minimal configuration.
     * Use this as a starting point and customize as needed.
     */
    public static Properties baseProps(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return props;
    }

    /**
     * Properties optimized for high throughput (bulk operations).
     * All values loaded from HOCON configuration.
     */
    public static Properties highThroughputProps(String bootstrap) {
        Properties props = baseProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, BenchmarkConstants.LINGER_MS_HIGH_THROUGHPUT);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, BenchmarkConstants.BATCH_SIZE_MAX);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, BenchmarkConstants.COMPRESSION_TYPE);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, BenchmarkConstants.BUFFER_MEMORY_LARGE);
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, BenchmarkConstants.MAX_REQUEST_SIZE);
        return props;
    }

    /**
     * Properties optimized for low latency (per-message operations).
     * All values loaded from HOCON configuration.
     */
    public static Properties lowLatencyProps(String bootstrap) {
        Properties props = baseProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, BenchmarkConstants.LINGER_MS_LOW_LATENCY);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, BenchmarkConstants.BATCH_SIZE_SMALL);
        return props;
    }

    /**
     * Properties for fire-and-forget (acks=0) - maximum speed, may lose messages.
     * All values loaded from HOCON configuration.
     */
    public static Properties fireAndForgetProps(String bootstrap) {
        Properties props = baseProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        props.put(ProducerConfig.LINGER_MS_CONFIG, BenchmarkConstants.LINGER_MS_FIRE_AND_FORGET);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, BenchmarkConstants.BATCH_SIZE_STANDARD);
        return props;
    }

    /**
     * Properties with custom acks level.
     * @param acks "0" (fire-and-forget), "1" (leader), or "all" (full durability)
     */
    public static Properties withAcks(String bootstrap, String acks) {
        Properties props = baseProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        return props;
    }

    /**
     * Create a producer with base configuration.
     */
    public static KafkaProducer<String, byte[]> create(String bootstrap) {
        return new KafkaProducer<>(baseProps(bootstrap));
    }

    /**
     * Create a producer with custom properties.
     */
    public static KafkaProducer<String, byte[]> create(Properties props) {
        return new KafkaProducer<>(props);
    }

    /**
     * Create a high-throughput producer.
     */
    public static KafkaProducer<String, byte[]> createHighThroughput(String bootstrap) {
        return new KafkaProducer<>(highThroughputProps(bootstrap));
    }

    /**
     * Create a low-latency producer.
     */
    public static KafkaProducer<String, byte[]> createLowLatency(String bootstrap) {
        return new KafkaProducer<>(lowLatencyProps(bootstrap));
    }
}
