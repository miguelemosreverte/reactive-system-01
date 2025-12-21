package com.reactive.platform.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.platform.serialization.Result;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Kafka-backed EventStore implementation.
 *
 * Reads events from Kafka topics for replay purposes.
 */
public class KafkaEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Configuration for KafkaEventStore.
     */
    public record Config(
            String bootstrapServers,
            String topic,
            String aggregateIdField,
            String eventIdField,
            String eventTypeField,
            Duration pollTimeout,
            int maxEventsToScan
    ) {
        public Config {
            Objects.requireNonNull(topic, "topic required");
        }

        public static Config forTopic(String bootstrapServers, String topic) {
            return new Config(bootstrapServers, topic, "sessionId", "requestId", "action",
                    Duration.ofMillis(500), 100_000);
        }

        public Config withFields(String aggregateId, String eventId, String eventType) {
            return new Config(bootstrapServers, topic, aggregateId, eventId, eventType,
                    pollTimeout, maxEventsToScan);
        }
    }

    private final Config config;

    public KafkaEventStore(Config config) {
        this.config = config;
    }

    // Convenience factory
    public static KafkaEventStore create(String bootstrapServers, String topic,
                                          String aggregateIdField, String eventIdField) {
        return new KafkaEventStore(new Config(
                bootstrapServers, topic, aggregateIdField, eventIdField, "action",
                Duration.ofMillis(500), 100_000
        ));
    }

    // ========================================================================
    // Builder (kept for backwards compatibility)
    // ========================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String bootstrapServers = "localhost:9092";
        private String topic;
        private String aggregateIdField = "sessionId";
        private String eventIdField = "requestId";
        private String eventTypeField = "action";
        private Duration pollTimeout = Duration.ofMillis(500);
        private int maxEventsToScan = 100_000;

        public Builder bootstrapServers(String s) { this.bootstrapServers = s; return this; }
        public Builder topic(String t) { this.topic = t; return this; }
        public Builder aggregateIdField(String f) { this.aggregateIdField = f; return this; }
        public Builder eventIdField(String f) { this.eventIdField = f; return this; }
        public Builder eventTypeField(String f) { this.eventTypeField = f; return this; }
        public Builder pollTimeout(Duration d) { this.pollTimeout = d; return this; }
        public Builder maxEventsToScan(int m) { this.maxEventsToScan = m; return this; }

        public KafkaEventStore build() {
            return new KafkaEventStore(new Config(
                    bootstrapServers, topic, aggregateIdField, eventIdField,
                    eventTypeField, pollTimeout, maxEventsToScan
            ));
        }
    }

    // ========================================================================
    // EventStore implementation
    // ========================================================================

    @Override
    public Result<List<StoredEvent>> getEventsByAggregate(String aggregateId) {
        return scanTopic(record -> aggregateId.equals(extractAggregateId(record)));
    }

    @Override
    public Result<List<StoredEvent>> getEventsByAggregate(String aggregateId, Instant from, Instant to) {
        return scanTopic(record -> {
            if (!aggregateId.equals(extractAggregateId(record))) return false;
            long ts = record.timestamp();
            return ts >= from.toEpochMilli() && ts <= to.toEpochMilli();
        });
    }

    @Override
    public Result<Optional<StoredEvent>> getEventById(String eventId) {
        return scanTopic(record ->
            parsePayload(record.value())
                .map(p -> eventId.equals(extractField(p, config.eventIdField)))
                .getOrElse(false)
        ).map(events -> events.stream().findFirst());
    }

    @Override
    public Result<List<StoredEvent>> getEventsUpTo(String aggregateId, String upToEventId) {
        return getEventsByAggregate(aggregateId).map(events ->
            takeUntil(events, e -> upToEventId.equals(e.eventId()))
        );
    }

    @Override
    public Result<Optional<StoredEvent>> getLatestEvent(String aggregateId) {
        return getEventsByAggregate(aggregateId).map(events ->
            events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1))
        );
    }

    @Override
    public Result<Long> countEvents(String aggregateId) {
        return getEventsByAggregate(aggregateId).map(e -> (long) e.size());
    }

    @Override
    public boolean isHealthy() {
        try (var consumer = createConsumer()) {
            consumer.listTopics(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("Kafka health check failed", e);
            return false;
        }
    }

    // ========================================================================
    // Core scanning - functional approach
    // ========================================================================

    private Result<List<StoredEvent>> scanTopic(Predicate<ConsumerRecord<String, byte[]>> filter) {
        return Result.of(() -> {
            try (var consumer = createConsumer()) {
                var partitions = getPartitions(consumer);
                if (partitions.isEmpty()) {
                    log.warn("No partitions for topic: {}", config.topic);
                    return List.<StoredEvent>of();
                }

                consumer.assign(partitions);
                consumer.seekToBeginning(partitions);
                var endOffsets = consumer.endOffsets(partitions);

                var events = pollAllRecords(consumer, partitions, endOffsets)
                        .filter(filter)
                        .flatMap(r -> toStoredEvent(r).stream())
                        .sorted(Comparator.comparingLong(StoredEvent::offset))
                        .toList();

                log.debug("Found {} events for topic {}", events.size(), config.topic);
                return events;
            }
        });
    }

    private Stream<ConsumerRecord<String, byte[]>> pollAllRecords(
            KafkaConsumer<String, byte[]> consumer,
            List<TopicPartition> partitions,
            Map<TopicPartition, Long> endOffsets
    ) {
        List<ConsumerRecord<String, byte[]>> allRecords = new ArrayList<>();
        int scanned = 0;

        while (scanned < config.maxEventsToScan && !reachedEnd(consumer, partitions, endOffsets)) {
            var records = consumer.poll(config.pollTimeout);
            for (var record : records) {
                allRecords.add(record);
                scanned++;
                if (scanned >= config.maxEventsToScan) break;
            }
        }

        return allRecords.stream();
    }

    private boolean reachedEnd(KafkaConsumer<?, ?> consumer,
                               List<TopicPartition> partitions,
                               Map<TopicPartition, Long> endOffsets) {
        return partitions.stream().allMatch(tp ->
            consumer.position(tp) >= endOffsets.getOrDefault(tp, 0L)
        );
    }

    private List<TopicPartition> getPartitions(KafkaConsumer<?, ?> consumer) {
        return consumer.partitionsFor(config.topic).stream()
                .map(info -> new TopicPartition(config.topic, info.partition()))
                .toList();
    }

    // ========================================================================
    // Parsing - now returns Optional instead of null
    // ========================================================================

    private Optional<StoredEvent> toStoredEvent(ConsumerRecord<String, byte[]> record) {
        return parsePayload(record.value()).fold(
            error -> {
                log.warn("Failed to parse event at offset {}: {}", record.offset(), error.getMessage());
                return Optional.empty();
            },
            payload -> Optional.of(buildStoredEvent(record, payload))
        );
    }

    private StoredEvent buildStoredEvent(ConsumerRecord<String, byte[]> record, Map<String, Object> payload) {
        var headers = extractHeaders(record);
        var aggregateId = Optional.ofNullable(extractAggregateId(record)).orElse(record.key());
        var eventId = Optional.ofNullable(extractField(payload, config.eventIdField))
                .orElse(String.valueOf(record.offset()));
        var traceId = headers.getOrDefault("traceparent",
                headers.getOrDefault("traceId", extractField(payload, "traceId")));

        return new StoredEvent(
                aggregateId,
                eventId,
                extractField(payload, config.eventTypeField),
                payload,
                Instant.ofEpochMilli(record.timestamp()),
                record.offset(),
                traceId,
                headers
        );
    }

    private Map<String, String> extractHeaders(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header h : record.headers()) {
            headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private String extractAggregateId(ConsumerRecord<String, byte[]> record) {
        if (record.key() != null && !record.key().isEmpty()) {
            return record.key();
        }
        return parsePayload(record.value())
                .map(p -> extractField(p, config.aggregateIdField))
                .getOrElse((String) null);
    }

    private Result<Map<String, Object>> parsePayload(byte[] value) {
        return Result.of(() -> mapper.readValue(value, MAP_TYPE));
    }

    private String extractField(Map<String, Object> payload, String field) {
        if (field == null || payload == null) return null;

        Object current = payload;
        for (String part : field.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current != null ? current.toString() : null;
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    private <T> List<T> takeUntil(List<T> list, Predicate<T> stopCondition) {
        List<T> result = new ArrayList<>();
        for (T item : list) {
            result.add(item);
            if (stopCondition.test(item)) break;
        }
        return result;
    }

    private KafkaConsumer<String, byte[]> createConsumer() {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-" + UUID.randomUUID());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "replay");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
        return new KafkaConsumer<>(props);
    }
}
