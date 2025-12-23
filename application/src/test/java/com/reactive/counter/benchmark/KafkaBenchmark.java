package com.reactive.counter.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reactive.platform.benchmark.BaseBenchmark;
import com.reactive.platform.benchmark.BenchmarkResult;
import com.reactive.platform.benchmark.BenchmarkTypes.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka Benchmark - tests Kafka produce/consume round-trip.
 *
 * Produces messages to counter-commands topic and consumes from counter-results.
 * Measures the full Kafka round-trip including Flink processing.
 */
public class KafkaBenchmark extends BaseBenchmark {

    private static final Logger log = LoggerFactory.getLogger(KafkaBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String EVENTS_TOPIC = "counter-events";  // Gateway sends here
    private static final String RESULTS_TOPIC = "counter-results";

    // ========================================================================
    // Static Factories
    // ========================================================================

    public static KafkaBenchmark create() {
        return new KafkaBenchmark();
    }

    private KafkaBenchmark() {
        super(ComponentId.KAFKA);
    }

    // ========================================================================
    // Benchmark Implementation
    // ========================================================================

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String kafkaUrl = getKafkaUrl(config);
        Instant loopStart = Instant.now();
        Instant warmupEnd = loopStart.plusMillis(config.warmupMs());

        // Pending requests: eventId -> startTime
        Map<String, Long> pendingRequests = new ConcurrentHashMap<>();

        // Create Kafka producer
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // Create Kafka consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-benchmark-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(RESULTS_TOPIC));

        ExecutorService producerExecutor = Executors.newFixedThreadPool(config.concurrency());
        AtomicBoolean warmupComplete = new AtomicBoolean(false);
        AtomicLong lastOpsCount = new AtomicLong(0);

        // Start consumer thread
        Thread consumerThread = new Thread(() -> {
            try {
                while (isRunning()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            var result = mapper.readTree(record.value());
                            String eventId = result.path("eventId").asText(null);
                            if (eventId != null) {
                                Long startTime = pendingRequests.remove(eventId);
                                if (startTime != null) {
                                    long latencyMs = System.currentTimeMillis() - startTime;
                                    recordLatency(latencyMs);
                                    recordSuccess();

                                    if (warmupComplete.get()) {
                                        String requestId = result.path("requestId").asText(null);
                                        ComponentTiming timing = new ComponentTiming(0, latencyMs, 0, 0);
                                        addSampleEvent(SampleEvent.success("kafka_" + eventId, requestId, null, latencyMs)
                                                .withTiming(timing));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed to parse result: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Consumer error", e);
            }
        }, "kafka-benchmark-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        // Start throughput sampler
        Thread throughputSampler = new Thread(() -> {
            while (isRunning()) {
                try {
                    Thread.sleep(1000);
                    if (warmupComplete.get()) {
                        long currentOps = getOperationCount();
                        long throughput = currentOps - lastOpsCount.getAndSet(currentOps);
                        recordThroughputSample(throughput);
                        log.info("Progress: ops={}, throughput={}/s, pending={}",
                                currentOps, throughput, pendingRequests.size());
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
            for (int i = 0; i < config.concurrency(); i++) {
                int workerId = i;
                producerExecutor.submit(() -> runProducerWorker(workerId, producer, pendingRequests, config, warmupEnd));
            }

            // Wait for warmup
            long warmupRemaining = warmupEnd.toEpochMilli() - System.currentTimeMillis();
            if (warmupRemaining > 0) {
                Thread.sleep(warmupRemaining);
            }
            warmupComplete.set(true);
            log.info("Warmup complete, starting measurements");

            // Wait for duration
            long durationRemaining = loopStart.plusMillis(config.durationMs()).toEpochMilli() - System.currentTimeMillis();
            if (durationRemaining > 0 && isRunning()) {
                Thread.sleep(durationRemaining);
            }

            // Stop and wait for pending results
            stop();
            producerExecutor.shutdown();
            producerExecutor.awaitTermination(5, TimeUnit.SECONDS);

            // Wait a bit for remaining results
            Thread.sleep(2000);
            consumerThread.interrupt();

            // Mark remaining pending as failed
            for (var entry : pendingRequests.entrySet()) {
                long latencyMs = System.currentTimeMillis() - entry.getValue();
                recordLatency(latencyMs);
                recordFailure();
            }

        } finally {
            throughputSampler.interrupt();
            producerExecutor.shutdownNow();
            producer.close();
            consumer.close();
        }
    }

    private void runProducerWorker(int workerId, KafkaProducer<String, String> producer,
                                   Map<String, Long> pendingRequests, Config config, Instant warmupEnd) {
        long counter = 0;
        while (isRunning()) {
            long start = System.currentTimeMillis();
            String eventId = "kafka_" + workerId + "_" + (counter++);
            String requestId = "kafka_req_" + workerId + "_" + start;

            try {
                // Build message matching Gateway's CounterCommand format exactly
                ObjectNode timing = mapper.createObjectNode();
                timing.put("gatewayReceivedAt", start);
                timing.put("gatewayPublishedAt", start);

                ObjectNode command = mapper.createObjectNode();
                command.put("requestId", requestId);
                command.putNull("customerId");
                command.put("eventId", eventId);
                command.put("sessionId", "kafka-bench-" + workerId);
                command.put("action", "increment");  // lowercase as Gateway normalizes
                command.put("value", 1);
                command.put("timestamp", start);
                command.set("timing", timing);
                command.put("source", "kafka-benchmark");

                String kafkaKey = "kafka-bench-" + workerId;
                pendingRequests.put(eventId, start);

                producer.send(new ProducerRecord<>(EVENTS_TOPIC, kafkaKey, command.toString()),
                        (metadata, exception) -> {
                            if (exception != null) {
                                pendingRequests.remove(eventId);
                                recordLatency(System.currentTimeMillis() - start);
                                recordFailure();
                                addSampleEvent(SampleEvent.error("kafka_" + eventId, null, null,
                                        System.currentTimeMillis() - start, exception.getMessage()));
                            }
                        });

            } catch (Exception e) {
                pendingRequests.remove(eventId);
                recordLatency(System.currentTimeMillis() - start);
                recordFailure();
            }

            // Small delay to avoid overwhelming
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private String getKafkaUrl(Config config) {
        // For Docker Compose, use kafka:29092 (internal)
        // For local, use localhost:9092
        String url = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        // Default to Docker Compose internal network
        return "kafka:29092";
    }

    // ========================================================================
    // CLI Entry Point
    // ========================================================================

    public static void main(String[] args) throws IOException {
        if (args.length < 6) {
            System.err.println("Usage: KafkaBenchmark <durationMs> <concurrency> <gatewayUrl> <droolsUrl> <reportsDir> <skipEnrichment>");
            System.exit(1);
        }

        long durationMs = Long.parseLong(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        String gatewayUrl = args[2];
        String droolsUrl = args[3];
        String reportsDir = args[4];
        boolean skipEnrichment = Boolean.parseBoolean(args[5]);

        log.info("Starting Kafka Benchmark: duration={}ms, concurrency={}", durationMs, concurrency);

        Config config = Config.builder()
                .durationMs(durationMs)
                .concurrency(concurrency)
                .gatewayUrl(gatewayUrl)
                .droolsUrl(droolsUrl)
                .skipEnrichment(skipEnrichment)
                .build();

        KafkaBenchmark benchmark = create();
        BenchmarkResult result = benchmark.run(config);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Path resultsPath = Path.of(reportsDir, "results.json");
        Files.createDirectories(resultsPath.getParent());
        Files.writeString(resultsPath, json);

        log.info("Benchmark complete: {} ops, {} success, {} failed",
                result.totalOperations(), result.successfulOperations(), result.failedOperations());
    }
}
