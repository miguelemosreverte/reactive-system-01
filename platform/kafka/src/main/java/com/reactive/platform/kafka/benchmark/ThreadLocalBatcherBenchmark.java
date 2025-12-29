package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.ThreadLocalBatcher;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

// TODO 4.1: Review for deletion - uses @Deprecated ThreadLocalBatcher (replaced by PartitionedBatcher)
/**
 * Benchmark comparing ThreadLocalBatcher to BULK baseline.
 */
public class ThreadLocalBatcherBenchmark {

    private static final int MESSAGE_SIZE = 64;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                 THREAD-LOCAL BATCHER BENCHMARK                                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Test with 1000 batch size (matches BULK)
        System.out.println("=== THREAD-LOCAL BATCHER (1K batch, 5ms interval) ===");
        long throughput1K = runTest(bootstrap, 1000, 5);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput1K);

        // Test with larger batch (high throughput scenario)
        System.out.println("=== THREAD-LOCAL BATCHER (10K batch, 100ms interval) ===");
        long throughput10K = runTest(bootstrap, 10000, 100);
        System.out.printf("Throughput: %,d msg/s%n%n", throughput10K);

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("Compare to BULK baseline (run separately): ~100M msg/s");
        System.out.printf("ThreadLocalBatcher (1K):   %,d msg/s%n", throughput1K);
        System.out.printf("ThreadLocalBatcher (10K):  %,d msg/s%n", throughput10K);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runTest(String bootstrap, int batchThreshold, long intervalMs) throws Exception {
        String topic = "tlb-test-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder sent = new LongAdder();
        LongAdder kafkaSends = new LongAdder();

        ThreadLocalBatcher batcher = new ThreadLocalBatcher(
            data -> {
                producer.send(new ProducerRecord<>(topic, data));
                kafkaSends.increment();
            },
            batchThreshold,
            intervalMs,
            MESSAGE_SIZE
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
                    batcher.flushCurrent(); // Flush this thread's remaining batch
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
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
