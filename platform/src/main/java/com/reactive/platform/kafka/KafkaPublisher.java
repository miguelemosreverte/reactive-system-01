package com.reactive.platform.kafka;

import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.Result;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    // ========================================================================
    // Static factories (Scala-style)
    // ========================================================================

    /** Create publisher with default settings. */
    public static <A> KafkaPublisher<A> create(
            String bootstrapServers,
            String topic,
            Codec<A> codec,
            Function<A, String> keyExtractor,
            Tracer tracer
    ) {
        return KafkaPublisher.<A>builder()
                .bootstrapServers(bootstrapServers)
                .topic(topic)
                .codec(codec)
                .keyExtractor(keyExtractor)
                .tracer(tracer)
                .build();
    }

    /** Create fire-and-forget publisher (no acks). */
    public static <A> KafkaPublisher<A> fireAndForget(
            String bootstrapServers,
            String topic,
            Codec<A> codec,
            Function<A, String> keyExtractor,
            Tracer tracer
    ) {
        return KafkaPublisher.<A>builder()
                .bootstrapServers(bootstrapServers)
                .topic(topic)
                .codec(codec)
                .keyExtractor(keyExtractor)
                .tracer(tracer)
                .fireAndForget()
                .build();
    }

    // ========================================================================
    // Internal state
    // ========================================================================

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final Codec<A> codec;
    private final Function<A, String> keyExtractor;
    private final Tracer tracer;
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private KafkaPublisher(
            KafkaProducer<String, byte[]> producer,
            String topic,
            Codec<A> codec,
            Function<A, String> keyExtractor,
            Tracer tracer
    ) {
        this.producer = producer;
        this.topic = topic;
        this.codec = codec;
        this.keyExtractor = keyExtractor;
        this.tracer = tracer;
    }

    /**
     * Publish a message with full tracing.
     * Returns the trace ID on success.
     */
    public CompletableFuture<Result<String>> publish(A message) {
        Span span = tracer.spanBuilder("kafka.publish")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", topic)
                .setAttribute("messaging.operation", "publish")
                .startSpan();

        String traceId = span.getSpanContext().getTraceId();

        return Result.of(() -> {
                    byte[] bytes = codec.encode(message).getOrThrow();
                    String key = keyExtractor.apply(message);

                    ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);

                    // Inject trace context into headers
                    Context context = Context.current().with(span);
                    injectTraceContext(context, record);

                    span.setAttribute("messaging.kafka.message_key", key);
                    span.setAttribute("serialization.codec", codec.name());

                    return record;
                })
                .fold(
                        error -> {
                            span.setStatus(StatusCode.ERROR, error.getMessage());
                            span.recordException(error);
                            span.end();
                            errorCount.incrementAndGet();
                            return CompletableFuture.completedFuture(Result.failure(error));
                        },
                        record -> {
                            CompletableFuture<Result<String>> future = new CompletableFuture<>();

                            producer.send(record, (metadata, exception) -> {
                                if (exception != null) {
                                    span.setStatus(StatusCode.ERROR, exception.getMessage());
                                    span.recordException(exception);
                                    span.end();
                                    errorCount.incrementAndGet();
                                    future.complete(Result.failure(exception));
                                } else {
                                    span.setAttribute("messaging.kafka.partition", metadata.partition());
                                    span.setAttribute("messaging.kafka.offset", metadata.offset());
                                    span.setStatus(StatusCode.OK);
                                    span.end();
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
        Span span = tracer.spanBuilder("kafka.publish.fast")
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", topic)
                .setAttribute("messaging.operation", "publish")
                .setAttribute("messaging.kafka.acks", "0")
                .startSpan();

        String traceId = span.getSpanContext().getTraceId();

        try {
            byte[] bytes = codec.encode(message).getOrThrow();
            String key = keyExtractor.apply(message);

            ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);
            Context context = Context.current().with(span);
            injectTraceContext(context, record);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Fire-and-forget publish failed: {}", exception.getMessage());
                    errorCount.incrementAndGet();
                }
            });

            publishedCount.incrementAndGet();
            span.setStatus(StatusCode.OK);

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            errorCount.incrementAndGet();
            throw new RuntimeException("Publish failed", e);
        } finally {
            span.end();
        }

        return traceId;
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

    private void injectTraceContext(Context context, ProducerRecord<String, byte[]> record) {
        TextMapSetter<ProducerRecord<String, byte[]>> setter = (carrier, key, value) -> {
            if (carrier != null && key != null && value != null) {
                carrier.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
            }
        };

        io.opentelemetry.api.GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .inject(context, record, setter);
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
        private Tracer tracer;
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

        public Builder<A> tracer(Tracer tracer) {
            this.tracer = tracer;
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
            this.maxInFlightRequests = 20;
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
            props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);

            KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);
            return new KafkaPublisher<>(producer, topic, codec, keyExtractor, tracer);
        }
    }
}
