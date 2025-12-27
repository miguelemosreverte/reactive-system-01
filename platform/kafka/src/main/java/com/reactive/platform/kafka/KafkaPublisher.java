package com.reactive.platform.kafka;

import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.Result;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import static com.reactive.platform.observe.Log.error;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Functional Kafka publisher with OpenTelemetry tracing.
 *
 * @param <A> The message type
 */
public class KafkaPublisher<A> implements AutoCloseable {

    // ========================================================================
    // Static factories with lambda config (Scala-style named parameters)
    // ========================================================================

    /**
     * Create publisher with lambda configuration.
     * Example: KafkaPublisher.create(c -> c.bootstrapServers(s).topic(t).codec(c))
     */
    public static <A> KafkaPublisher<A> create(java.util.function.Consumer<Builder<A>> configure) {
        Builder<A> builder = new Builder<>();
        configure.accept(builder);
        return builder.build();
    }

    /** Create fire-and-forget publisher with lambda configuration. */
    public static <A> KafkaPublisher<A> fireAndForget(java.util.function.Consumer<Builder<A>> configure) {
        Builder<A> builder = new Builder<>();
        configure.accept(builder);
        return builder.fireAndForget().build();
    }

    // ========================================================================
    // Internal state
    // ========================================================================

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final Codec<A> codec;
    private final Function<A, String> keyExtractor;
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private KafkaPublisher(
            KafkaProducer<String, byte[]> producer,
            String topic,
            Codec<A> codec,
            Function<A, String> keyExtractor
    ) {
        this.producer = producer;
        this.topic = topic;
        this.codec = codec;
        this.keyExtractor = keyExtractor;
    }

    /**
     * Publish a message with full tracing.
     * Returns the trace ID on success.
     */
    public CompletableFuture<Result<String>> publish(A message) {
        // Create producer span using Log API (no OTel types leaked)
        SpanHandle span = Log.producerSpan("kafka.publish");
        span.attr("messaging.system", "kafka");
        span.attr("messaging.destination", topic);
        span.attr("messaging.operation", "publish");

        String traceId = span.traceId();

        return Result.of(() -> {
                    byte[] bytes = codec.encode(message).getOrThrow();
                    String key = keyExtractor.apply(message);

                    ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);

                    // Inject trace context into headers using Log API
                    injectTraceContext(span, record);

                    span.attr("messaging.kafka.message_key", key);
                    span.attr("serialization.codec", codec.name());

                    return record;
                })
                .fold(
                        error -> {
                            span.failure(error);
                            errorCount.incrementAndGet();
                            return CompletableFuture.completedFuture(Result.failure(error));
                        },
                        record -> {
                            CompletableFuture<Result<String>> future = new CompletableFuture<>();

                            producer.send(record, (metadata, exception) -> {
                                if (exception != null) {
                                    span.failure(exception);
                                    errorCount.incrementAndGet();
                                    future.complete(Result.failure(exception));
                                } else {
                                    span.attr("messaging.kafka.partition", metadata.partition());
                                    span.attr("messaging.kafka.offset", metadata.offset());
                                    span.success();
                                    publishedCount.incrementAndGet();
                                    future.complete(Result.success(traceId));
                                }
                            });

                            return future;
                        }
                );
    }

    /**
     * Fire-and-forget publish for maximum throughput.
     * Returns trace ID immediately without waiting for ack.
     */
    public String publishFireAndForget(A message) {
        // Skip tracing if not sampled (99.9% of requests)
        if (!Log.isSampled()) {
            return publishFireAndForgetNoTrace(message);
        }

        // Create producer span using Log API (no OTel types leaked)
        SpanHandle span = Log.producerSpan("kafka.publish.fast");
        span.attr("messaging.system", "kafka");
        span.attr("messaging.destination", topic);
        span.attr("messaging.operation", "publish");
        span.attr("messaging.kafka.acks", "0");

        String traceId = span.traceId();

        try {
            byte[] bytes = codec.encode(message).getOrThrow();
            String key = keyExtractor.apply(message);

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);
            injectTraceContext(span, record);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    error("Fire-and-forget publish failed: {}", exception.getMessage());
                    errorCount.incrementAndGet();
                }
            });

            publishedCount.incrementAndGet();
            span.success();

        } catch (Exception e) {
            span.failure(e);
            errorCount.incrementAndGet();
            throw new RuntimeException("Publish failed", e);
        }

        return traceId;
    }

    /**
     * Ultra-fast fire-and-forget without any tracing overhead.
     * No callback - truly fire-and-forget for maximum throughput.
     */
    private String publishFireAndForgetNoTrace(A message) {
        try {
            byte[] bytes = codec.encode(message).getOrThrow();
            String key = keyExtractor.apply(message);

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);

            // No callback - truly fire-and-forget (saves ~40% overhead)
            producer.send(record);

            publishedCount.incrementAndGet();
            return "";

        } catch (Exception e) {
            errorCount.incrementAndGet();
            throw new RuntimeException("Publish failed", e);
        }
    }

    /**
     * Publish an entire batch as a single Kafka message.
     * This is the key optimization: N items → 1 Kafka send instead of N sends.
     *
     * Format: [count:4 bytes][len1:4][data1][len2:4][data2]...
     *
     * Benefits:
     * - Reduces Kafka producer.send() calls from N to 1
     * - Reduces serialization overhead (single buffer allocation)
     * - With acks>0, waits for one ack instead of N
     * - Leverages Kafka's compression on larger payloads
     *
     * @param messages List of messages to batch
     * @return Number of messages sent
     */
    public int publishBatchFireAndForget(List<A> messages) {
        if (messages.isEmpty()) return 0;

        try {
            // Pre-size buffer based on expected message size
            int estimatedSize = messages.size() * 64 + 4;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(estimatedSize);
            DataOutputStream dos = new DataOutputStream(baos);

            // Write batch header: message count
            dos.writeInt(messages.size());

            // Write each message with length prefix
            for (A message : messages) {
                byte[] bytes = codec.encode(message).getOrThrow();
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }

            dos.flush();
            byte[] batchPayload = baos.toByteArray();

            // Send as single Kafka message
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                topic,
                "batch",  // Use fixed key for batch ordering
                batchPayload
            );

            // Add header to indicate this is a batch
            record.headers().add(new RecordHeader("batch-count",
                String.valueOf(messages.size()).getBytes(StandardCharsets.UTF_8)));

            producer.send(record);

            int count = messages.size();
            publishedCount.addAndGet(count);
            return count;

        } catch (IOException e) {
            errorCount.incrementAndGet();
            throw new RuntimeException("Batch publish failed", e);
        }
    }

    /**
     * Publish batch using raw byte arrays (no codec needed).
     * Even faster for pre-serialized data.
     */
    public int publishBatchRawFireAndForget(List<byte[]> messages) {
        if (messages.isEmpty()) return 0;

        try {
            // Calculate exact size needed
            int totalSize = 4; // count header
            for (byte[] msg : messages) {
                totalSize += 4 + msg.length; // length + data
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
            DataOutputStream dos = new DataOutputStream(baos);

            // Write batch header: message count
            dos.writeInt(messages.size());

            // Write each message with length prefix
            for (byte[] bytes : messages) {
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }

            dos.flush();
            byte[] batchPayload = baos.toByteArray();

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                topic,
                "batch",
                batchPayload
            );

            record.headers().add(new RecordHeader("batch-count",
                String.valueOf(messages.size()).getBytes(StandardCharsets.UTF_8)));

            producer.send(record);

            int count = messages.size();
            publishedCount.addAndGet(count);
            return count;

        } catch (IOException e) {
            errorCount.incrementAndGet();
            throw new RuntimeException("Batch publish failed", e);
        }
    }

    /**
     * Get published message count.
     */
    public long publishedCount() {
        return publishedCount.get();
    }

    /**
     * Get error count.
     */
    public long errorCount() {
        return errorCount.get();
    }

    @Override
    public void close() {
        producer.close();
    }

    // ========================================================================
    // Trace context injection
    // ========================================================================

    private void injectTraceContext(SpanHandle span, ProducerRecord<String, byte[]> record) {
        // Inject propagation headers from the span into Kafka record headers
        span.headers().forEach((key, value) -> {
            if (key != null && value != null) {
                record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
            }
        });
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static <A> Builder<A> builder() {
        return new Builder<>();
    }

    public static class Builder<A> {
        private String bootstrapServers;
        private String topic;
        private Codec<A> codec;
        private Function<A, String> keyExtractor = Object::toString;
        private String clientId = "platform-publisher";
        private int maxInFlightRequests = 5;
        private String acks = "1";

        public Builder<A> bootstrapServers(String servers) {
            this.bootstrapServers = servers;
            return this;
        }

        public Builder<A> topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder<A> codec(Codec<A> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<A> keyExtractor(Function<A, String> extractor) {
            this.keyExtractor = extractor;
            return this;
        }

        public Builder<A> clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder<A> maxInFlightRequests(int max) {
            this.maxInFlightRequests = max;
            return this;
        }

        public Builder<A> acks(String acks) {
            this.acks = acks;
            return this;
        }

        public Builder<A> fireAndForget() {
            this.acks = "0";
            this.maxInFlightRequests = 100;
            this.lingerMs = 0;         // No batching delay - send immediately
            this.batchSize = 65536;    // 64KB batches
            this.compression = "none"; // No compression overhead locally
            return this;
        }

        private int lingerMs = 0;
        private int batchSize = 16384;
        private String compression = "none";

        public Builder<A> lingerMs(int ms) {
            this.lingerMs = ms;
            return this;
        }

        public Builder<A> batchSize(int size) {
            this.batchSize = size;
            return this;
        }

        public Builder<A> compression(String type) {
            this.compression = type;
            return this;
        }

        private long bufferMemory = 16777216; // 16MB - reduced from 64MB to save memory

        public Builder<A> bufferMemory(long bytes) {
            this.bufferMemory = bytes;
            return this;
        }

        public KafkaPublisher<A> build() {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, acks);
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, maxInFlightRequests);
            props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression);
            props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);

            // Tracing is handled via Log API - no tracer injection needed
            KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);
            return new KafkaPublisher<>(producer, topic, codec, keyExtractor);
        }
    }
}
