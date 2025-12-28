package com.reactive.counter.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reactive.platform.benchmark.BaseBenchmark;
import com.reactive.platform.benchmark.BenchmarkResult;
import com.reactive.platform.benchmark.BenchmarkTypes.*;
import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.serialization.AvroCounterEventCodec;
import com.reactive.platform.serialization.Codec;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
 * Flink Benchmark - tests stream processing throughput.
 *
 * Produces messages to Kafka and measures time until Flink produces results.
 * This tests the Flink job's processing capacity under load.
 *
 * Unlike KafkaBenchmark which measures Kafka round-trip, this focuses on
 * Flink's processing throughput by running with higher concurrency.
 */
public class FlinkBenchmark extends BaseBenchmark {

    private static final Logger log = LoggerFactory.getLogger(FlinkBenchmark.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String EVENTS_TOPIC = "counter-events";  // Gateway sends here
    private static final String RESULTS_TOPIC = "counter-results";

    // Avro codec for CounterEvent serialization - same as gateway uses
    private static final Codec<CounterEvent> avroCodec = AvroCounterEventCodec.create();

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

    // Maximum in-flight messages to prevent memory exhaustion
    // This provides backpressure when Flink is slower (e.g., with RocksDB checkpointing)
    // 75K balances throughput (~15-20K ops/s) with reasonable drain time (~5s)
    private static final int MAX_IN_FLIGHT = 75_000;

    @Override
    protected void runBenchmarkLoop(Config config) throws Exception {
        String kafkaUrl = getKafkaUrl();
        // Will be reset after probe phase
        Instant[] benchTiming = new Instant[] { Instant.now(), null };
        benchTiming[1] = benchTiming[0].plusMillis(config.warmupMs());

        // Track in-flight messages for latency calculation
        // Limited to MAX_IN_FLIGHT to prevent OOM when Flink is slower than producers
        Map<String, Long> inFlight = new ConcurrentHashMap<>();

        // Kafka producer - Avro binary, optimized for throughput
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        producerProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // Kafka consumer - optimized for high throughput
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "flink-benchmark-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // High throughput settings: fetch immediately, large batches
        consumerProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);  // Don't wait for data
        consumerProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 1);  // 1ms max wait
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10000);  // Large batches
        consumerProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);  // 10 min to avoid timeout

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerProps);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList(RESULTS_TOPIC));

        // Force partition assignment before starting producers
        // Keep polling until partitions are assigned (up to 10 seconds)
        int waitMs = 0;
        while (consumer.assignment().isEmpty() && waitMs < 10000) {
            consumer.poll(Duration.ofMillis(500));
            waitMs += 500;
        }
        log.info("Consumer subscribed to {} partitions after {}ms", consumer.assignment().size(), waitMs);

        // === CRITICAL: Wait for Flink to be actively consuming ===
        // Send probe messages until we receive a response, confirming Flink is ready
        log.info("Waiting for Flink to be ready (sending probe messages)...");
        String probeId = "probe_" + System.currentTimeMillis();
        boolean flinkReady = false;
        int probeAttempts = 0;
        int maxProbeAttempts = 60;  // Up to 30 seconds (500ms * 60)

        while (!flinkReady && probeAttempts < maxProbeAttempts) {
            // Send a probe message
            long probeTime = System.currentTimeMillis();
            String probeEventId = probeId + "_" + probeAttempts;
            CounterEvent.Timing timing = CounterEvent.Timing.complete(probeTime, probeTime);
            CounterEvent probeEvent = new CounterEvent(
                    "probe_req_" + probeAttempts,
                    "",
                    probeEventId,
                    "probe-session",
                    "increment",
                    1,
                    probeTime,
                    timing
            );
            byte[] probeBytes = avroCodec.encode(probeEvent).getOrThrow();
            producer.send(new ProducerRecord<>(EVENTS_TOPIC, "probe-session", probeBytes));
            producer.flush();

            // Poll for response
            ConsumerRecords<String, String> probeRecords = consumer.poll(Duration.ofMillis(500));
            if (!probeRecords.isEmpty()) {
                for (ConsumerRecord<String, String> record : probeRecords) {
                    String eventId = extractEventIdFast(record.value());
                    if (eventId != null && eventId.startsWith(probeId)) {
                        flinkReady = true;
                        log.info("Flink is ready! Received probe response after {} attempts ({}ms)",
                                probeAttempts + 1, (probeAttempts + 1) * 500);
                        break;
                    }
                }
            }
            probeAttempts++;
        }

        if (!flinkReady) {
            log.warn("Flink may not be ready after {}ms - proceeding anyway", probeAttempts * 500);
        }

        // === RESET TIMING after probe phase ===
        // The probe phase can take several seconds, so reset the clock for accurate measurement
        benchTiming[0] = Instant.now();
        benchTiming[1] = benchTiming[0].plusMillis(config.warmupMs());
        log.info("Timing reset after probe phase - actual benchmark starting now");

        // Use more workers for Flink benchmark to stress the stream processor
        int workerCount = Math.max(config.concurrency(), 16);
        ExecutorService producerExecutor = Executors.newFixedThreadPool(workerCount);
        AtomicBoolean warmupComplete = new AtomicBoolean(false);
        AtomicLong lastOpsCount = new AtomicLong(0);

        // Consumer threads - parallel processing for high throughput
        AtomicLong totalRecordsReceived = new AtomicLong(0);
        AtomicLong matchedRecords = new AtomicLong(0);
        AtomicLong pollCount = new AtomicLong(0);

        // Use a thread pool to process records in parallel
        int consumerThreads = Math.max(8, Runtime.getRuntime().availableProcessors());
        ExecutorService consumerExecutor = Executors.newFixedThreadPool(consumerThreads);

        Thread consumerThread = new Thread(() -> {
            log.info("Consumer thread started, polling from {} partitions with {} processing threads",
                    consumer.assignment().size(), consumerThreads);
            try {
                while (isRunning()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(50));
                    pollCount.incrementAndGet();
                    if (records.isEmpty()) continue;

                    long now = System.currentTimeMillis();
                    int batchSize = records.count();
                    totalRecordsReceived.addAndGet(batchSize);

                    // Process records in parallel using ForkJoinPool for better CPU utilization
                    for (ConsumerRecord<String, String> record : records) {
                        consumerExecutor.submit(() -> {
                            try {
                                // Fast eventId extraction using regex instead of full JSON parsing
                                String value = record.value();
                                String eventId = extractEventIdFast(value);

                                if (eventId != null) {
                                    Long startTime = inFlight.remove(eventId);
                                    if (startTime != null) {
                                        matchedRecords.incrementAndGet();
                                        long latencyMs = now - startTime;
                                        recordLatency(latencyMs);
                                        recordSuccess();

                                        // Sample events after warmup (every 1000th to reduce overhead)
                                        if (warmupComplete.get() && matchedRecords.get() % 1000 == 0) {
                                            ComponentTiming timing = new ComponentTiming(0, latencyMs, 0, 0);
                                            addSampleEvent(SampleEvent.success("flink_" + eventId, null, null, latencyMs)
                                                    .withTiming(timing));
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore parse errors
                            }
                        });
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
                        log.info("Progress: ops={}, throughput={}/s, in-flight={}, received={}, matched={}, polls={}",
                                currentOps, throughput, inFlight.size(),
                                totalRecordsReceived.get(), matchedRecords.get(), pollCount.get());
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
            long warmupRemaining = benchTiming[1].toEpochMilli() - System.currentTimeMillis();
            if (warmupRemaining > 0) {
                Thread.sleep(warmupRemaining);
            }
            warmupComplete.set(true);
            log.info("Warmup complete, starting measurements (workers={})", workerCount);

            // Wait for duration
            long durationRemaining = benchTiming[0].plusMillis(config.durationMs()).toEpochMilli() - System.currentTimeMillis();
            if (durationRemaining > 0 && isRunning()) {
                Thread.sleep(durationRemaining);
            }

            // Drain remaining results - wait for in-flight messages to complete
            // With 100K max in-flight and ~15K ops/s processing rate, need ~7s to drain
            stop();
            producerExecutor.shutdown();
            producerExecutor.awaitTermination(5, TimeUnit.SECONDS);

            // Extended drain period - wait until in-flight drops below threshold or timeout
            log.info("Draining {} in-flight messages...", inFlight.size());
            long drainStart = System.currentTimeMillis();
            long maxDrainMs = 10_000; // 10 second max drain time
            while (inFlight.size() > 1000 && (System.currentTimeMillis() - drainStart) < maxDrainMs) {
                Thread.sleep(500);
            }
            log.info("Drain complete: {} messages remaining after {}ms",
                    inFlight.size(), System.currentTimeMillis() - drainStart);
            consumerThread.interrupt();

            // Mark remaining in-flight as timeout
            for (var entry : inFlight.entrySet()) {
                recordLatency(System.currentTimeMillis() - entry.getValue());
                recordFailure();
            }

        } finally {
            throughputSampler.interrupt();
            producerExecutor.shutdownNow();
            consumerExecutor.shutdownNow();
            producer.close();
            consumer.close();
        }
    }

    private void runProducer(int workerId, KafkaProducer<String, byte[]> producer,
                             Map<String, Long> inFlight) {
        long counter = 0;
        while (isRunning()) {
            // Backpressure: if too many messages in-flight, wait for Flink to catch up
            // This prevents OOM when Flink is slower than producers (e.g., RocksDB checkpointing)
            while (inFlight.size() >= MAX_IN_FLIGHT && isRunning()) {
                try {
                    Thread.sleep(1); // Brief pause to let consumer catch up
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            long now = System.currentTimeMillis();
            String eventId = "flink_" + workerId + "_" + (counter++);
            String requestId = "flink_req_" + workerId + "_" + now;
            String sessionId = "flink-bench-" + workerId;

            try {
                // Create domain CounterEvent (same as gateway)
                CounterEvent.Timing timing = CounterEvent.Timing.complete(now, now);
                CounterEvent event = new CounterEvent(
                        requestId,
                        "",  // customerId
                        eventId,
                        sessionId,
                        "increment",
                        1,
                        now,
                        timing
                );

                // Serialize to Avro binary using the same codec as gateway
                byte[] avroBytes = avroCodec.encode(event).getOrThrow();

                inFlight.put(eventId, now);

                producer.send(new ProducerRecord<>(EVENTS_TOPIC, sessionId, avroBytes),
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

    /**
     * Fast eventId extraction using string parsing instead of full JSON parsing.
     * Looks for "eventId":"value" pattern in the JSON string.
     */
    private String extractEventIdFast(String json) {
        // Look for "eventId":" pattern
        int idx = json.indexOf("\"eventId\":\"");
        if (idx < 0) return null;

        int start = idx + 11; // Length of "eventId":"
        int end = json.indexOf('"', start);
        if (end < 0) return null;

        return json.substring(start, end);
    }

    // ========================================================================
    // CLI Entry Point
    // ========================================================================

    public static void main(String[] args) throws IOException {
        if (args.length < 6) {
            System.err.println("Usage: FlinkBenchmark <durationMs> <concurrency> <gatewayUrl> <droolsUrl> <reportsDir> <skipEnrichment>");
            System.exit(1);
        }

        long durationMs = Long.parseLong(args[0]);
        int concurrency = Integer.parseInt(args[1]);
        String gatewayUrl = args[2];
        String droolsUrl = args[3];
        String reportsDir = args[4];
        boolean skipEnrichment = Boolean.parseBoolean(args[5]);

        log.info("Starting Flink Benchmark: duration={}ms, concurrency={}", durationMs, concurrency);

        Config config = Config.builder()
                .durationMs(durationMs)
                .concurrency(concurrency)
                .gatewayUrl(gatewayUrl)
                .droolsUrl(droolsUrl)
                .skipEnrichment(skipEnrichment)
                .build();

        FlinkBenchmark benchmark = create();
        BenchmarkResult result = benchmark.run(config);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Path resultsPath = Path.of(reportsDir, "results.json");
        Files.createDirectories(resultsPath.getParent());
        Files.writeString(resultsPath, json);

        log.info("Benchmark complete: {} ops, {} success, {} failed",
                result.totalOperations(), result.successfulOperations(), result.failedOperations());
    }
}
