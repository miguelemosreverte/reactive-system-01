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

    // ========================================================================
    // Static factories (Scala-style: KafkaEventStore.forTopic(...))
    // ========================================================================

    /** Create event store with default field mappings. */
    public static KafkaEventStore forTopic(String bootstrapServers, String topic) {
        return new KafkaEventStore(bootstrapServers, topic, "sessionId", "requestId", "action");
    }

    /** Create event store with custom field mappings. */
    public static KafkaEventStore forTopic(String bootstrapServers, String topic,
                                            String aggregateIdField, String eventIdField) {
        return new KafkaEventStore(bootstrapServers, topic, aggregateIdField, eventIdField, "action");
    }

    /** @deprecated Use forTopic() instead */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    // ========================================================================
    // Internal state
    // ========================================================================

    private final String bootstrapServers;
    private final String topic;
    private final String aggregateIdField;
    private final String eventIdField;
    private final String eventTypeField;
    private final Duration pollTimeout;
    private final int maxEventsToScan;

    private KafkaEventStore(String bootstrapServers, String topic,
                            String aggregateIdField, String eventIdField, String eventTypeField) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers);
        this.topic = Objects.requireNonNull(topic);
        this.aggregateIdField = aggregateIdField;
        this.eventIdField = eventIdField;
        this.eventTypeField = eventTypeField;
        this.pollTimeout = Duration.ofMillis(500);
        this.maxEventsToScan = 100_000;
    }

    /** @deprecated Use forTopic() instead */
    @Deprecated
    public static class Builder {
        private String bootstrapServers = "localhost:9092";
        private String topic;
        private String aggregateIdField = "sessionId";
        private String eventIdField = "requestId";

        public Builder bootstrapServers(String s) { this.bootstrapServers = s; return this; }
        public Builder topic(String t) { this.topic = t; return this; }
        public Builder aggregateIdField(String f) { this.aggregateIdField = f; return this; }
        public Builder eventIdField(String f) { this.eventIdField = f; return this; }

        public KafkaEventStore build() {
            return KafkaEventStore.forTopic(bootstrapServers, topic, aggregateIdField, eventIdField);
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
                .map(p -> eventId.equals(extractField(p, eventIdField)))
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
                    log.warn("No partitions for topic: {}", topic);
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

                log.debug("Found {} events for topic {}", events.size(), topic);
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

        while (scanned < maxEventsToScan && !reachedEnd(consumer, partitions, endOffsets)) {
            var records = consumer.poll(pollTimeout);
            for (var record : records) {
                allRecords.add(record);
                scanned++;
                if (scanned >= maxEventsToScan) break;
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
        return consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
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
        var eventId = Optional.ofNullable(extractField(payload, eventIdField))
                .orElse(String.valueOf(record.offset()));
        var traceId = headers.getOrDefault("traceparent",
                headers.getOrDefault("traceId", extractField(payload, "traceId")));

        return new StoredEvent(
                aggregateId,
                eventId,
                extractField(payload, eventTypeField),
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
                .map(p -> extractField(p, aggregateIdField))
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
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
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
