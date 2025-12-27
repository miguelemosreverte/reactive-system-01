package com.reactive.platform.kafka.benchmark;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;

import java.io.*;
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
 *   BULK      - ALL messages in 1 send (best case for Kafka)
 *   CONSUMER  - Read throughput
 *   ALL       - Run all modes and generate comparison report
 *
 * Usage:
 *   java KafkaBaselineBenchmark <mode> <durationSec> <kafkaBootstrap> <reportsDir>
 */
public class KafkaBaselineBenchmark {

    private static final int MESSAGE_SIZE = 64;  // bytes per message
    private static final byte[] TEST_MESSAGE = new byte[MESSAGE_SIZE];

    static {
        Arrays.fill(TEST_MESSAGE, (byte) 'X');
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toUpperCase() : "ALL";
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        String bootstrap = args.length > 2 ? args[2] : "kafka:29092";
        String reportsDir = args.length > 3 ? args[3] : "reports/kafka-baseline";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    KAFKA BASELINE BENCHMARK                                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Mode:      %s%n", mode);
        System.out.printf("  Duration:  %d seconds per test%n", durationSec);
        System.out.printf("  Kafka:     %s%n", bootstrap);
        System.out.printf("  Reports:   %s%n", reportsDir);
        System.out.println();

        List<Result> results = new ArrayList<>();

        switch (mode) {
            case "NAIVE" -> results.add(benchmarkNaiveProducer(bootstrap, durationSec));
            case "BULK" -> results.add(benchmarkBulkProducer(bootstrap, durationSec));
            case "CONSUMER" -> results.add(benchmarkConsumer(bootstrap, durationSec));
            case "ALL" -> {
                results.add(benchmarkNaiveProducer(bootstrap, durationSec));
                results.add(benchmarkBulkProducer(bootstrap, durationSec));
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
     */
    static Result benchmarkNaiveProducer(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("NAIVE PRODUCER: 1 Kafka send() per message (worst case)");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        String topic = "benchmark-naive-" + System.currentTimeMillis();
        Properties props = producerProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);      // No batching delay
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // Small batches

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < 10_000; i++) {
            producer.send(new ProducerRecord<>(topic, TEST_MESSAGE));
        }
        producer.flush();

        // Benchmark
        System.out.printf("Running for %d seconds...%n", durationSec);
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
                        producer.send(new ProducerRecord<>(topic, TEST_MESSAGE));
                        count.increment();
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

        Duration elapsed = Duration.between(start, Instant.now());
        double throughput = count.sum() / (elapsed.toMillis() / 1000.0);

        System.out.printf("Result: %,d messages in %.2fs = %,.0f msg/s%n",
            count.sum(), elapsed.toMillis() / 1000.0, throughput);
        System.out.println();

        return new Result("NAIVE_PRODUCER", count.sum(), elapsed.toMillis(), throughput,
            "1 send() per message, no batching benefit");
    }

    /**
     * BULK PRODUCER: ALL messages in 1 Kafka send (serialized batch).
     * This is the theoretical maximum - Kafka does 1 I/O for N messages.
     */
    static Result benchmarkBulkProducer(String bootstrap, int durationSec) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("BULK PRODUCER: Batch N messages into 1 Kafka send (best case)");
        System.out.println("═══════════════════════════════════════════════════════════════════════");

        String topic = "benchmark-bulk-" + System.currentTimeMillis();
        Properties props = producerProps(bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 1048576);  // 1MB batches
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864L); // 64MB buffer

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

        // Pre-build batch payload: [count:4][len:4][data]...
        int batchSize = 1000;  // 1000 messages per Kafka send
        byte[] batchPayload = buildBatchPayload(batchSize);

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < 100; i++) {
            producer.send(new ProducerRecord<>(topic, "batch", batchPayload));
        }
        producer.flush();

        // Benchmark
        System.out.printf("Running for %d seconds (batch size: %d messages per send)...%n", durationSec, batchSize);
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
                        producer.send(new ProducerRecord<>(topic, "batch", batchPayload));
                        messageCount.add(batchSize);
                        batchCount.increment();
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

        Duration elapsed = Duration.between(start, Instant.now());
        double throughput = messageCount.sum() / (elapsed.toMillis() / 1000.0);
        double kafkaSendsPerSec = batchCount.sum() / (elapsed.toMillis() / 1000.0);

        System.out.printf("Result: %,d messages (%,d batches) in %.2fs = %,.0f msg/s%n",
            messageCount.sum(), batchCount.sum(), elapsed.toMillis() / 1000.0, throughput);
        System.out.printf("Kafka sends/sec: %,.0f (each send = %d messages)%n", kafkaSendsPerSec, batchSize);
        System.out.println();

        return new Result("BULK_PRODUCER", messageCount.sum(), elapsed.toMillis(), throughput,
            String.format("%d messages per Kafka send, LZ4 compression", batchSize));
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

        return new Result("CONSUMER", count.sum(), elapsed.toMillis(), throughput,
            "Optimized fetch settings");
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

    static byte[] buildBatchPayload(int count) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(count * (MESSAGE_SIZE + 4) + 4);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(count);
        for (int i = 0; i < count; i++) {
            dos.writeInt(MESSAGE_SIZE);
            dos.write(TEST_MESSAGE);
        }
        dos.flush();
        return baos.toByteArray();
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
        System.out.printf("%-20s %15s %15s   %s%n", "Mode", "Messages", "Throughput", "Notes");
        System.out.println("─".repeat(80));

        Result best = results.stream().max(Comparator.comparingDouble(r -> r.throughput)).orElse(null);

        for (Result r : results) {
            String marker = r == best ? " ★" : "";
            System.out.printf("%-20s %,15d %,13.0f/s   %s%s%n",
                r.mode, r.messages, r.throughput, r.notes, marker);
        }
        System.out.println("─".repeat(80));
        System.out.println("★ = Best throughput");
        System.out.println();
    }

    static void writeReport(List<Result> results, String reportsDir) throws IOException {
        Path dir = Path.of(reportsDir);
        Files.createDirectories(dir);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"benchmark\": \"kafka-baseline\",\n");
        json.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            json.append("    {\n");
            json.append("      \"mode\": \"").append(r.mode).append("\",\n");
            json.append("      \"messages\": ").append(r.messages).append(",\n");
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

    record Result(String mode, long messages, long durationMs, double throughput, String notes) {}
}
