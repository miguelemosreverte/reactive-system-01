package com.reactive.platform.kafka;

import com.reactive.platform.observe.Log;
import com.reactive.platform.serialization.Codec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * High-throughput Kafka publisher with dynamic batching.
 *
 * Implements Akka Streams-style groupedWithin(count, duration):
 * - Buffers messages in memory
 * - Flushes when buffer reaches maxBatchSize OR maxBatchDelayMs elapsed
 * - Returns immediately without waiting for Kafka
 *
 * This decouples HTTP response time from Kafka latency entirely.
 */
public class BatchingKafkaPublisher<A> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchingKafkaPublisher.class);

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final Codec<A> codec;
    private final Function<A, String> keyExtractor;

    // Batching configuration
    private final int maxBatchSize;
    private final long maxBatchDelayMs;

    // Internal buffer and scheduling
    private final BlockingQueue<A> buffer;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong batchCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    private volatile long firstMessageTime = 0;
    private volatile boolean running = true;

    private BatchingKafkaPublisher(
            KafkaProducer<String, byte[]> producer,
            String topic,
            Codec<A> codec,
            Function<A, String> keyExtractor,
            int maxBatchSize,
            long maxBatchDelayMs,
            int bufferCapacity
    ) {
        this.producer = producer;
        this.topic = topic;
        this.codec = codec;
        this.keyExtractor = keyExtractor;
        this.maxBatchSize = maxBatchSize;
        this.maxBatchDelayMs = maxBatchDelayMs;
        this.buffer = new LinkedBlockingQueue<>(bufferCapacity);

        // Background flush scheduler
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-batch-flusher");
            t.setDaemon(true);
            return t;
        });

        // Start the flush loop
        scheduler.scheduleAtFixedRate(this::checkAndFlush,
                maxBatchDelayMs / 2, maxBatchDelayMs / 2, TimeUnit.MILLISECONDS);

        log.info("BatchingKafkaPublisher started: maxBatchSize={}, maxBatchDelayMs={}, bufferCapacity={}",
                maxBatchSize, maxBatchDelayMs, bufferCapacity);
    }

    /**
     * Queue a message for batched publishing.
     * Returns immediately - does not wait for Kafka.
     */
    public void publish(A message) {
        if (!running) {
            throw new IllegalStateException("Publisher is closed");
        }

        // Record first message time for timeout-based flushing
        if (buffer.isEmpty()) {
            firstMessageTime = System.currentTimeMillis();
        }

        // Non-blocking add - if buffer full, trigger immediate flush
        if (!buffer.offer(message)) {
            // Buffer full - flush synchronously and retry
            flush();
            if (!buffer.offer(message)) {
                throw new IllegalStateException("Buffer still full after flush");
            }
        }

        // Check if we hit batch size limit
        if (buffer.size() >= maxBatchSize) {
            scheduler.execute(this::flush);
        }
    }

    /**
     * Check if time-based flush is needed.
     */
    private void checkAndFlush() {
        if (!running) return;

        if (!buffer.isEmpty() && firstMessageTime > 0) {
            long elapsed = System.currentTimeMillis() - firstMessageTime;
            if (elapsed >= maxBatchDelayMs) {
                flush();
            }
        }
    }

    /**
     * Flush all buffered messages to Kafka.
     */
    private synchronized void flush() {
        if (buffer.isEmpty()) return;

        List<A> batch = new ArrayList<>(Math.min(buffer.size(), maxBatchSize));
        buffer.drainTo(batch, maxBatchSize);

        if (batch.isEmpty()) return;

        firstMessageTime = 0; // Reset timer

        long startTime = System.currentTimeMillis();

        for (A message : batch) {
            try {
                byte[] bytes = codec.encode(message).getOrThrow();
                String key = keyExtractor.apply(message);

                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, bytes);

                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        errorCount.incrementAndGet();
                        Log.error("Batch publish failed: {}", exception.getMessage());
                    }
                });

                publishedCount.incrementAndGet();

            } catch (Exception e) {
                errorCount.incrementAndGet();
                Log.error("Failed to encode message: {}", e.getMessage());
            }
        }

        batchCount.incrementAndGet();

        if (log.isDebugEnabled()) {
            log.debug("Flushed batch: size={}, latency={}ms, total={}",
                    batch.size(), System.currentTimeMillis() - startTime, publishedCount.get());
        }
    }

    public long publishedCount() { return publishedCount.get(); }
    public long batchCount() { return batchCount.get(); }
    public long errorCount() { return errorCount.get(); }
    public int bufferSize() { return buffer.size(); }

    @Override
    public void close() {
        running = false;
        flush(); // Final flush
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.close();
        log.info("BatchingKafkaPublisher closed: published={}, batches={}, errors={}",
                publishedCount.get(), batchCount.get(), errorCount.get());
    }

    // ========================================================================
    // Builder
    // ========================================================================

    public static <A> Builder<A> builder() {
        return new Builder<>();
    }

    public static <A> BatchingKafkaPublisher<A> create(java.util.function.Consumer<Builder<A>> configure) {
        Builder<A> builder = new Builder<>();
        configure.accept(builder);
        return builder.build();
    }

    public static class Builder<A> {
        private String bootstrapServers;
        private String topic;
        private Codec<A> codec;
        private Function<A, String> keyExtractor = Object::toString;
        private String clientId = "batching-publisher";

        // Batching parameters (like Akka's groupedWithin)
        private int maxBatchSize = 1000;        // Flush every N messages
        private long maxBatchDelayMs = 100;     // OR every N milliseconds
        private int bufferCapacity = 10_000;    // Max buffered messages (reduced for memory)

        // Kafka producer settings
        private int lingerMs = 5;               // Kafka's own batching
        private int batchSize = 131072;         // 128KB Kafka batches
        private long bufferMemory = 33554432;   // 32MB

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

        /**
         * Akka-style groupedWithin: flush when count OR duration is reached.
         */
        public Builder<A> groupedWithin(int maxCount, long maxDelayMs) {
            this.maxBatchSize = maxCount;
            this.maxBatchDelayMs = maxDelayMs;
            return this;
        }

        public Builder<A> bufferCapacity(int capacity) {
            this.bufferCapacity = capacity;
            return this;
        }

        public BatchingKafkaPublisher<A> build() {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
            props.put(ProducerConfig.ACKS_CONFIG, "0");  // Fire-and-forget
            props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
            props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 100);

            KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);

            return new BatchingKafkaPublisher<>(
                    producer, topic, codec, keyExtractor,
                    maxBatchSize, maxBatchDelayMs, bufferCapacity
            );
        }
    }
}
