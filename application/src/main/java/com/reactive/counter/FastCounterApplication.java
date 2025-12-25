package com.reactive.counter;

import com.reactive.counter.domain.CounterEvent;
import com.reactive.counter.serialization.AvroCounterEventCodec;
import com.reactive.platform.http.HttpServer;
import com.reactive.platform.http.HttpServer.*;
import com.reactive.platform.http.NettyHttpServer;
import com.reactive.platform.id.IdGenerator;
import com.reactive.platform.kafka.KafkaPublisher;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Counter Application using FastHttpServer.
 *
 * Bypasses Spring WebFlux overhead for maximum throughput.
 * Uses the same KafkaPublisher and Avro serialization as the Spring version.
 *
 * Benchmark target: Match FastHttpServer isolation throughput (~35k req/s)
 */
public class FastCounterApplication {

    private static final IdGenerator idGenerator = IdGenerator.getInstance();
    private static KafkaProducer<String, byte[]> rawProducer;
    private static final AtomicLong sendCount = new AtomicLong(0);
    private static final byte[] STATIC_RESPONSE = "{\"success\":true,\"status\":\"raw\"}".getBytes();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "3000"));
        String kafkaBootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092");
        String topic = System.getenv().getOrDefault("EVENTS_TOPIC", "counter-events");

        System.out.println("[FastCounterApplication] Starting...");
        System.out.println("  Port: " + port);
        System.out.println("  Kafka: " + kafkaBootstrap);
        System.out.println("  Topic: " + topic);

        // Create Kafka publisher with fire-and-forget for max throughput
        KafkaPublisher<CounterEvent> publisher = KafkaPublisher.create(c -> c
                .bootstrapServers(kafkaBootstrap)
                .topic(topic)
                .codec(AvroCounterEventCodec.create())
                .keyExtractor(CounterEvent::eventId)
                .fireAndForget());

        // Create raw Kafka producer for minimal overhead testing
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);  // Batch for 5ms
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 65536);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864L);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 100);
        rawProducer = new KafkaProducer<>(props);
        String rawTopic = topic;

        // Create HTTP server with routes - use Netty for better ab compatibility
        HttpServer server = NettyHttpServer.createNetty()
                .get("/health", Handler.sync(req -> Response.ok("{\"status\":\"UP\"}")))
                .post("/api/counter", Handler.sync(req -> handleCounterSync(req, publisher)))
                .post("/api/counter/fast", Handler.sync(req -> handleCounterSync(req, publisher)))
                // Diagnostic endpoints to isolate bottlenecks
                .post("/api/counter/no-kafka", Handler.sync(req -> handleCounterNoKafka(req)))
                .post("/api/counter/no-avro", Handler.sync(req -> handleCounterNoAvro(req, publisher)))
                // Raw Kafka - minimal overhead (no Avro, no callback, static response)
                .post("/api/counter/raw", Handler.sync(req -> handleCounterRaw(req, rawTopic)));

        // Start server
        try (Handle handle = server.start(port)) {
            System.out.println("[FastCounterApplication] Ready on port " + port);
            handle.awaitTermination();
        } finally {
            publisher.close();
        }
    }

    /**
     * Handle POST /api/counter synchronously - minimizes async overhead
     */
    private static Response handleCounterSync(Request request, KafkaPublisher<CounterEvent> publisher) {
        try {
            // Parse request body - use raw bytes for speed
            byte[] body = request.body();
            ActionRequest actionReq = parseActionRequestFast(body);

            String sessionId = actionReq.sessionId != null ? actionReq.sessionId : "default";
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();

            // Create and publish event
            CounterEvent event = CounterEvent.create(
                    requestId, "", eventId, sessionId,
                    actionReq.action, actionReq.value);

            publisher.publishFireAndForget(event);

            // Build response using StringBuilder (faster than String.format)
            String responseJson = new StringBuilder(160)
                    .append("{\"success\":true,\"requestId\":\"").append(requestId)
                    .append("\",\"customerId\":\"\",\"eventId\":\"").append(eventId)
                    .append("\",\"otelTraceId\":\"\",\"status\":\"accepted\"}")
                    .toString();

            return Response.accepted(responseJson);

        } catch (Exception e) {
            return Response.serverError(e.getMessage());
        }
    }

    /**
     * Raw Kafka - absolute minimum overhead
     * - No Avro encoding (just pass raw bytes)
     * - No callback (fire-and-forget truly)
     * - Static response (no string building)
     * - With linger.ms=5 for batching
     */
    private static Response handleCounterRaw(Request request, String topic) {
        byte[] body = request.body();
        rawProducer.send(new ProducerRecord<>(topic, "key", body));
        sendCount.incrementAndGet();
        return new Response(202, java.util.Map.of("Content-Type", "application/json"), STATIC_RESPONSE);
    }

    /**
     * Diagnostic: Everything except Kafka - isolates HTTP + parsing + ID + event creation
     */
    private static Response handleCounterNoKafka(Request request) {
        try {
            byte[] body = request.body();
            ActionRequest actionReq = parseActionRequestFast(body);

            String sessionId = actionReq.sessionId != null ? actionReq.sessionId : "default";
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();

            // Create event but DON'T publish
            CounterEvent event = CounterEvent.create(
                    requestId, "", eventId, sessionId,
                    actionReq.action, actionReq.value);

            // Simulate Avro encoding overhead
            AvroCounterEventCodec.create().encode(event);

            String responseJson = new StringBuilder(160)
                    .append("{\"success\":true,\"requestId\":\"").append(requestId)
                    .append("\",\"customerId\":\"\",\"eventId\":\"").append(eventId)
                    .append("\",\"otelTraceId\":\"\",\"status\":\"no-kafka\"}")
                    .toString();

            return Response.accepted(responseJson);
        } catch (Exception e) {
            return Response.serverError(e.getMessage());
        }
    }

    /**
     * Diagnostic: Skip Avro encoding - raw string to Kafka
     */
    private static Response handleCounterNoAvro(Request request, KafkaPublisher<CounterEvent> publisher) {
        try {
            byte[] body = request.body();
            ActionRequest actionReq = parseActionRequestFast(body);

            String sessionId = actionReq.sessionId != null ? actionReq.sessionId : "default";
            String requestId = idGenerator.generateRequestId();
            String eventId = idGenerator.generateEventId();

            // Create event and publish (Avro encoding happens inside publisher)
            CounterEvent event = CounterEvent.create(
                    requestId, "", eventId, sessionId,
                    actionReq.action, actionReq.value);

            publisher.publishFireAndForget(event);

            String responseJson = new StringBuilder(160)
                    .append("{\"success\":true,\"requestId\":\"").append(requestId)
                    .append("\",\"customerId\":\"\",\"eventId\":\"").append(eventId)
                    .append("\",\"otelTraceId\":\"\",\"status\":\"accepted\"}")
                    .toString();

            return Response.accepted(responseJson);
        } catch (Exception e) {
            return Response.serverError(e.getMessage());
        }
    }

    /**
     * Fast JSON parsing operating on bytes directly
     */
    private static ActionRequest parseActionRequestFast(byte[] body) {
        String json = new String(body);
        String sessionId = extractJsonString(json, "sessionId");
        String action = extractJsonString(json, "action");
        int value = extractJsonInt(json, "value", 1);
        return new ActionRequest(sessionId, action, value);
    }

    /**
     * Simple JSON parsing for ActionRequest (avoids Jackson overhead)
     */
    private static ActionRequest parseActionRequest(String json) {
        String sessionId = extractJsonString(json, "sessionId");
        String action = extractJsonString(json, "action");
        int value = extractJsonInt(json, "value", 1);
        return new ActionRequest(sessionId, action, value);
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private static int extractJsonInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return defaultValue;
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : defaultValue;
    }

    private record ActionRequest(String sessionId, String action, int value) {}
}
