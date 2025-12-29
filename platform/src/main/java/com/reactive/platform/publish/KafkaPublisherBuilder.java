package com.reactive.platform.publish;

import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.serialization.Codec;

import java.util.function.Function;

/**
 * Builder for Kafka-backed publishers.
 *
 * This is the only class that imports Kafka types.
 * Application code uses only the Publisher interface.
 *
 * @param <A> The message type
 */
public class KafkaPublisherBuilder<A> {

    private String bootstrapServers;
    private String topic;
    private Codec<A> codec;
    private Function<A, String> keyExtractor = Object::toString;
    private String clientId = "platform-publisher";
    private int maxInFlightRequests = 5;
    private String acks = "1";
    private int lingerMs = 0;
    private int batchSize = 16384;
    private String compression = "none";
    private long bufferMemory = 16777216;

    public KafkaPublisherBuilder<A> bootstrapServers(String servers) {
        this.bootstrapServers = servers;
        return this;
    }

    public KafkaPublisherBuilder<A> topic(String topic) {
        this.topic = topic;
        return this;
    }

    public KafkaPublisherBuilder<A> codec(Codec<A> codec) {
        this.codec = codec;
        return this;
    }

    public KafkaPublisherBuilder<A> keyExtractor(Function<A, String> extractor) {
        this.keyExtractor = extractor;
        return this;
    }

    public KafkaPublisherBuilder<A> clientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    public KafkaPublisherBuilder<A> maxInFlightRequests(int max) {
        this.maxInFlightRequests = max;
        return this;
    }

    public KafkaPublisherBuilder<A> acks(String acks) {
        this.acks = acks;
        return this;
    }

    public KafkaPublisherBuilder<A> lingerMs(int ms) {
        this.lingerMs = ms;
        return this;
    }

    public KafkaPublisherBuilder<A> batchSize(int size) {
        this.batchSize = size;
        return this;
    }

    public KafkaPublisherBuilder<A> compression(String type) {
        this.compression = type;
        return this;
    }

    public KafkaPublisherBuilder<A> bufferMemory(long bytes) {
        this.bufferMemory = bytes;
        return this;
    }

    public KafkaPublisherBuilder<A> fireAndForget() {
        this.acks = "0";
        this.maxInFlightRequests = 100;
        this.lingerMs = 0;
        this.batchSize = 65536;
        this.compression = "none";
        return this;
    }

    /**
     * Build the publisher.
     * Returns Publisher interface - Kafka types not exposed.
     */
    public Publisher<A> build() {
        // Use KafkaPublisher's builder internally
        KafkaPublisher<A> kafkaPublisher = KafkaPublisher.<A>builder()
            .bootstrapServers(bootstrapServers)
            .topic(topic)
            .codec(codec)
            .keyExtractor(keyExtractor)
            .clientId(clientId)
            .maxInFlightRequests(maxInFlightRequests)
            .acks(acks)
            .lingerMs(lingerMs)
            .batchSize(batchSize)
            .compression(compression)
            .bufferMemory(bufferMemory)
            .build();

        // Wrap in adapter that implements Publisher interface
        return new KafkaPublisherAdapter<>(kafkaPublisher);
    }
}
