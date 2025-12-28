package com.reactive.platform.kafka.benchmark;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.serialization.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Kafka Baseline Benchmark - Establishes theoretical throughput limits.
 *
 * Modes:
 *   NAIVE     - 1 Kafka send() per message (worst case producer)
 *   BULK      - 1000 messages per send (standard batching)
 *   MEGA      - 10000 messages per send (extreme batching, high latency OK)
 *   CONSUMER  - Read throughput
 *   ALL       - Run all modes and generate comparison report
 *
 * Acks options (via environment or 5th arg):
 *   0   - Fire-and-forget (no acknowledgement, fastest, may lose data)
 *   1   - Leader acknowledgement (production-safe for most cases)
 *   all - All replicas (guaranteed durability, slowest)
 *
 * The MEGA mode tests what's possible when we can accept HTTP-timeout-level
 * latency (e.g., 30 seconds). This establishes the absolute ceiling.
 *
 * Usage:
 *   java KafkaBaselineBenchmark <mode> <durationSec> <kafkaBootstrap> <reportsDir> [acks]
 */
public class KafkaBaselineBenchmark {

    private static final int MESSAGE_SIZE = 64;  // bytes per message
    private static final byte[] TEST_MESSAGE = new byte[MESSAGE_SIZE];

    // Configurable acks level: "0", "1", or "all"
    private static String acksConfig = "1";  // Default to leader ack (production-safe)

    static {
        Arrays.fill(TEST_MESSAGE, (byte) 'X');
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toUpperCase() : "ALL";
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        String bootstrap = args.length > 2 ? args[2] : "kafka:29092";
        String reportsDir = args.length > 3 ? args[3] : "reports/kafka-baseline";
        acksConfig = args.length > 4 ? args[4] : System.getenv().getOrDefault("KAFKA_ACKS", "1");

        String acksDisplay = switch (acksConfig) {
            case "0" -> "0 (fire-and-forget, NO durability)";
            case "1" -> "1 (leader ack, production-safe)";
            case "all" -> "all (all replicas, guaranteed durability)";
            default -> acksConfig;
        };

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    KAFKA BASELINE BENCHMARK                                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Mode:      %s%n", mode);
        System.out.printf("  Duration:  %d seconds per test%n", durationSec);
        System.out.printf("  Kafka:     %s%n", bootstrap);
        System.out.printf("  Acks:      %s%n", acksDisplay);
        System.out.printf("  Reports:   %s%n", reportsDir);
        System.out.println();

        List<Result> results = new ArrayList<>();

        switch (mode) {
            case "NAIVE" -> results.add(benchmarkNaiveProducer(bootstrap, durationSec));
            case "BULK" -> results.add(benchmarkBulkProducer(bootstrap, durationSec, 1000));
            case "MEGA" -> results.add(benchmarkBulkProducer(bootstrap, durationSec, 10000));
            case "CONSUMER" -> results.add(benchmarkConsumer(bootstrap, durationSec));
            case "ALL" -> {
                results.add(benchmarkNaiveProducer(bootstrap, durationSec));
                results.add(benchmarkBulkProducer(bootstrap, durationSec, 1000));
                results.add(benchmarkBulkProducer(bootstrap, durationSec, 10000));
                results.add(benchmarkConsumer(bootstrap, durationSec));
            }
            default -> System.out.println("Unknown mode: " + mode);
        }

        // Print summary
        printSummary(results);

        // Write report
        writeReport(results, reportsDir);
    }

    /**
     * NAIVE PRODUCER: 1 Kafka send() per message.
     * This is the worst case for throughput - no batching benefit.
     * Each message contains a sequence number for verification.
     */
    static Result benchmarkNaiveProducer(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("NAIVE PRODUCER: 1 Kafka send() per message (worst case)");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        String topic = "benchmark-naive-" + System.currentTimeMillis();
        Properties props = producerProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, acksConfig);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);      // No batching delay
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // Small batches

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

        // Warmup (not counted, separate offset range)
        System.out.println("Warming up...");
        for (int i = 0; i < 10_000; i++) {
            producer.send(new ProducerRecord<>(topic, buildMessageWithSequence(-1)));  // -1 = warmup
        }
        producer.flush();

        // Get starting offset after warmup
        long startOffset = getTopicRecordCount(bootstrap, topic);

        // Benchmark with sequence numbers
        System.out.printf("Running for %d seconds (acks=%s)...%n", durationSec, acksConfig);
        AtomicLong sequence = new AtomicLong(0);
        LongAdder count = new LongAdder();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSec);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    while (Instant.now().isBefore(end)) {
                        long seq = sequence.incrementAndGet();
                        producer.send(new ProducerRecord<>(topic, buildMessageWithSequence(seq)));
                        count.increment();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        System.out.println("Flushing to Kafka...");
        producer.flush();
        producer.close();
        executor.shutdown();

        Duration elapsed = Duration.between(start, Instant.now());
        double throughput = count.sum() / (elapsed.toMillis() / 1000.0);
        long expectedMessages = count.sum();
        long lastSequence = sequence.get();

        System.out.printf("Sent: %,d messages in %.2fs = %,.0f msg/s%n",
            expectedMessages, elapsed.toMillis() / 1000.0, throughput);

        // ═══════════════════════════════════════════════════════════════════════
        // VALIDATION: Verify messages are actually in Kafka
        // ═══════════════════════════════════════════════════════════════════════
        System.out.println();
        System.out.println("VALIDATION:");

        // 1. Check Kafka offset count
        long endOffset = getTopicRecordCount(bootstrap, topic);
        long kafkaRecords = endOffset - startOffset;
        System.out.printf("  Kafka records: %,d (expected %,d)%n", kafkaRecords, expectedMessages);

        boolean offsetMatch = kafkaRecords == expectedMessages;
        if (offsetMatch) {
            System.out.println("  ✓ Offset count matches expected");
        } else {
            System.out.printf("  ✗ Offset mismatch! Kafka has %,d, expected %,d%n", kafkaRecords, expectedMessages);
        }

        // 2. Verify last message sequence
        long foundSequence = verifyLastMessage(bootstrap, topic, lastSequence);
        boolean sequenceMatch = foundSequence > 0 && foundSequence <= lastSequence;
        if (sequenceMatch) {
            System.out.printf("  ✓ Last message verified (seq=%,d)%n", foundSequence);
        } else {
            System.out.printf("  ✗ Could not verify last message (expected seq near %,d, found %,d)%n",
                lastSequence, foundSequence);
        }

        boolean verified = offsetMatch && sequenceMatch;
        System.out.printf("  VERIFIED: %s%n", verified ? "YES ✓" : "NO ✗");
        System.out.println();

        return new Result("NAIVE_PRODUCER", expectedMessages, kafkaRecords, verified,
            elapsed.toMillis(), throughput,
            String.format("1 send() per message, acks=%s", acksConfig));
    }

    /**
     * BULK PRODUCER: ALL messages in 1 Kafka send (serialized batch).
     * This is the theoretical maximum - Kafka does 1 I/O for N messages.
     *
     * IMPORTANT: Each Kafka record contains `batchSize` messages serialized together.
     * - Kafka records = number of send() calls (batches)
     * - Logical messages = batchSize × number of batches
     *
     * @param batchSize Number of messages per Kafka send (e.g., 1000 or 10000)
     */
    static Result benchmarkBulkProducer(String bootstrap, int durationSec, int batchSize) throws Exception {
        String modeName = batchSize >= 10000 ? "MEGA" : "BULK";
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("%s PRODUCER: Batch %d messages into 1 Kafka send%n", modeName, batchSize);
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        String topic = "benchmark-bulk-" + System.currentTimeMillis();
        Properties props = producerProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, acksConfig);
        props.put(ProducerConfig.LINGER_MS_CONFIG, batchSize >= 10000 ? 100 : 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16777216);  // 16MB batches
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 134217728L); // 128MB buffer
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 16777216); // 16MB max request

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

        // Warmup (not counted)
        System.out.println("Warming up...");
        byte[] warmupPayload = buildBatchPayloadWithSequence(batchSize, -1);
        for (int i = 0; i < 100; i++) {
            producer.send(new ProducerRecord<>(topic, "warmup", warmupPayload));
        }
        producer.flush();

        // Get starting offset after warmup
        long startOffset = getTopicRecordCount(bootstrap, topic);

        // Benchmark with sequence numbers in batches
        System.out.printf("Running for %d seconds (batch size: %d, acks=%s)...%n", durationSec, batchSize, acksConfig);
        AtomicLong batchSequence = new AtomicLong(0);
        LongAdder messageCount = new LongAdder();
        LongAdder batchCount = new LongAdder();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSec);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    while (Instant.now().isBefore(end)) {
                        long seq = batchSequence.incrementAndGet();
                        byte[] payload = buildBatchPayloadWithSequence(batchSize, seq);
                        producer.send(new ProducerRecord<>(topic, "batch", payload));
                        messageCount.add(batchSize);
                        batchCount.increment();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        System.out.println("Flushing to Kafka...");
        producer.flush();
        producer.close();
        executor.shutdown();

        Duration elapsed = Duration.between(start, Instant.now());
        double throughput = messageCount.sum() / (elapsed.toMillis() / 1000.0);
        double kafkaSendsPerSec = batchCount.sum() / (elapsed.toMillis() / 1000.0);
        long expectedBatches = batchCount.sum();
        long expectedMessages = messageCount.sum();
        long lastBatchSequence = batchSequence.get();

        System.out.printf("Sent: %,d messages (%,d Kafka records) in %.2fs%n",
            expectedMessages, expectedBatches, elapsed.toMillis() / 1000.0);
        System.out.printf("Throughput: %,.0f msg/s | %,.0f Kafka records/s%n", throughput, kafkaSendsPerSec);

        // ═══════════════════════════════════════════════════════════════════════
        // VALIDATION: Verify Kafka records are actually stored
        // ═══════════════════════════════════════════════════════════════════════
        System.out.println();
        System.out.println("VALIDATION:");

        // 1. Check Kafka offset count (number of records, NOT logical messages)
        long endOffset = getTopicRecordCount(bootstrap, topic);
        long kafkaRecords = endOffset - startOffset;
        System.out.printf("  Kafka records: %,d (expected %,d batches)%n", kafkaRecords, expectedBatches);

        boolean offsetMatch = kafkaRecords == expectedBatches;
        if (offsetMatch) {
            System.out.println("  ✓ Record count matches expected batches");
        } else {
            System.out.printf("  ✗ Record mismatch! Kafka has %,d, expected %,d batches%n", kafkaRecords, expectedBatches);
        }

        // 2. Verify last batch sequence
        long foundSequence = verifyLastBatch(bootstrap, topic, lastBatchSequence);
        boolean sequenceMatch = foundSequence > 0 && foundSequence <= lastBatchSequence;
        if (sequenceMatch) {
            System.out.printf("  ✓ Last batch verified (seq=%,d)%n", foundSequence);
        } else {
            System.out.printf("  ✗ Could not verify last batch (expected seq near %,d, found %,d)%n",
                lastBatchSequence, foundSequence);
        }

        // 3. Calculate verified message count
        long verifiedMessages = kafkaRecords * batchSize;
        System.out.printf("  Verified messages: %,d (%,d batches × %,d per batch)%n",
            verifiedMessages, kafkaRecords, batchSize);

        boolean verified = offsetMatch && sequenceMatch;
        System.out.printf("  VERIFIED: %s%n", verified ? "YES ✓" : "NO ✗");
        System.out.println();

        return new Result(modeName + "_PRODUCER", expectedMessages, kafkaRecords, verified,
            elapsed.toMillis(), throughput,
            String.format("%d msg/batch, acks=%s, LZ4", batchSize, acksConfig));
    }

    /**
     * CONSUMER: Read throughput.
     */
    static Result benchmarkConsumer(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("CONSUMER: Read throughput");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        // First, produce messages to consume
        String topic = "benchmark-consumer-" + System.currentTimeMillis();
        int messagesToProduce = 10_000_000;

        System.out.printf("Producing %,d messages to consume...%n", messagesToProduce);
        produceMessages(bootstrap, topic, messagesToProduce);

        // Now consume
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "benchmark-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1048576);    // 1MB min fetch
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 52428800);   // 50MB max fetch
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 10485760); // 10MB per partition

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));

        // Warmup
        System.out.println("Warming up consumer...");
        consumer.poll(Duration.ofSeconds(1));

        // Benchmark
        System.out.printf("Consuming for %d seconds...%n", durationSec);
        LongAdder count = new LongAdder();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(durationSec);

        while (Instant.now().isBefore(end)) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
            count.add(records.count());
            if (records.isEmpty() && count.sum() >= messagesToProduce) break;
        }

        consumer.close();

        Duration elapsed = Duration.between(start, Instant.now());
        double throughput = count.sum() / (elapsed.toMillis() / 1000.0);

        System.out.printf("Result: %,d messages in %.2fs = %,.0f msg/s%n",
            count.sum(), elapsed.toMillis() / 1000.0, throughput);
        System.out.println();

        return new Result("CONSUMER", count.sum(), count.sum(), true,
            elapsed.toMillis(), throughput, "Optimized fetch settings");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    static Properties producerProps(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return props;
    }

    /**
     * Build batch payload: [sequence:8][count:4][messages...]
     * The sequence number allows verification that batches were stored.
     */
    static byte[] buildBatchPayloadWithSequence(int count, long sequence) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(count * (MESSAGE_SIZE + 4) + 12);
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(sequence);  // 8 bytes: batch sequence for verification
            dos.writeInt(count);      // 4 bytes: message count
            for (int i = 0; i < count; i++) {
                dos.writeInt(MESSAGE_SIZE);
                dos.write(TEST_MESSAGE);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verify last batch in topic by reading its sequence number.
     */
    static long verifyLastBatch(String bootstrap, String topic, long expectedSequence) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verify-batch-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) return -1;

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

            if (lastPartition == null || maxOffset == 0) return -1;

            // Seek to last record
            consumer.seek(lastPartition, maxOffset - 1);
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(5));

            for (ConsumerRecord<String, byte[]> record : records) {
                byte[] value = record.value();
                if (value.length >= 8) {
                    ByteBuffer buf = ByteBuffer.wrap(value);
                    return buf.getLong();  // First 8 bytes are sequence
                }
            }
            return -1;
        } catch (Exception e) {
            System.err.println("Failed to verify last batch: " + e.getMessage());
            return -1;
        }
    }

    static void produceMessages(String bootstrap, String topic, int count) throws Exception {
        Properties props = producerProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 262144);

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);
        int threads = Runtime.getRuntime().availableProcessors();
        int perThread = count / threads;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong produced = new AtomicLong(0);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        producer.send(new ProducerRecord<>(topic, TEST_MESSAGE));
                        if (produced.incrementAndGet() % 1_000_000 == 0) {
                            System.out.printf("  Produced %,d messages...%n", produced.get());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        producer.flush();
        producer.close();
        executor.shutdown();
        System.out.printf("  Done producing %,d messages%n", produced.get());
    }

    static void printSummary(List<Result> results) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              SUMMARY                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("%-18s %12s %14s %10s   %s%n", "Mode", "Messages", "Throughput", "Verified", "Notes");
        System.out.println("─".repeat(90));

        Result best = results.stream().max(Comparator.comparingDouble(r -> r.throughput)).orElse(null);

        for (Result r : results) {
            String marker = r == best ? " ★" : "";
            String verified = r.verified ? "✓ YES" : "✗ NO";
            System.out.printf("%-18s %,12d %,12.0f/s %10s   %s%s%n",
                r.mode, r.messages, r.throughput, verified, r.notes, marker);
        }
        System.out.println("─".repeat(90));
        System.out.println("★ = Best throughput | ✓ = Messages verified in Kafka");
        System.out.println();
    }

    static void writeReport(List<Result> results, String reportsDir) throws IOException {
        Path dir = Path.of(reportsDir);
        Files.createDirectories(dir);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"benchmark\": \"kafka-baseline\",\n");
        json.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"acks\": \"").append(acksConfig).append("\",\n");
        json.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            json.append("    {\n");
            json.append("      \"mode\": \"").append(r.mode).append("\",\n");
            json.append("      \"messages\": ").append(r.messages).append(",\n");
            json.append("      \"kafkaRecords\": ").append(r.kafkaRecords).append(",\n");
            json.append("      \"verified\": ").append(r.verified).append(",\n");
            json.append("      \"durationMs\": ").append(r.durationMs).append(",\n");
            json.append("      \"throughput\": ").append(String.format("%.2f", r.throughput)).append(",\n");
            json.append("      \"notes\": \"").append(r.notes).append("\"\n");
            json.append("    }").append(i < results.size() - 1 ? "," : "").append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        Files.writeString(dir.resolve("results.json"), json.toString());
        System.out.printf("Report written to %s/results.json%n", reportsDir);
    }

    // ========================================================================
    // Validation: Verify messages are actually in Kafka
    // ========================================================================

    /**
     * Get the total number of records in a topic by summing end offsets across all partitions.
     */
    static long getTopicRecordCount(String bootstrap, String topic) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);

        try (AdminClient admin = AdminClient.create(props)) {
            // Get topic partitions
            DescribeTopicsResult descResult = admin.describeTopics(List.of(topic));
            TopicDescription desc = descResult.topicNameValues().get(topic).get(5, TimeUnit.SECONDS);

            // Get end offsets for all partitions
            List<TopicPartition> partitions = desc.partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

            // Use consumer to get end offsets
            Properties consumerProps = new Properties();
            consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProps)) {
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
                return endOffsets.values().stream().mapToLong(Long::longValue).sum();
            }
        } catch (Exception e) {
            System.err.println("Failed to get topic record count: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Consume and verify the last message in a topic.
     * Returns the sequence number if found, or -1 on failure.
     */
    static long verifyLastMessage(String bootstrap, String topic, long expectedSequence) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verify-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            // Get partitions and seek to end - 1
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return -1;
            }

            List<TopicPartition> partitions = partitionInfos.stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            // Find partition with highest offset and seek to last message
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

            // Seek to last message
            consumer.seek(lastPartition, maxOffset - 1);

            // Poll for the last message
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(5));
            if (records.isEmpty()) {
                return -1;
            }

            // Get the last record
            ConsumerRecord<String, byte[]> lastRecord = null;
            for (ConsumerRecord<String, byte[]> record : records) {
                lastRecord = record;
            }

            if (lastRecord == null) {
                return -1;
            }

            // Extract sequence number from payload
            byte[] value = lastRecord.value();
            if (value.length >= 8) {
                ByteBuffer buf = ByteBuffer.wrap(value);
                return buf.getLong();  // First 8 bytes are sequence number
            }

            return -1;
        } catch (Exception e) {
            System.err.println("Failed to verify last message: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Build a message payload with sequence number for verification.
     * Format: [sequence:8 bytes][padding to MESSAGE_SIZE]
     */
    static byte[] buildMessageWithSequence(long sequence) {
        ByteBuffer buf = ByteBuffer.allocate(MESSAGE_SIZE);
        buf.putLong(sequence);  // First 8 bytes are sequence number
        // Rest is padding (already zeroed)
        return buf.array();
    }

    record Result(String mode, long messages, long kafkaRecords, boolean verified,
                  long durationMs, double throughput, String notes) {}
}
