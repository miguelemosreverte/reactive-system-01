package com.reactive.platform.publish;

import com.reactive.platform.serialization.Result;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * No-op publisher for testing.
 *
 * Accepts all messages but does nothing with them.
 * Useful for unit tests and dry-run scenarios.
 *
 * @param <A> The message type
 */
class NoopPublisher<A> implements Publisher<A> {

    private final AtomicLong published = new AtomicLong(0);

    @Override
    public CompletableFuture<Result<String>> publish(A message) {
        published.incrementAndGet();
        return CompletableFuture.completedFuture(Result.success("noop-trace-id"));
    }

    @Override
    public String publishFireAndForget(A message) {
        published.incrementAndGet();
        return "noop-trace-id";
    }

    @Override
    public int publishBatch(List<A> messages) {
        int count = messages.size();
        published.addAndGet(count);
        return count;
    }

    @Override
    public int publishBatchRaw(List<byte[]> messages) {
        int count = messages.size();
        published.addAndGet(count);
        return count;
    }

    @Override
    public long publishedCount() {
        return published.get();
    }

    @Override
    public long errorCount() {
        return 0;
    }

    @Override
    public void close() {
        // No-op
    }
}
