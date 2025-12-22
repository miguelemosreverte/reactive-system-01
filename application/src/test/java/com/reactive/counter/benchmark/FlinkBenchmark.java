package com.reactive.counter.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reactive.platform.benchmark.BaseBenchmark;
import com.reactive.platform.benchmark.BenchmarkTypes.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Flink Benchmark - tests stream processing throughput.
 *
 * Produces messages to Kafka and measures time until Flink produces results.
 * This tests the Flink job's processing capacity under load.
 *
 * Unlike KafkaBenchmark which measures Kafka round-trip, this focuses on
 * Flink's processing throughput by running with higher concurrency.
 */
public class FlinkBenchmark extends BaseBenchmark {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String EVENTS_TOPIC = "counter-events";  // Gateway sends here
    private static final String RESULTS_TOPIC = "counter-results";

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static FlinkBenchmark create() {
        return new FlinkBenchmark();
    }

    private FlinkBenchmark() {
        super(ComponentId.FLINK);
    }

    // ========================================================================
    // Benchmark Implementation
    // ========================================================================

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String kafkaUrl = getKafkaUrl();
        Instant loopStart = Instant.now();
        Instant warmupEnd = loopStart.plusMillis(config.warmupMs());

        // Track in-flight messages for latency calculation
        Map<String, Long> inFlight = new ConcurrentHashMap<>();

        // Kafka producer - optimized for throughput
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Kafka consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "flink-benchmark-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);

        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(RESULTS_TOPIC));

        // Use more workers for Flink benchmark to stress the stream processor
        int workerCount = Math.max(config.concurrency(), 16);
        ExecutorService producerExecutor = Executors.newFixedThreadPool(workerCount);
        AtomicBoolean warmupComplete = new AtomicBoolean(false);
        AtomicLong lastOpsCount = new AtomicLong(0);

        // Consumer thread - processes results
        Thread consumerThread = new Thread(() -> {
            try {
                while (isRunning()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(50));
                    long now = System.currentTimeMillis();

                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            var result = mapper.readTree(record.value());
                            String eventId = result.path("eventId").asText(null);

                            if (eventId != null) {
                                Long startTime = inFlight.remove(eventId);
                                if (startTime != null) {
                                    long latencyMs = now - startTime;
                                    recordLatency(latencyMs);
                                    recordSuccess();

                                    // Sample events after warmup
                                    if (warmupComplete.get()) {
                                        // Extract timing from result
                                        long flinkProcessingMs = result.path("processingTimeMs").asLong(0);
                                        ComponentTiming timing = new ComponentTiming(
                                                0, // gateway
                                                latencyMs - flinkProcessingMs, // kafka
                                                flinkProcessingMs, // flink
                                                0  // drools
                                        );
                                        addSampleEvent(SampleEvent.success("flink_" + eventId, null, null, latencyMs)
                                                .withTiming(timing));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Ignore parse errors
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Consumer error", e);
            }
        }, "flink-benchmark-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        // Throughput sampler
        Thread throughputSampler = new Thread(() -> {
            while (isRunning()) {
                try {
                    Thread.sleep(1000);
                    if (warmupComplete.get()) {
                        long currentOps = getOperationCount();
                        long throughput = currentOps - lastOpsCount.getAndSet(currentOps);
                        recordThroughputSample(throughput);
                        log.info("Progress: ops={}, throughput={}/s, in-flight={}",
                                currentOps, throughput, inFlight.size());
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        throughputSampler.setDaemon(true);
        throughputSampler.start();

        try {
            // Start producer workers
            for (int i = 0; i < workerCount; i++) {
                int workerId = i;
                producerExecutor.submit(() -> runProducer(workerId, producer, inFlight));
            }

            // Wait for warmup
            long warmupRemaining = warmupEnd.toEpochMilli() - System.currentTimeMillis();
            if (warmupRemaining > 0) {
                Thread.sleep(warmupRemaining);
            }
            warmupComplete.set(true);
            log.info("Warmup complete, starting measurements (workers={})", workerCount);

            // Wait for duration
            long durationRemaining = loopStart.plusMillis(config.durationMs()).toEpochMilli() - System.currentTimeMillis();
            if (durationRemaining > 0 && isRunning()) {
                Thread.sleep(durationRemaining);
            }

            // Drain remaining results
            stop();
            producerExecutor.shutdown();
            producerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            Thread.sleep(3000);
            consumerThread.interrupt();

            // Mark remaining in-flight as timeout
            for (var entry : inFlight.entrySet()) {
                recordLatency(System.currentTimeMillis() - entry.getValue());
                recordFailure();
            }

        } finally {
            throughputSampler.interrupt();
            producerExecutor.shutdownNow();
            producer.close();
            consumer.close();
        }
    }

    private void runProducer(int workerId, KafkaProducer<String, String> producer,
                             Map<String, Long> inFlight) {
        long counter = 0;
        while (isRunning()) {
            long now = System.currentTimeMillis();
            String eventId = "flink_" + workerId + "_" + (counter++);
            String requestId = "flink_req_" + workerId + "_" + now;

            try {
                // Build message matching Gateway's CounterCommand format exactly
                ObjectNode timing = mapper.createObjectNode();
                timing.put("gatewayReceivedAt", now);
                timing.put("gatewayPublishedAt", now);

                ObjectNode command = mapper.createObjectNode();
                command.put("requestId", requestId);
                command.putNull("customerId");
                command.put("eventId", eventId);
                command.put("sessionId", "flink-bench-" + workerId);
                command.put("action", "increment");  // lowercase
                command.put("value", 1);
                command.put("timestamp", now);
                command.set("timing", timing);
                command.put("source", "flink-benchmark");

                inFlight.put(eventId, now);

                producer.send(new ProducerRecord<>(EVENTS_TOPIC, "flink-bench-" + workerId, command.toString()),
                        (metadata, exception) -> {
                            if (exception != null) {
                                inFlight.remove(eventId);
                                recordFailure();
                            }
                        });

            } catch (Exception e) {
                inFlight.remove(eventId);
                recordFailure();
            }
        }
    }

    private String getKafkaUrl() {
        String url = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        return (url != null && !url.isEmpty()) ? url : "kafka:29092";
    }
}
