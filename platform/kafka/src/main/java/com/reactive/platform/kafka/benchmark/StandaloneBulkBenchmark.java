package com.reactive.platform.kafka.benchmark;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Standalone BULK benchmark - minimal dependencies for running inside Docker.
 *
 * Usage: java -cp kafka-clients.jar:. StandaloneBulkBenchmark <bootstrap> <durationSec>
 *
 * This tests the theoretical maximum Kafka throughput by batching N messages
 * into a single Kafka send() call.
 */
public class StandaloneBulkBenchmark {

    public static void main(String[] args) throws Exception {
        String bootstrap = args.length > 0 ? args[0] : "localhost:9092";
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        int batchSize = args.length > 2 ? Integer.parseInt(args[2]) : 1000;

        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              STANDALONE BULK BENCHMARK                                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.printf("  Bootstrap:  %s%n", bootstrap);
        System.out.printf("  Duration:   %d seconds%n", durationSec);
        System.out.printf("  Batch size: %d messages per Kafka send%n", batchSize);
        System.out.printf("  Threads:    %d%n", Runtime.getRuntime().availableProcessors());
        System.out.println();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16777216");  // 16MB
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "134217728");  // 128MB
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, "16777216");

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

        // Build batch payload
        byte[] batchPayload = buildBatchPayload(batchSize);
        System.out.printf("  Payload:    %d bytes (%d messages × 64 bytes + headers)%n", batchPayload.length, batchSize);
        System.out.println();

        String topic = "bulk-benchmark-" + System.currentTimeMillis();

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < 100; i++) {
            producer.send(new ProducerRecord<>(topic, "batch", batchPayload));
        }
        producer.flush();

        // Benchmark
        System.out.printf("Running for %d seconds...%n", durationSec);
        LongAdder messageCount = new LongAdder();
        LongAdder batchCount = new LongAdder();
        long start = System.currentTimeMillis();
        long deadline = start + (durationSec * 1000L);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    while (System.currentTimeMillis() < deadline) {
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
        long sendPhaseEnd = System.currentTimeMillis();
        long sendPhaseDuration = sendPhaseEnd - start;

        System.out.println("Flushing...");
        producer.flush();
        producer.close();
        exec.shutdown();

        long totalElapsed = System.currentTimeMillis() - start;

        // Send rate = messages / send phase duration (what we can push)
        double sendRate = messageCount.sum() * 1000.0 / sendPhaseDuration;
        // Sustained rate = messages / total time including flush (what Kafka can absorb)
        double sustainedRate = messageCount.sum() * 1000.0 / totalElapsed;

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.printf("  Messages:       %,d%n", messageCount.sum());
        System.out.printf("  Batches:        %,d%n", batchCount.sum());
        System.out.printf("  Send phase:     %.2f seconds%n", sendPhaseDuration / 1000.0);
        System.out.printf("  Flush time:     %.2f seconds%n", (totalElapsed - sendPhaseDuration) / 1000.0);
        System.out.printf("  Total elapsed:  %.2f seconds%n", totalElapsed / 1000.0);
        System.out.println("───────────────────────────────────────────────────────────────────────");
        System.out.printf("  SEND RATE:      %,.0f msg/s  (fire-and-forget)%n", sendRate);
        System.out.printf("  SUSTAINED RATE: %,.0f msg/s  (including flush)%n", sustainedRate);
        System.out.println("═══════════════════════════════════════════════════════════════════════");
    }

    static byte[] buildBatchPayload(int count) throws IOException {
        byte[] msg = new byte[64];
        Arrays.fill(msg, (byte) 'X');

        ByteArrayOutputStream baos = new ByteArrayOutputStream(count * 68 + 4);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(count);
        for (int i = 0; i < count; i++) {
            dos.writeInt(64);
            dos.write(msg);
        }
        dos.flush();
        return baos.toByteArray();
    }
}
