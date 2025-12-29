package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.StripedBatcher;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal benchmark - eliminate all overhead except batcher.submit().
 */
public class MinimalBenchmark {

    private static final int MESSAGE_SIZE = BenchmarkConstants.MESSAGE_SIZE;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                       MINIMAL OVERHEAD BENCHMARK                             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Single test: 64MB threshold, 1s interval
        System.out.println("=== STRIPED BATCHER (64MB, 1s) - Minimal Overhead ===");
        long throughput = runTest(bootstrap, 65536, 1000);
        System.out.printf("Throughput: %,d msg/s%n", throughput);
        System.out.printf("Per-message overhead: %.1f ns%n", 1_000_000_000.0 / throughput);
    }

    static long runTest(String bootstrap, int thresholdKB, long intervalMs) throws Exception {
        String topic = "minimal-test-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder kafkaSends = new LongAdder();
        LongAdder byteSent = new LongAdder();

        StripedBatcher batcher = new StripedBatcher(
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

        // Pre-allocated messages per thread (no allocation in hot loop)
        byte[][] threadMessages = new byte[threads][];
        for (int i = 0; i < threads; i++) {
            threadMessages[i] = new byte[MESSAGE_SIZE];
        }

        // Per-thread counters (no contention)
        long[] counts = new long[threads];
        long endTime = System.nanoTime() + DURATION_SECONDS * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            final byte[] msg = threadMessages[t];
            executor.submit(() -> {
                try {
                    long count = 0;
                    long end = endTime;
                    while (System.nanoTime() < end) {
                        batcher.send(msg);
                        count++;
                    }
                    counts[threadId] = count;
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

        long total = 0;
        for (long c : counts) total += c;

        long avgBatchBytes = kafkaSends.sum() > 0 ? byteSent.sum() / kafkaSends.sum() : 0;
        System.out.printf("  (Kafka sends: %,d, avg batch: %,d KB, total: %,d MB)%n",
            kafkaSends.sum(),
            avgBatchBytes / 1024,
            byteSent.sum() / (1024 * 1024));

        return total / DURATION_SECONDS;
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
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 268435456L);
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 104857600);
        return new KafkaProducer<>(props);
    }
}
