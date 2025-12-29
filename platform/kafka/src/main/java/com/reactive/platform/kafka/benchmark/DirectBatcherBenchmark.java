package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.DirectBatcher;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

// TODO 4.1: Review for deletion - uses @Deprecated DirectBatcher (replaced by PartitionedBatcher)
/**
 * Simple benchmark comparing DirectBatcher to BULK baseline.
 *
 * Tests:
 * 1. BULK - direct producer.send() with pre-built batches
 * 2. DirectBatcher - per-message submission with adaptive batching
 */
public class DirectBatcherBenchmark {

    private static final int MESSAGE_SIZE = 64;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    DIRECT BATCHER BENCHMARK                                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Test 1: BULK baseline
        System.out.println("=== BULK BASELINE (direct producer.send with pre-built batches) ===");
        long bulkThroughput = runBulkTest(bootstrap);
        System.out.printf("BULK: %,d msg/s%n%n", bulkThroughput);

        // Test 2: DirectBatcher with high-throughput settings (1M messages, 30s interval)
        System.out.println("=== DIRECT BATCHER (per-message submit, 1M batch, 30s interval) ===");
        long directThroughput = runDirectBatcherTest(bootstrap, 1_000_000, 30000);
        System.out.printf("DirectBatcher: %,d msg/s%n%n", directThroughput);

        // Test 3: DirectBatcher matching BULK batch size (1000 messages, 5ms interval)
        System.out.println("=== DIRECT BATCHER (per-message submit, 1K batch, 5ms interval) ===");
        long matchBulkThroughput = runDirectBatcherTest(bootstrap, 1000, 5);
        System.out.printf("DirectBatcher (match BULK): %,d msg/s%n%n", matchBulkThroughput);

        // Test 4: DirectBatcher with low-latency settings
        System.out.println("=== DIRECT BATCHER (per-message submit, 100 batch, 1ms interval) ===");
        long lowLatencyThroughput = runDirectBatcherTest(bootstrap, 100, 1);
        System.out.printf("DirectBatcher (low latency): %,d msg/s%n%n", lowLatencyThroughput);

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  BULK baseline:           %,12d msg/s%n", bulkThroughput);
        System.out.printf("  DirectBatcher (1M):      %,12d msg/s (%.1f%% of BULK)%n",
            directThroughput, 100.0 * directThroughput / bulkThroughput);
        System.out.printf("  DirectBatcher (1K):      %,12d msg/s (%.1f%% of BULK)%n",
            matchBulkThroughput, 100.0 * matchBulkThroughput / bulkThroughput);
        System.out.printf("  DirectBatcher (100):     %,12d msg/s (%.1f%% of BULK)%n",
            lowLatencyThroughput, 100.0 * lowLatencyThroughput / bulkThroughput);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runBulkTest(String bootstrap) throws Exception {
        String topic = "bulk-test-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        int batchSize = 1000;
        byte[] batch = new byte[batchSize * MESSAGE_SIZE];
        // Pre-fill batch
        for (int i = 0; i < batchSize; i++) {
            ByteBuffer.wrap(batch, i * MESSAGE_SIZE, MESSAGE_SIZE).putLong(i);
        }

        LongAdder sent = new LongAdder();
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long endTime = System.nanoTime() + DURATION_SECONDS * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    while (System.nanoTime() < endTime) {
                        producer.send(new ProducerRecord<>(topic, batch));
                        sent.add(batchSize);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        producer.flush();
        producer.close();

        return sent.sum() / DURATION_SECONDS;
    }

    static long runDirectBatcherTest(String bootstrap, int thresholdMessages, long intervalMs) throws Exception {
        String topic = "direct-test-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder sent = new LongAdder();
        LongAdder kafkaSends = new LongAdder();

        DirectBatcher batcher = new DirectBatcher(
            data -> {
                producer.send(new ProducerRecord<>(topic, data));
                kafkaSends.increment();
            },
            thresholdMessages,
            intervalMs
        );

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong sequence = new AtomicLong(0);

        long endTime = System.nanoTime() + DURATION_SECONDS * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    while (System.nanoTime() < endTime) {
                        long seq = sequence.incrementAndGet();
                        ByteBuffer.wrap(msg).putLong(seq);
                        batcher.submit(msg);
                        sent.increment();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        batcher.close();
        producer.flush();
        producer.close();

        System.out.printf("  (Kafka sends: %,d, avg batch: %,d msgs)%n",
            kafkaSends.sum(), kafkaSends.sum() > 0 ? sent.sum() / kafkaSends.sum() : 0);

        return sent.sum() / DURATION_SECONDS;
    }

    static KafkaProducer<String, byte[]> createProducer(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16777216);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 134217728L);
        return new KafkaProducer<>(props);
    }
}
