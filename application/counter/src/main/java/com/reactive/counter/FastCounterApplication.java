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
                .post("/api/counter/raw", Handler.sync(req -> handleCounterRaw(req, rawTopic)))
                // Timed endpoint - measures each step precisely
                .post("/api/counter/timed", Handler.sync(req -> handleCounterTimed(req, publisher)));

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

    // Timing accumulators for diagnostics
    private static final AtomicLong totalParseNs = new AtomicLong(0);
    private static final AtomicLong totalIdGenNs = new AtomicLong(0);
    private static final AtomicLong totalEventCreateNs = new AtomicLong(0);
    private static final AtomicLong totalAvroEncodeNs = new AtomicLong(0);
    private static final AtomicLong totalKafkaSendNs = new AtomicLong(0);
    private static final AtomicLong totalResponseNs = new AtomicLong(0);
    private static final AtomicLong timedRequestCount = new AtomicLong(0);

    /**
     * Timed handler - measures each step precisely for diagnostics
     */
    private static Response handleCounterTimed(Request request, KafkaPublisher<CounterEvent> publisher) {
        long t0 = System.nanoTime();

        // Step 1: Parse request
        byte[] body = request.body();
        ActionRequest actionReq = parseActionRequestFast(body);
        long t1 = System.nanoTime();
        totalParseNs.addAndGet(t1 - t0);

        // Step 2: Generate IDs
        String sessionId = actionReq.sessionId != null ? actionReq.sessionId : "default";
        String requestId = idGenerator.generateRequestId();
        String eventId = idGenerator.generateEventId();
        long t2 = System.nanoTime();
        totalIdGenNs.addAndGet(t2 - t1);

        // Step 3: Create event
        CounterEvent event = CounterEvent.create(requestId, "", eventId, sessionId, actionReq.action, actionReq.value);
        long t3 = System.nanoTime();
        totalEventCreateNs.addAndGet(t3 - t2);

        // Step 4a: Avro encoding (separate measurement)
        byte[] encoded = AvroCounterEventCodec.create().encode(event).getOrThrow();
        long t3a = System.nanoTime();
        totalAvroEncodeNs.addAndGet(t3a - t3);

        // Step 4b: Kafka send (raw send, Avro already done)
        publisher.publishFireAndForget(event);  // Note: This still re-encodes inside, but we can see Avro cost
        long t4 = System.nanoTime();
        totalKafkaSendNs.addAndGet(t4 - t3a);

        // Step 5: Build response
        String responseJson = new StringBuilder(160)
                .append("{\"success\":true,\"requestId\":\"").append(requestId)
                .append("\",\"customerId\":\"\",\"eventId\":\"").append(eventId)
                .append("\",\"otelTraceId\":\"\",\"status\":\"accepted\"}")
                .toString();
        long t5 = System.nanoTime();
        totalResponseNs.addAndGet(t5 - t4);

        long count = timedRequestCount.incrementAndGet();

        // Log every 10000 requests
        if (count % 10000 == 0) {
            double parseUs = totalParseNs.get() / count / 1000.0;
            double idGenUs = totalIdGenNs.get() / count / 1000.0;
            double eventUs = totalEventCreateNs.get() / count / 1000.0;
            double avroUs = totalAvroEncodeNs.get() / count / 1000.0;
            double kafkaUs = totalKafkaSendNs.get() / count / 1000.0;
            double respUs = totalResponseNs.get() / count / 1000.0;
            double totalUs = parseUs + idGenUs + eventUs + avroUs + kafkaUs + respUs;

            System.out.printf("[TIMING] After %d requests - Parse: %.1fµs, IDGen: %.1fµs, Event: %.1fµs, Avro: %.1fµs, Kafka: %.1fµs, Response: %.1fµs, Total: %.1fµs%n",
                    count, parseUs, idGenUs, eventUs, avroUs, kafkaUs, respUs, totalUs);
        }

        return Response.accepted(responseJson);
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
