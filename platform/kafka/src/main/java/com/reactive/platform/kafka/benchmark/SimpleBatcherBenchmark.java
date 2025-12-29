package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.SimpleBatcher;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

// TODO 4.1: Review for deletion - uses @Deprecated SimpleBatcher (replaced by PartitionedBatcher)
/**
 * Benchmark for SimpleBatcher - minimal design, no complexity.
 */
public class SimpleBatcherBenchmark {

    private static final int MESSAGE_SIZE = 64;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      SIMPLE BATCHER BENCHMARK                                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("One buffer, flush when full OR timer. No levels, no cascade.");
        System.out.println();

        // Test 1: 1MB threshold, 10ms interval (low latency)
        System.out.println("=== SIMPLE BATCHER (1MB, 10ms) - Low Latency ===");
        long throughput1 = runTest(bootstrap, 1024, 10);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput1);

        // Test 2: 16MB threshold, 100ms interval (balanced)
        System.out.println("=== SIMPLE BATCHER (16MB, 100ms) - Balanced ===");
        long throughput2 = runTest(bootstrap, 16384, 100);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput2);

        // Test 3: 64MB threshold, 1s interval (high throughput)
        System.out.println("=== SIMPLE BATCHER (64MB, 1s) - High Throughput ===");
        long throughput3 = runTest(bootstrap, 65536, 1000);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput3);

        // Test 4: 128MB threshold, 5s interval (max throughput)
        System.out.println("=== SIMPLE BATCHER (128MB, 5s) - Max Throughput ===");
        long throughput4 = runTest(bootstrap, 131072, 5000);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput4);

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY (BULK baseline ~100M msg/s):");
        System.out.printf("  1MB/10ms:    %,12d msg/s%n", throughput1);
        System.out.printf("  16MB/100ms:  %,12d msg/s%n", throughput2);
        System.out.printf("  64MB/1s:     %,12d msg/s%n", throughput3);
        System.out.printf("  128MB/5s:    %,12d msg/s%n", throughput4);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runTest(String bootstrap, int thresholdKB, long intervalMs) throws Exception {
        String topic = "simple-test-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder sent = new LongAdder();
        LongAdder kafkaSends = new LongAdder();
        LongAdder byteSent = new LongAdder();

        SimpleBatcher batcher = new SimpleBatcher(
            data -> {
                producer.send(new ProducerRecord<>(topic, data));
                kafkaSends.increment();
                byteSent.add(data.length);
            },
            thresholdKB,
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
