package com.reactive.platform.kafka.benchmark;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka verification utilities for benchmarks.
 *
 * Consolidates duplicate verification logic (verifyLastMessage, verifyLastBatch).
 * See tasks/benchmark-refactoring-tasks.md for the full refactoring plan.
 */
public final class KafkaVerifier {

    private KafkaVerifier() {} // Utility class

    /**
     * Verify the last record in a topic by extracting its sequence number.
     * Works for both single messages and batches (first 8 bytes = sequence).
     *
     * @param bootstrap Kafka bootstrap servers
     * @param topic Topic to verify
     * @return The sequence number from the last record, or -1 on failure
     */
    public static long verifyLastSequence(String bootstrap, String topic) {
        Properties props = consumerProps(bootstrap);

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return -1;
            }

            List<TopicPartition> partitions = partitionInfos.stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            // Find partition with highest offset
            TopicPartition lastPartition = null;
            long maxOffset = 0;
            for (var entry : endOffsets.entrySet()) {
                if (entry.getValue() > maxOffset) {
                    maxOffset = entry.getValue();
                    lastPartition = entry.getKey();
                }
            }

            if (lastPartition == null || maxOffset == 0) {
                return -1;
            }

            // Seek to last record
            consumer.seek(lastPartition, maxOffset - 1);
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(5));

            for (ConsumerRecord<String, byte[]> record : records) {
                byte[] value = record.value();
                if (value != null && value.length >= 8) {
                    return ByteBuffer.wrap(value).getLong();
                }
            }
            return -1;
        } catch (Exception e) {
            System.err.println("Failed to verify last sequence: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Get the total record count across all partitions of a topic.
     */
    public static long getRecordCount(String bootstrap, String topic) {
        Properties props = consumerProps(bootstrap);

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return 0;
            }

            List<TopicPartition> partitions = partitionInfos.stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            return endOffsets.values().stream().mapToLong(Long::longValue).sum();
        } catch (Exception e) {
            System.err.println("Failed to get record count: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Verify a topic contains at least the expected number of records.
     */
    public static BenchmarkResult.VerificationResult verify(String bootstrap, String topic, long expectedCount) {
        long actualCount = getRecordCount(bootstrap, topic);
        if (actualCount < 0) {
            return BenchmarkResult.VerificationResult.failure(expectedCount, 0, "Failed to get record count");
        }
        if (actualCount >= expectedCount) {
            return BenchmarkResult.VerificationResult.success(actualCount);
        }
        return BenchmarkResult.VerificationResult.failure(expectedCount, actualCount,
            String.format("Expected %d records, found %d (%.1f%%)",
                expectedCount, actualCount, actualCount * 100.0 / expectedCount));
    }

    private static Properties consumerProps(String bootstrap) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verifier-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }
}
