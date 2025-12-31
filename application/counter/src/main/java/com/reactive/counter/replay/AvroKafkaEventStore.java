package com.reactive.counter.replay;

import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.serialization.AvroCounterEventCodec;
import com.reactive.platform.replay.EventStore;
import com.reactive.platform.replay.StoredEvent;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.Result;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Avro-aware Kafka EventStore for CounterEvent.
 *
 * Uses AvroCounterEventCodec to decode binary Avro messages from Kafka.
 */
public class AvroKafkaEventStore implements EventStore {

    private final String bootstrapServers;
    private final String topic;
    private final Codec<CounterEvent> codec;
    private final Duration pollTimeout;
    private final int maxEventsToScan;

    private AvroKafkaEventStore(String bootstrapServers, String topic) {
        this.bootstrapServers = Objects.requireNonNull(bootstrapServers);
        this.topic = Objects.requireNonNull(topic);
        this.codec = AvroCounterEventCodec.create();
        this.pollTimeout = Duration.ofMillis(500);
        this.maxEventsToScan = 100_000;
    }

    public static AvroKafkaEventStore create(java.util.function.Consumer<Builder> configure) {
        Builder builder = new Builder();
        configure.accept(builder);
        return builder.build();
    }

    public static class Builder {
        private String bootstrapServers = "localhost:9092";
        private String topic;

        public Builder bootstrapServers(String s) { this.bootstrapServers = s; return this; }
        public Builder topic(String t) { this.topic = t; return this; }

        AvroKafkaEventStore build() {
            return new AvroKafkaEventStore(bootstrapServers, topic);
        }
    }

    @Override
    public Result<List<StoredEvent>> getEventsByAggregate(String aggregateId) {
        return scanTopic(record -> {
            var event = decodeEvent(record.value());
            return event.map(e -> aggregateId.equals(e.sessionId())).getOrElse(false);
        });
    }

    @Override
    public Result<List<StoredEvent>> getEventsByAggregate(String aggregateId, Instant from, Instant to) {
        return scanTopic(record -> {
            var event = decodeEvent(record.value());
            if (event.isFailure()) return false;
            var e = event.getOrThrow();
            if (!aggregateId.equals(e.sessionId())) return false;
            long ts = record.timestamp();
            return ts >= from.toEpochMilli() && ts <= to.toEpochMilli();
        });
    }

    @Override
    public Result<Optional<StoredEvent>> getEventById(String eventId) {
        return scanTopic(record -> {
            var event = decodeEvent(record.value());
            return event.map(e -> eventId.equals(e.requestId())).getOrElse(false);
        }).map(events -> events.stream().findFirst());
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
            return false;
        }
    }

    private Result<List<StoredEvent>> scanTopic(Predicate<ConsumerRecord<String, byte[]>> filter) {
        return Result.of(() -> {
            try (var consumer = createConsumer()) {
                var partitions = getPartitions(consumer);
                if (partitions.isEmpty()) {
                    return List.<StoredEvent>of();
                }

                consumer.assign(partitions);
                consumer.seekToBeginning(partitions);
                var endOffsets = consumer.endOffsets(partitions);

                return pollAllRecords(consumer, partitions, endOffsets)
                        .filter(filter)
                        .flatMap(r -> toStoredEvent(r).stream())
                        .sorted(Comparator.comparingLong(StoredEvent::offset))
                        .toList();
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

    private Result<CounterEvent> decodeEvent(byte[] value) {
        return codec.decode(value);
    }

    private Optional<StoredEvent> toStoredEvent(ConsumerRecord<String, byte[]> record) {
        return decodeEvent(record.value()).fold(
            err -> Optional.empty(),
            event -> Optional.of(buildStoredEvent(record, event))
        );
    }

    private StoredEvent buildStoredEvent(ConsumerRecord<String, byte[]> record, CounterEvent event) {
        var headers = extractHeaders(record);
        var traceId = headers.getOrDefault("traceparent", headers.getOrDefault("traceId", ""));

        Map<String, Object> payload = Map.of(
            "requestId", event.requestId(),
            "customerId", event.customerId(),
            "eventId", event.eventId(),
            "sessionId", event.sessionId(),
            "action", event.action(),
            "value", event.value(),
            "timestamp", event.timestamp()
        );

        return new StoredEvent(
                event.sessionId(),
                event.requestId(),
                event.action(),
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-avro-" + UUID.randomUUID());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "replay-avro");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
        return new KafkaConsumer<>(props);
    }
}
