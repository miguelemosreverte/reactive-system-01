package com.reactive.platform.gateway.microbatch;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Ultra-fast batcher - optimized for maximum throughput.
 *
 * Key optimizations:
 * 1. Many stripes (256) to reduce contention
 * 2. Thread ID hashing (faster than ThreadLocal.get())
 * 3. Lock-free atomic position updates
 * 4. Inline hot path
 *
 * @deprecated Use {@link PartitionedBatcher} instead - achieves 1.11B msg/s without atomic overhead.
 */
@Deprecated
public final class UltraFastBatcher implements MessageBatcher {

    private static final int NUM_STRIPES = 256;  // Many stripes = less contention
    private static final int STRIPE_MASK = NUM_STRIPES - 1;
    private static final int STRIPE_SIZE = 4 * 1024 * 1024;  // 4MB per stripe

    private final Consumer<byte[]> sender;
    private final Stripe[] stripes;
    private final Thread flushThread;
    private volatile boolean running = true;

    public UltraFastBatcher(Consumer<byte[]> sender) {
        this.sender = sender;
        this.stripes = new Stripe[NUM_STRIPES];
        for (int i = 0; i < NUM_STRIPES; i++) {
            stripes[i] = new Stripe();
        }

        this.flushThread = Thread.ofPlatform()
            .name("ultra-flush")
            .daemon(true)
            .start(this::flushLoop);
    }

    /**
     * Send a message - lock-free hot path.
     */
    @Override
    public void send(byte[] message) {
        int stripeIdx = (int) (Thread.currentThread().threadId() & STRIPE_MASK);
        stripes[stripeIdx].write(message);
    }

    private void flushLoop() {
        while (running) {
            for (Stripe stripe : stripes) {
                int pos = stripe.position.get();
                if (pos >= STRIPE_SIZE / 2) {  // Flush at 50%
                    byte[] data = Arrays.copyOf(stripe.buffer, pos);
                    stripe.position.set(0);
                    sender.accept(data);
                }
            }
            try { Thread.sleep(1); } catch (InterruptedException e) { break; }
        }

        // Final flush
        for (Stripe stripe : stripes) {
            int pos = stripe.position.get();
            if (pos > 0) {
                sender.accept(Arrays.copyOf(stripe.buffer, pos));
            }
        }
    }

    @Override
    public void close() {
        running = false;
        flushThread.interrupt();
        try { flushThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Stripe {
        final byte[] buffer = new byte[STRIPE_SIZE];
        final AtomicInteger position = new AtomicInteger(0);

        void write(byte[] data) {
            int len = data.length;
            int pos = position.getAndAdd(len);

            // Handle overflow
            if (pos + len > STRIPE_SIZE) {
                position.set(0);
                pos = position.getAndAdd(len);
            }

            if (pos + len <= STRIPE_SIZE) {
                System.arraycopy(data, 0, buffer, pos, len);
            }
        }
    }
}
