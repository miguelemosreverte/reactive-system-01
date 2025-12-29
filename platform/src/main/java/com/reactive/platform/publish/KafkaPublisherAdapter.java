package com.reactive.platform.publish;

import com.reactive.platform.kafka.KafkaPublisher;
import com.reactive.platform.base.Result;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter that wraps KafkaPublisher to implement the Publisher interface.
 *
 * This class is package-private - external code uses only Publisher interface.
 *
 * @param <A> The message type
 */
class KafkaPublisherAdapter<A> implements Publisher<A> {

    private final KafkaPublisher<A> delegate;

    KafkaPublisherAdapter(KafkaPublisher<A> delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<Result<String>> publish(A message) {
        return delegate.publish(message);
    }

    @Override
    public String publishFireAndForget(A message) {
        return delegate.publishFireAndForget(message);
    }

    @Override
    public int publishBatch(List<A> messages) {
        return delegate.publishBatchFireAndForget(messages);
    }

    @Override
    public int publishBatchRaw(List<byte[]> messages) {
        return delegate.publishBatchRawFireAndForget(messages);
    }

    @Override
    public long publishedCount() {
        return delegate.publishedCount();
    }

    @Override
    public long errorCount() {
        return delegate.errorCount();
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
