package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.ExponentialBatcher;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark for ExponentialBatcher.
 */
public class ExponentialBatcherBenchmark {

    private static final int MESSAGE_SIZE = 64;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 EXPONENTIAL BATCHER BENCHMARK                                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Bucket levels: 1KB → 10KB → 100KB → 1MB → 10MB → 100MB");
        System.out.println();

        // Test 1: Flush at Level 1 (6MB), 100ms interval
        System.out.println("=== EXPONENTIAL BATCHER (flush at 6MB, 100ms interval) ===");
        long throughput1 = runTest(bootstrap, 1, 100);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput1);

        // Test 2: Flush at Level 2 (60MB), 1s interval - high throughput
        System.out.println("=== EXPONENTIAL BATCHER (flush at 60MB, 1s interval) ===");
        long throughput2 = runTest(bootstrap, 2, 1000);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput2);

        // Test 3: Flush at Level 3 (200MB), 30s interval - max throughput
        System.out.println("=== EXPONENTIAL BATCHER (flush at 200MB, 30s interval) ===");
        long throughput3 = runTest(bootstrap, 3, 30000);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput3);

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY (compare to BULK baseline ~100M msg/s):");
        System.out.printf("  Flush at 6MB:    %,12d msg/s%n", throughput1);
        System.out.printf("  Flush at 60MB:   %,12d msg/s%n", throughput2);
        System.out.printf("  Flush at 200MB:  %,12d msg/s%n", throughput3);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runTest(String bootstrap, int flushLevel, long intervalMs) throws Exception {
        String topic = "exp-test-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder sent = new LongAdder();
        LongAdder kafkaSends = new LongAdder();
        LongAdder byteSent = new LongAdder();

        ExponentialBatcher batcher = new ExponentialBatcher(
            data -> {
                producer.send(new ProducerRecord<>(topic, data));
                kafkaSends.increment();
                byteSent.add(data.length);
            },
            flushLevel,
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

        long avgBatchBytes = kafkaSends.sum() > 0 ? byteSent.sum() / kafkaSends.sum() : 0;
        System.out.printf("  (Kafka sends: %,d, avg batch: %,d KB, total: %,d MB)%n",
            kafkaSends.sum(),
            avgBatchBytes / 1024,
            byteSent.sum() / (1024 * 1024));

        return sent.sum() / DURATION_SECONDS;
    }

    static KafkaProducer<String, byte[]> createProducer(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 100);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16777216);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 268435456L);  // 256MB
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 104857600);  // 100MB
        return new KafkaProducer<>(props);
    }
}
