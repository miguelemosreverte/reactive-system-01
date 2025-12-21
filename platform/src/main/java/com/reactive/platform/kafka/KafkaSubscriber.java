package com.reactive.platform.kafka;

import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.Result;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Functional Kafka subscriber with OpenTelemetry tracing.
 *
 * @param <A> The message type
 */
public class KafkaSubscriber<A> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaSubscriber.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final String topic;
    private final Codec<A> codec;
    private final Tracer tracer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Metrics
    private final AtomicLong consumedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private KafkaSubscriber(
            KafkaConsumer<String, byte[]> consumer,
            String topic,
            Codec<A> codec,
            Tracer tracer
    ) {
        this.consumer = consumer;
        this.topic = topic;
        this.codec = codec;
        this.tracer = tracer;
    }

    /**
     * Start consuming messages.
     *
     * @param handler Function to handle each decoded message
     */
    public void subscribe(Consumer<A> handler) {
        subscribe(handler, error -> log.error("Message processing failed", error));
    }

    /**
     * Start consuming messages with error handler.
     */
    public void subscribe(Consumer<A> handler, Consumer<Throwable> errorHandler) {
        if (running.compareAndSet(false, true)) {
            consumer.subscribe(Collections.singletonList(topic));

            executor.submit(() -> {
                log.info("Started consuming from topic: {}", topic);

                while (running.get()) {
                    try {
                        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));

                        for (ConsumerRecord<String, byte[]> record : records) {
                            processRecord(record, handler, errorHandler);
                        }
                    } catch (Exception e) {
                        if (running.get()) {
                            log.error("Error polling Kafka", e);
                            errorCount.incrementAndGet();
                        }
                    }
                }

                log.info("Stopped consuming from topic: {}", topic);
            });
        }
    }

    private void processRecord(
            ConsumerRecord<String, byte[]> record,
            Consumer<A> handler,
            Consumer<Throwable> errorHandler
    ) {
        // Extract trace context from headers
        Context parentContext = extractTraceContext(record);

        Span span = tracer.spanBuilder("kafka.consume")
                .setParent(parentContext)
                .setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination", topic)
                .setAttribute("messaging.operation", "receive")
                .setAttribute("messaging.kafka.partition", record.partition())
                .setAttribute("messaging.kafka.offset", record.offset())
                .startSpan();

        try {
            Result<A> decoded = codec.decode(record.value());

            decoded.fold(
                    error -> {
                        span.setStatus(StatusCode.ERROR, "Decode failed: " + error.getMessage());
                        span.recordException(error);
                        errorHandler.accept(error);
                        errorCount.incrementAndGet();
                        return null;
                    },
                    message -> {
                        handler.accept(message);
                        consumedCount.incrementAndGet();
                        span.setStatus(StatusCode.OK);
                        return null;
                    }
            );

        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            errorHandler.accept(e);
            errorCount.incrementAndGet();
        } finally {
            span.end();
        }
    }

    /**
     * Stop consuming.
     */
    public void stop() {
        running.set(false);
    }

    /**
     * Check if running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get consumed message count.
     */
    public long consumedCount() {
        return consumedCount.get();
    }

    /**
     * Get error count.
     */
    public long errorCount() {
        return errorCount.get();
    }

    @Override
    public void close() {
        stop();
        executor.shutdown();
        consumer.close();
    }

    // ========================================================================
    // Trace context extraction
    // ========================================================================

    private Context extractTraceContext(ConsumerRecord<String, byte[]> record) {
        TextMapGetter<ConsumerRecord<String, byte[]>> getter = new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(ConsumerRecord<String, byte[]> carrier) {
                return () -> {
                    java.util.Iterator<Header> it = carrier.headers().iterator();
                    return new java.util.Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public String next() {
                            return it.next().key();
                        }
                    };
                };
            }

            @Override
            public String get(ConsumerRecord<String, byte[]> carrier, String key) {
                Header header = carrier.headers().lastHeader(key);
                return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
            }
        };

        return io.opentelemetry.api.GlobalOpenTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), record, getter);
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
        private String groupId;
        private Codec<A> codec;
        private Tracer tracer;
        private String clientId = "platform-subscriber";
        private boolean autoCommit = true;
        private int maxPollRecords = 500;

        public Builder<A> bootstrapServers(String servers) {
            this.bootstrapServers = servers;
            return this;
        }

        public Builder<A> topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder<A> groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder<A> codec(Codec<A> codec) {
            this.codec = codec;
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

        public Builder<A> autoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
            return this;
        }

        public Builder<A> maxPollRecords(int max) {
            this.maxPollRecords = max;
            return this;
        }

        public KafkaSubscriber<A> build() {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
            props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 10);
            props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);

            KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
            return new KafkaSubscriber<>(consumer, topic, codec, tracer);
        }
    }
}
