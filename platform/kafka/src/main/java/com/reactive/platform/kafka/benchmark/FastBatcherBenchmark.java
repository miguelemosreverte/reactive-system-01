package com.reactive.platform.kafka.benchmark;

import com.reactive.platform.gateway.microbatch.FastBatcher;
import com.reactive.platform.gateway.microbatch.StripedBatcher;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark: FastBatcher vs StripedBatcher using ONLY send(message).
 *
 * Both must use the same interface. No cheating with sendBatch().
 */
public class FastBatcherBenchmark {

    private static final int MESSAGE_SIZE = BenchmarkConstants.MESSAGE_SIZE;
    private static final int DURATION_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║             FAST BATCHER vs STRIPED BATCHER BENCHMARK                       ║");
        System.out.println("║                     Using only send(message)                                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("Threads: %d, Message size: %d bytes, Duration: %d seconds%n%n",
            threads, MESSAGE_SIZE, DURATION_SECONDS);

        // Warmup
        System.out.println("Warming up...");
        runFastBatcher(bootstrap, 2);
        runStripedBatcher(bootstrap, 2);
        System.out.println();

        // Benchmark FastBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("FAST BATCHER (ThreadLocal accumulation)");
        System.out.println("───────────────────────────────────────────────────────────────────────────────");
        long fastThroughput = runFastBatcher(bootstrap, DURATION_SECONDS);
        System.out.printf("  Throughput: %,d msg/s%n", fastThroughput);
        System.out.printf("  Per-message: %.1f ns%n", 1_000_000_000.0 / fastThroughput);
        System.out.println();

        // Benchmark StripedBatcher
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("STRIPED BATCHER (Atomic per-stripe)");
        System.out.println("───────────────────────────────────────────────────────────────────────────────");
        long stripedThroughput = runStripedBatcher(bootstrap, DURATION_SECONDS);
        System.out.printf("  Throughput: %,d msg/s%n", stripedThroughput);
        System.out.printf("  Per-message: %.1f ns%n", 1_000_000_000.0 / stripedThroughput);
        System.out.println();

        // Summary
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.printf("  FastBatcher:    %,12d msg/s%n", fastThroughput);
        System.out.printf("  StripedBatcher: %,12d msg/s%n", stripedThroughput);
        System.out.printf("  Ratio:          %.2fx%n", (double) fastThroughput / stripedThroughput);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
    }

    static long runFastBatcher(String bootstrap, int durationSec) throws Exception {
        String topic = "fast-bench-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder kafkaSends = new LongAdder();
        LongAdder kafkaBytes = new LongAdder();

        FastBatcher batcher = new FastBatcher(data -> {
            producer.send(new ProducerRecord<>(topic, data));
            kafkaSends.increment();
            kafkaBytes.add(data.length);
        });

        long count = runBenchmark(batcher, durationSec);

        batcher.close();
        producer.flush();
        producer.close();

        long avgBatch = kafkaSends.sum() > 0 ? kafkaBytes.sum() / kafkaSends.sum() : 0;
        System.out.printf("  Kafka sends: %,d, avg batch: %,d KB%n",
            kafkaSends.sum(), avgBatch / 1024);

        return count / durationSec;
    }

    static long runStripedBatcher(String bootstrap, int durationSec) throws Exception {
        String topic = "striped-bench-" + System.currentTimeMillis();
        KafkaProducer<String, byte[]> producer = createProducer(bootstrap);

        LongAdder kafkaSends = new LongAdder();
        LongAdder kafkaBytes = new LongAdder();

        StripedBatcher batcher = new StripedBatcher(data -> {
            producer.send(new ProducerRecord<>(topic, data));
            kafkaSends.increment();
            kafkaBytes.add(data.length);
        }, 16384, 100);  // 16MB threshold, 100ms interval

        long count = runBenchmark(batcher, durationSec);

        batcher.close();
        producer.flush();
        producer.close();

        long avgBatch = kafkaSends.sum() > 0 ? kafkaBytes.sum() / kafkaSends.sum() : 0;
        System.out.printf("  Kafka sends: %,d, avg batch: %,d KB%n",
            kafkaSends.sum(), avgBatch / 1024);

        return count / durationSec;
    }

    static long runBenchmark(com.reactive.platform.gateway.microbatch.MessageBatcher batcher, int durationSec) throws Exception {
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong sequence = new AtomicLong(0);

        long[] counts = new long[threads];
        long endTime = System.nanoTime() + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    byte[] msg = new byte[MESSAGE_SIZE];
                    long count = 0;
                    while (System.nanoTime() < endTime) {
                        long seq = sequence.incrementAndGet();
                        ByteBuffer.wrap(msg).putLong(seq);
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

        return java.util.Arrays.stream(counts).sum();
    }

    static KafkaProducer<String, byte[]> createProducer(String bootstrap) {
        return ProducerFactory.createHighThroughput(bootstrap);
    }
}
