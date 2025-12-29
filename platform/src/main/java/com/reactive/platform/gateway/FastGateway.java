package com.reactive.platform.gateway;

import com.reactive.platform.http.HttpServer;
import com.reactive.platform.http.HttpServer.Handle;
import com.reactive.platform.http.HttpServer.Handler;
import com.reactive.platform.http.HttpServer.Request;
import com.reactive.platform.http.HttpServer.Response;
import com.reactive.platform.http.Json;
import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.base.Result;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * High-performance gateway combining FastHttpServer + KafkaPublisher.
 *
 * Design principles:
 * - Functional composition
 * - Immutable request/response flow
 * - Fire-and-forget publishing for max throughput
 * - Zero framework overhead
 *
 * Target: Match Kafka throughput (~1M events/s)
 */
public final class FastGateway<E> {

    private final HttpServer server;
    private final KafkaPublisher<E> publisher;
    private final AtomicLong requestCounter = new AtomicLong(0);
    private final AtomicLong eventCounter = new AtomicLong(0);

    private FastGateway(HttpServer server, KafkaPublisher<E> publisher) {
        this.server = server;
        this.publisher = publisher;
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
        private Function<E, String> keyExtractor = e -> "";
        private Function<Map<String, Object>, E> eventFactory;
        private HttpServer.Config serverConfig = HttpServer.Config.defaults();

        public Builder<E> kafka(String bootstrap, String topic) {
            this.kafkaBootstrap = bootstrap;
            this.topic = topic;
            return this;
        }

        public Builder<E> codec(Codec<E> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<E> keyExtractor(Function<E, String> fn) {
            this.keyExtractor = fn;
            return this;
        }

        public Builder<E> eventFactory(Function<Map<String, Object>, E> factory) {
            this.eventFactory = factory;
            return this;
        }

        public Builder<E> serverConfig(HttpServer.Config config) {
            this.serverConfig = config;
            return this;
        }

        public FastGateway<E> build() {
            if (codec == null) throw new IllegalStateException("Codec required");
            if (eventFactory == null) throw new IllegalStateException("Event factory required");

            KafkaPublisher<E> publisher = KafkaPublisher.create(c -> c
                    .bootstrapServers(kafkaBootstrap)
                    .topic(topic)
                    .codec(codec)
                    .keyExtractor(keyExtractor)
                    .fireAndForget());

            HttpServer server = HttpServer.create(serverConfig);

            return new FastGateway<>(server, publisher);
        }
    }

    // ========================================================================
    // Route registration
    // ========================================================================

    /**
     * Register event ingestion endpoint.
     *
     * Receives JSON, transforms to event, publishes to Kafka, returns 202.
     */
    public FastGateway<E> ingest(String path, Function<Map<String, Object>, E> transformer) {
        server.post(path, Handler.sync(req -> {
            requestCounter.incrementAndGet();

            // Parse JSON body
            Map<String, Object> json = Json.parse(req.body());

            // Transform to event
            E event = transformer.apply(json);

            // Fire-and-forget publish
            publisher.publishFireAndForget(event);
            eventCounter.incrementAndGet();

            // Return accepted
            return Response.accepted(Json.stringify(
                    "accepted", true,
                    "timestamp", System.currentTimeMillis()
            ));
        }));
        return this;
    }

    /**
     * Register health endpoint.
     */
    public FastGateway<E> health(String path) {
        server.get(path, Handler.sync(req ->
                Response.ok(Json.stringify(
                        "status", "UP",
                        "requests", requestCounter.get(),
                        "events", eventCounter.get()
                ))
        ));
        return this;
    }

    /**
     * Register custom GET endpoint.
     */
    public FastGateway<E> get(String path, Function<Request, Response> handler) {
        server.get(path, Handler.sync(handler));
        return this;
    }

    /**
     * Register custom POST endpoint.
     */
    public FastGateway<E> post(String path, Function<Request, Response> handler) {
        server.post(path, Handler.sync(handler));
        return this;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Start the gateway on the specified port.
     */
    public GatewayHandle start(int port) {
        Handle serverHandle = server.start(port);
        return new GatewayHandle(serverHandle, publisher);
    }

    /**
     * Gateway handle for lifecycle and metrics.
     */
    public record GatewayHandle(Handle server, KafkaPublisher<?> publisher) implements AutoCloseable {
        public void awaitTermination() throws InterruptedException {
            server.awaitTermination();
        }

        @Override
        public void close() {
            server.close();
            publisher.close();
        }
    }

    // ========================================================================
    // Metrics
    // ========================================================================

    public long requestCount() {
        return requestCounter.get();
    }

    public long eventCount() {
        return eventCounter.get();
    }

    // ========================================================================
    // Main entry point for standalone mode
    // ========================================================================

    public static void main(String[] args) {
        String kafkaBootstrap = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = env("KAFKA_TOPIC", "events");
        int port = Integer.parseInt(env("PORT", "8080"));

        // Simple string codec for demo
        Codec<String> stringCodec = Codec.of(
                s -> Result.success(s.getBytes(StandardCharsets.UTF_8)),
                b -> Result.success(new String(b, StandardCharsets.UTF_8)),
                "text/plain",
                "string"
        );

        FastGateway<String> gateway = FastGateway.<String>builder()
                .kafka(kafkaBootstrap, topic)
                .codec(stringCodec)
                .eventFactory(json -> Json.stringify(json.keySet().toArray()))
                .build();

        gateway
                .health("/health")
                .ingest("/events", json -> Json.stringify(json.keySet().toArray()));

        System.out.println("Starting FastGateway on port " + port);
        System.out.println("Kafka: " + kafkaBootstrap + " / " + topic);

        try (GatewayHandle handle = gateway.start(port)) {
            Result.run(() -> handle.awaitTermination());
        }
    }

    private static String env(String key, String defaultValue) {
        return Optional.ofNullable(System.getenv(key)).orElse(defaultValue);
    }
}
