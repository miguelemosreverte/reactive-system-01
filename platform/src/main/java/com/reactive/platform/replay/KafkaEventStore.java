package com.reactive.platform.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.platform.serialization.Result;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
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
import java.util.stream.Collectors;

/**
 * Kafka-backed EventStore implementation.
 *
 * Reads events from Kafka topics for replay purposes.
 * Uses a dedicated consumer group to avoid interfering with production consumers.
 *
 * Note: This implementation scans the topic to find events by aggregate ID.
 * For high-volume topics, consider adding a secondary index (e.g., in Redis or a DB)
 * or using Kafka Streams with state stores for efficient lookups.
 */
public class KafkaEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String bootstrapServers;
    private final String topic;
    private final String aggregateIdField;
    private final String eventIdField;
    private final String eventTypeField;
    private final Duration pollTimeout;
    private final int maxEventsToScan;

    private KafkaEventStore(Builder builder) {
        this.bootstrapServers = builder.bootstrapServers;
        this.topic = builder.topic;
        this.aggregateIdField = builder.aggregateIdField;
        this.eventIdField = builder.eventIdField;
        this.eventTypeField = builder.eventTypeField;
        this.pollTimeout = builder.pollTimeout;
        this.maxEventsToScan = builder.maxEventsToScan;
    }

    @Override
    public Result<List<StoredEvent>> getEventsByAggregate(String aggregateId) {
        return scanTopic(record -> {
            String recordAggregateId = extractAggregateId(record);
            return aggregateId.equals(recordAggregateId);
        });
    }

    @Override
    public Result<List<StoredEvent>> getEventsByAggregate(String aggregateId, Instant from, Instant to) {
        return scanTopic(record -> {
            String recordAggregateId = extractAggregateId(record);
            if (!aggregateId.equals(recordAggregateId)) {
                return false;
            }
            long timestamp = record.timestamp();
            return timestamp >= from.toEpochMilli() && timestamp <= to.toEpochMilli();
        });
    }

    @Override
    public Result<Optional<StoredEvent>> getEventById(String eventId) {
        return scanTopic(record -> {
            try {
                Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);
                String recordEventId = extractField(payload, eventIdField);
                return eventId.equals(recordEventId);
            } catch (Exception e) {
                return false;
            }
        }).map(events -> events.isEmpty() ? Optional.empty() : Optional.of(events.get(0)));
    }

    @Override
    public Result<List<StoredEvent>> getEventsUpTo(String aggregateId, String upToEventId) {
        return getEventsByAggregate(aggregateId).map(events -> {
            List<StoredEvent> result = new ArrayList<>();
            for (StoredEvent event : events) {
                result.add(event);
                if (upToEventId.equals(event.eventId())) {
                    break;
                }
            }
            return result;
        });
    }

    @Override
    public Result<Optional<StoredEvent>> getLatestEvent(String aggregateId) {
        return getEventsByAggregate(aggregateId).map(events ->
                events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1)));
    }

    @Override
    public Result<Long> countEvents(String aggregateId) {
        return getEventsByAggregate(aggregateId).map(events -> (long) events.size());
    }

    @Override
    public boolean isHealthy() {
        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            consumer.listTopics(Duration.ofSeconds(5));
            return true;
        } catch (Exception e) {
            log.warn("Kafka health check failed", e);
            return false;
        }
    }

    // ========================================================================
    // Internal methods
    // ========================================================================

    private Result<List<StoredEvent>> scanTopic(java.util.function.Predicate<ConsumerRecord<String, byte[]>> filter) {
        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            // Get all partitions and assign from beginning
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .collect(Collectors.toList());

            if (partitions.isEmpty()) {
                log.warn("No partitions found for topic: {}", topic);
                return Result.success(Collections.emptyList());
            }

            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            // Get end offsets to know when to stop
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            List<StoredEvent> events = new ArrayList<>();
            Map<TopicPartition, Long> currentOffsets = new HashMap<>();

            // Initialize current offsets
            for (TopicPartition tp : partitions) {
                currentOffsets.put(tp, consumer.position(tp));
            }

            int totalScanned = 0;
            boolean done = false;

            while (!done && totalScanned < maxEventsToScan) {
                ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);

                if (records.isEmpty()) {
                    // Check if we've reached the end of all partitions
                    done = true;
                    for (TopicPartition tp : partitions) {
                        long current = consumer.position(tp);
                        long end = endOffsets.getOrDefault(tp, 0L);
                        if (current < end) {
                            done = false;
                            break;
                        }
                    }
                    continue;
                }

                for (ConsumerRecord<String, byte[]> record : records) {
                    totalScanned++;

                    if (filter.test(record)) {
                        StoredEvent event = toStoredEvent(record);
                        if (event != null) {
                            events.add(event);
                        }
                    }

                    // Update current offset
                    TopicPartition tp = new TopicPartition(record.topic(), record.partition());
                    currentOffsets.put(tp, record.offset() + 1);
                }

                // Check if we've processed all messages
                done = true;
                for (TopicPartition tp : partitions) {
                    long current = currentOffsets.getOrDefault(tp, 0L);
                    long end = endOffsets.getOrDefault(tp, 0L);
                    if (current < end) {
                        done = false;
                        break;
                    }
                }
            }

            log.debug("Scanned {} events, found {} matching for topic {}", totalScanned, events.size(), topic);

            // Sort by offset to ensure order
            events.sort(Comparator.comparingLong(StoredEvent::offset));

            return Result.success(events);

        } catch (Exception e) {
            log.error("Failed to scan topic {}", topic, e);
            return Result.failure(e);
        }
    }

    private StoredEvent toStoredEvent(ConsumerRecord<String, byte[]> record) {
        try {
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);

            String aggregateId = extractAggregateId(record);
            String eventId = extractField(payload, eventIdField);
            String eventType = extractField(payload, eventTypeField);

            // Extract headers
            Map<String, String> headers = new HashMap<>();
            for (Header header : record.headers()) {
                headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }

            // Extract trace ID from headers or payload
            String traceId = headers.getOrDefault("traceparent",
                    headers.getOrDefault("traceId",
                            extractField(payload, "traceId")));

            return new StoredEvent(
                    aggregateId != null ? aggregateId : record.key(),
                    eventId != null ? eventId : String.valueOf(record.offset()),
                    eventType,
                    payload,
                    Instant.ofEpochMilli(record.timestamp()),
                    record.offset(),
                    traceId,
                    headers
            );

        } catch (Exception e) {
            log.warn("Failed to parse event at offset {}: {}", record.offset(), e.getMessage());
            return null;
        }
    }

    private String extractAggregateId(ConsumerRecord<String, byte[]> record) {
        // First try message key (common pattern)
        if (record.key() != null && !record.key().isEmpty()) {
            return record.key();
        }

        // Then try payload field
        try {
            Map<String, Object> payload = objectMapper.readValue(record.value(), MAP_TYPE);
            return extractField(payload, aggregateIdField);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractField(Map<String, Object> payload, String field) {
        if (field == null || payload == null) return null;

        // Support nested fields like "session.id"
        String[] parts = field.split("\\.");
        Object current = payload;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }

        return current != null ? current.toString() : null;
    }

    private KafkaConsumer<String, byte[]> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "event-store-replay-" + UUID.randomUUID());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "event-store-replay");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);

        return new KafkaConsumer<>(props);
    }

    // ========================================================================
    // Builder
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

        public Builder bootstrapServers(String servers) {
            this.bootstrapServers = servers;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder aggregateIdField(String field) {
            this.aggregateIdField = field;
            return this;
        }

        public Builder eventIdField(String field) {
            this.eventIdField = field;
            return this;
        }

        public Builder eventTypeField(String field) {
            this.eventTypeField = field;
            return this;
        }

        public Builder pollTimeout(Duration timeout) {
            this.pollTimeout = timeout;
            return this;
        }

        public Builder maxEventsToScan(int max) {
            this.maxEventsToScan = max;
            return this;
        }

        public KafkaEventStore build() {
            Objects.requireNonNull(topic, "topic is required");
            return new KafkaEventStore(this);
        }
    }
}
