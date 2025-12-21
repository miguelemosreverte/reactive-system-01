package com.reactive.platform.benchmark;

import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.kafka.KafkaSubscriber;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.JsonCodec;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka benchmark using Testcontainers.
 *
 * Tests publish/consume round-trip latency and throughput.
 *
 * Run with: mvn test -Dtest=KafkaBenchmark
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KafkaBenchmark {

    private static final String TOPIC = "benchmark-events";
    private static final int WARMUP_COUNT = 100;
    private static final int BENCHMARK_COUNT = 1000;

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    // Test event
    public record TestEvent(
            String eventId,
            String sessionId,
            String action,
            int value,
            long timestamp
    ) {}

    private Codec<TestEvent> codec;
    private Tracer tracer;

    @BeforeEach
    void setup() {
        codec = JsonCodec.forClass(TestEvent.class);
        tracer = GlobalOpenTelemetry.getTracer("kafka-benchmark");
    }

    @Test
    @Order(1)
    @DisplayName("Kafka Publish Throughput")
    void benchmarkPublishThroughput() throws Exception {
        KafkaPublisher<TestEvent> publisher = KafkaPublisher.<TestEvent>builder()
                .bootstrapServers(kafka.getBootstrapServers())
                .topic(TOPIC)
                .codec(codec)
                .keyExtractor(TestEvent::sessionId)
                .tracer(tracer)
                .fireAndForget()
                .build();

        try {
            // Warmup
            for (int i = 0; i < WARMUP_COUNT; i++) {
                publisher.publishFireAndForget(createEvent(i));
            }

            // Benchmark
            Instant start = Instant.now();

            for (int i = 0; i < BENCHMARK_COUNT; i++) {
                publisher.publishFireAndForget(createEvent(i));
            }

            Duration elapsed = Duration.between(start, Instant.now());
            double throughput = BENCHMARK_COUNT / (elapsed.toMillis() / 1000.0);

            System.out.println("=== Kafka Publish Benchmark ===");
            System.out.printf("Events: %d%n", BENCHMARK_COUNT);
            System.out.printf("Duration: %d ms%n", elapsed.toMillis());
            System.out.printf("Throughput: %.2f events/sec%n", throughput);

            assertTrue(throughput > 1000, "Expected > 1000 events/sec");

        } finally {
            publisher.close();
        }
    }

    @Test
    @Order(2)
    @DisplayName("Kafka Round-Trip Latency")
    void benchmarkRoundTripLatency() throws Exception {
        List<Long> latencies = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(BENCHMARK_COUNT);
        AtomicLong receiveCount = new AtomicLong(0);

        // Subscriber
        KafkaSubscriber<TestEvent> subscriber = KafkaSubscriber.<TestEvent>builder()
                .bootstrapServers(kafka.getBootstrapServers())
                .topic(TOPIC + "-rt")
                .groupId("benchmark-group")
                .codec(codec)
                .tracer(tracer)
                .build();

        subscriber.subscribe(event -> {
            long latency = System.currentTimeMillis() - event.timestamp();
            latencies.add(latency);
            receiveCount.incrementAndGet();
            latch.countDown();
        });

        // Wait for consumer to be ready
        Thread.sleep(1000);

        // Publisher
        KafkaPublisher<TestEvent> publisher = KafkaPublisher.<TestEvent>builder()
                .bootstrapServers(kafka.getBootstrapServers())
                .topic(TOPIC + "-rt")
                .codec(codec)
                .keyExtractor(TestEvent::sessionId)
                .tracer(tracer)
                .acks("1")
                .build();

        try {
            Instant start = Instant.now();

            // Publish all events
            for (int i = 0; i < BENCHMARK_COUNT; i++) {
                publisher.publishFireAndForget(createEvent(i));
            }

            // Wait for all to be consumed
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            Duration elapsed = Duration.between(start, Instant.now());

            if (!completed) {
                System.out.printf("Warning: Only received %d of %d events%n",
                        receiveCount.get(), BENCHMARK_COUNT);
            }

            // Calculate stats
            BenchmarkResult.LatencyStats stats = BenchmarkResult.LatencyStats.calculate(latencies);

            System.out.println("=== Kafka Round-Trip Benchmark ===");
            System.out.printf("Events: %d (received: %d)%n", BENCHMARK_COUNT, receiveCount.get());
            System.out.printf("Duration: %d ms%n", elapsed.toMillis());
            System.out.printf("Latency Min: %.2f ms%n", stats.min());
            System.out.printf("Latency Max: %.2f ms%n", stats.max());
            System.out.printf("Latency Avg: %.2f ms%n", stats.avg());
            System.out.printf("Latency P50: %.2f ms%n", stats.p50());
            System.out.printf("Latency P95: %.2f ms%n", stats.p95());
            System.out.printf("Latency P99: %.2f ms%n", stats.p99());

            assertTrue(stats.p95() < 100, "Expected P95 latency < 100ms");

        } finally {
            publisher.close();
            subscriber.close();
        }
    }

    private TestEvent createEvent(int index) {
        return new TestEvent(
                "evt-" + index,
                "session-" + (index % 8),
                "increment",
                1,
                System.currentTimeMillis()
        );
    }
}
