package com.reactive.platform.kafka.benchmark;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Quick test: Does bigger batch = better Kafka throughput?
 * Tests raw Kafka producer with different batch sizes.
 */
public class QuickBatchTest {

    private static final String TOPIC = "quick-batch-test";
    private static final int DURATION_SEC = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";

        System.out.println("\n═══════════════════════════════════════════════════════════════");
        System.out.println("QUICK BATCH TEST: Does bigger batch = better throughput?");
        System.out.println("═══════════════════════════════════════════════════════════════\n");

        // Test different batch sizes with direct Kafka producer
        int[] batchSizes = {100, 500, 1000, 2000, 5000, 10000, 20000, 50000};
        byte[] event = new byte[100];

        System.out.printf("%-12s │ %-15s │ %-15s │ %-12s%n",
            "Batch Size", "Msg Throughput", "Batch Rate", "Latency");
        System.out.println("─".repeat(60));

        long bestMsgThroughput = 0;
        int bestBatchSize = 0;

        for (int batchSize : batchSizes) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, "1");
            props.put(ProducerConfig.LINGER_MS_CONFIG, "100");  // Wait for batches
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(16 * 1024 * 1024));
            props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, String.valueOf(256 * 1024 * 1024));
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

            try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
                LongAdder msgCount = new LongAdder();
                LongAdder batchCount = new LongAdder();
                LongAdder latencyNanos = new LongAdder();

                long start = System.currentTimeMillis();
                long deadline = start + DURATION_SEC * 1000L;

                // Create batches and send
                while (System.currentTimeMillis() < deadline) {
                    // Create a bulk message with N events
                    byte[] bulk = new byte[4 + batchSize * 100];
                    bulk[0] = (byte) (batchSize >> 24);
                    bulk[1] = (byte) (batchSize >> 16);
                    bulk[2] = (byte) (batchSize >> 8);
                    bulk[3] = (byte) batchSize;

                    long sendStart = System.nanoTime();
                    producer.send(new ProducerRecord<>(TOPIC, bulk));
                    latencyNanos.add(System.nanoTime() - sendStart);

                    msgCount.add(batchSize);
                    batchCount.increment();
                }

                producer.flush();
                long elapsed = System.currentTimeMillis() - start;

                double msgThroughput = msgCount.sum() * 1000.0 / elapsed;
                double batchRate = batchCount.sum() * 1000.0 / elapsed;
                double avgLatencyMs = latencyNanos.sum() / (double) batchCount.sum() / 1_000_000.0;

                String marker = msgThroughput > bestMsgThroughput ? " ★" : "";
                if (msgThroughput > bestMsgThroughput) {
                    bestMsgThroughput = (long) msgThroughput;
                    bestBatchSize = batchSize;
                }

                System.out.printf("%-12d │ %12.1fM/s │ %12.0f/s │ %9.2fms%s%n",
                    batchSize, msgThroughput / 1_000_000.0, batchRate, avgLatencyMs, marker);
            }
        }

        System.out.println("─".repeat(60));
        System.out.printf("\nBest: batch=%d → %.1fM msg/s%n", bestBatchSize, bestMsgThroughput / 1_000_000.0);

        if (bestBatchSize > 10000) {
            System.out.println("\n✓ CONFIRMED: Bigger batches = better throughput");
            System.out.println("  Current hardcoded limits are too conservative!");
        } else {
            System.out.println("\n→ Optimal batch size is moderate on this system");
        }
    }
}
