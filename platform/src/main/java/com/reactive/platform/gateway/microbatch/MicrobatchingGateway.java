package com.reactive.platform.gateway.microbatch;

import com.reactive.platform.base.Result;
import com.reactive.platform.http.HttpServer;
import com.reactive.platform.http.HttpServer.Handle;
import com.reactive.platform.http.HttpServer.Handler;
import com.reactive.platform.http.HttpServer.Request;
import com.reactive.platform.http.HttpServer.Response;
import com.reactive.platform.http.RocketHttpServer;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.serialization.Codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * High-performance gateway with adaptive microbatching.
 *
 * Combines:
 * - RocketHttpServer for maximum HTTP throughput (600K+ req/s)
 * - Adaptive microbatching for optimal Kafka throughput
 * - SQLite-backed calibration for persistence across restarts
 * - Rich observability metrics
 *
 * The gateway dynamically finds the optimal batch size and flush interval
 * to maximize throughput while respecting latency constraints.
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                        MicrobatchingGateway                              │
 * │                                                                          │
 * │  HTTP Requests ──┬──► MicrobatchCollector ──► Kafka Producer Batch Send  │
 * │                  │         ▲                                             │
 * │                  │    Calibration                                        │
 * │                  │         │                                             │
 * │                  └──► SQLite (persist optimal config)                    │
 * │                                                                          │
 * │  Metrics ──────────────────────────────────────────────────────────────► │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
public final class MicrobatchingGateway<E> implements AutoCloseable {

    // Pre-computed responses for zero-alloc hot path
    private static final byte[] RESPONSE_202 = (
        "HTTP/1.1 202 Accepted\r\n" +
        "Content-Type: application/json\r\n" +
        "Content-Length: 11\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n" +
        "{\"ok\":true}"
    ).getBytes(StandardCharsets.UTF_8);

    private final RocketHttpServer.Handle serverHandle;
    private final KafkaPublisher<E> publisher;
    private final MicrobatchCollector<E> collector;
    private final BatchCalibration calibration;
    private final Function<ByteBuffer, E> eventFactory;

    // Metrics
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final long startTimeNanos = System.nanoTime();

    private MicrobatchingGateway(
        RocketHttpServer.Handle serverHandle,
        KafkaPublisher<E> publisher,
        MicrobatchCollector<E> collector,
        BatchCalibration calibration,
        Function<ByteBuffer, E> eventFactory
    ) {
        this.serverHandle = serverHandle;
        this.publisher = publisher;
        this.collector = collector;
        this.calibration = calibration;
        this.eventFactory = eventFactory;
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    public static final class Builder<E> {
        private String kafkaBootstrap = "localhost:9092";
        private String topic = "events";
        private Codec<E> codec;
        private Function<ByteBuffer, E> eventFactory;
        private int port = 8080;
        private int reactors = Runtime.getRuntime().availableProcessors();
        private Path calibrationPath;
        private double targetLatencyMicros = 5000.0; // 5ms

        public Builder<E> kafka(String bootstrap, String topic) {
            this.kafkaBootstrap = bootstrap;
            this.topic = topic;
            return this;
        }

        public Builder<E> codec(Codec<E> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<E> eventFactory(Function<ByteBuffer, E> factory) {
            this.eventFactory = factory;
            return this;
        }

        public Builder<E> port(int port) {
            this.port = port;
            return this;
        }

        public Builder<E> reactors(int count) {
            this.reactors = count;
            return this;
        }

        public Builder<E> calibrationPath(Path path) {
            this.calibrationPath = path;
            return this;
        }

        public Builder<E> targetLatency(double micros) {
            this.targetLatencyMicros = micros;
            return this;
        }

        public MicrobatchingGateway<E> build() {
            if (codec == null) throw new IllegalStateException("Codec required");
            if (eventFactory == null) throw new IllegalStateException("Event factory required");

            // Create calibration store
            Path calPath = calibrationPath != null
                ? calibrationPath
                : Path.of(System.getProperty("user.home"), ".reactive", "calibration.db");
            BatchCalibration calibration = BatchCalibration.create(calPath, targetLatencyMicros);

            // Create Kafka publisher (batch mode with larger batches)
            KafkaPublisher<E> publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(topic)
                .codec(codec)
                .keyExtractor(e -> "")  // Single partition for ordering
                .acks("0")              // Fire-and-forget for max throughput
                .batchSize(262144)      // 256KB batches for batch messages
                .lingerMs(5)            // Small linger for batch accumulation
                .compression("lz4"));   // Compress batches efficiently

            // Create microbatch collector that sends batches to Kafka as SINGLE MESSAGE
            // This is the key optimization: N items → 1 Kafka send
            MicrobatchCollector<E> collector = MicrobatchCollector.create(
                batch -> publisher.publishBatchFireAndForget(batch),
                calibration
            );

            // Track reference for use in handler
            final MicrobatchCollector<E> collectorRef = collector;
            final Function<ByteBuffer, E> factoryRef = eventFactory;
            final AtomicLong reqCount = new AtomicLong(0);

            // Create HTTP server with body handler
            RocketHttpServer server = RocketHttpServer.create()
                .reactors(reactors)
                .onBody(body -> {
                    reqCount.incrementAndGet();
                    E event = factoryRef.apply(body);
                    collectorRef.submitFireAndForget(event);
                });

            RocketHttpServer.Handle handle = server.start(port);

            MicrobatchingGateway<E> gateway = new MicrobatchingGateway<>(
                handle, publisher, collector, calibration, eventFactory
            );

            // Print startup info
            printStartupInfo(port, reactors, calibration.getBestConfig());

            return gateway;
        }

        private void printStartupInfo(int port, int reactors, BatchCalibration.Config config) {
            System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║               MICROBATCHING GATEWAY - Adaptive High Throughput               ║");
            System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.printf("  HTTP Server:    RocketHttpServer (%d reactors)%n", reactors);
            System.out.printf("  Port:           %d%n", port);
            System.out.printf("  Batch Size:     %d (initial, will adapt)%n", config.batchSize());
            System.out.printf("  Flush Interval: %d µs (initial, will adapt)%n", config.flushIntervalMicros());
            System.out.println();
            System.out.println("  Calibration: SQLite-backed, persists across restarts");
            System.out.println("  Target:      Automatic optimization for max throughput");
            System.out.println();
        }
    }

    // ========================================================================
    // Observability
    // ========================================================================

    /**
     * Gateway metrics snapshot.
     */
    public record GatewayMetrics(
        long totalRequests,
        long errorCount,
        double uptimeSeconds,
        double requestsPerSecond,
        MicrobatchCollector.Metrics collectorMetrics,
        BatchCalibration.Config currentConfig,
        BatchCalibration.CalibrationStats calibrationStats
    ) {}

    /**
     * Get comprehensive metrics.
     */
    public GatewayMetrics getMetrics() {
        long requests = requestCount.get();
        double uptimeSec = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;

        return new GatewayMetrics(
            requests,
            errorCount.get(),
            uptimeSec,
            uptimeSec > 0 ? requests / uptimeSec : 0,
            collector.getMetrics(),
            calibration.getBestConfig(),
            calibration.getStats()
        );
    }

    /**
     * Get metrics as JSON string (for health endpoint).
     */
    public String getMetricsJson() {
        GatewayMetrics m = getMetrics();
        MicrobatchCollector.Metrics c = m.collectorMetrics();
        BatchCalibration.Config cfg = m.currentConfig();

        return String.format("""
            {
              "status": "UP",
              "uptime_seconds": %.2f,
              "total_requests": %d,
              "requests_per_second": %.2f,
              "errors": %d,
              "microbatch": {
                "current_batch_size": %d,
                "current_flush_interval_micros": %d,
                "avg_batch_size": %.2f,
                "avg_flush_time_micros": %.2f,
                "throughput_per_second": %.2f
              },
              "calibration": {
                "best_batch_size": %d,
                "best_flush_interval_micros": %d,
                "best_score": %.4f,
                "best_throughput": %d,
                "total_observations": %d
              }
            }""",
            m.uptimeSeconds(),
            m.totalRequests(),
            m.requestsPerSecond(),
            m.errorCount(),
            c.currentBatchSize(),
            c.currentFlushIntervalMicros(),
            c.avgBatchSize(),
            c.avgFlushTimeMicros(),
            c.throughputPerSec(),
            cfg.batchSize(),
            cfg.flushIntervalMicros(),
            cfg.score(),
            cfg.throughputPerSec(),
            m.calibrationStats().totalObservations()
        );
    }

    /**
     * Await termination.
     */
    public void awaitTermination() throws InterruptedException {
        serverHandle.awaitTermination();
    }

    @Override
    public void close() {
        collector.close();
        serverHandle.close();
        publisher.close();
        calibration.close();
    }

    // ========================================================================
    // Main - Standalone execution
    // ========================================================================

    public static void main(String[] args) {
        String kafkaBootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = env("KAFKA_TOPIC", "events");
        int port = Integer.parseInt(env("PORT", "8080"));
        int reactors = Integer.parseInt(env("REACTORS",
            String.valueOf(Runtime.getRuntime().availableProcessors())));

        // Simple byte array codec for raw throughput
        Codec<byte[]> byteCodec = Codec.of(
            bytes -> com.reactive.platform.base.Result.success(bytes),
            bytes -> com.reactive.platform.base.Result.success(bytes),
            "application/octet-stream",
            "bytes"
        );

        MicrobatchingGateway<byte[]> gateway = MicrobatchingGateway.<byte[]>builder()
            .kafka(kafkaBootstrap, topic)
            .codec(byteCodec)
            .eventFactory(MicrobatchingGateway::bufferToBytes)
            .port(port)
            .reactors(reactors)
            .targetLatency(5000.0) // 5ms target
            .build();

        // Print metrics periodically
        Thread metricsThread = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (Result.sleep(5000).isFailure()) break;
                GatewayMetrics m = gateway.getMetrics();
                System.out.printf(
                    "[Metrics] Requests: %,d | Throughput: %.0f req/s | " +
                    "Batch: %d | Flush: %d µs%n",
                    m.totalRequests(),
                    m.requestsPerSecond(),
                    m.collectorMetrics().currentBatchSize(),
                    m.collectorMetrics().currentFlushIntervalMicros()
                );
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            metricsThread.interrupt();
            gateway.close();
            System.out.println("Gateway stopped.");
        }));

        Result.run(() -> gateway.awaitTermination());
    }

    private static byte[] bufferToBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}
