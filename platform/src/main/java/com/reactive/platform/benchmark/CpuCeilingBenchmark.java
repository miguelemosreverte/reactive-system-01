package com.reactive.platform.benchmark;

import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * CPU Ceiling Benchmark - Find the raw CPU throughput ceiling.
 *
 * This measures:
 * 1. Raw counter increment speed (baseline)
 * 2. ByteBuffer operations (simulated request processing)
 * 3. LongAdder contention across threads
 *
 * This tells us the theoretical maximum throughput if I/O were free.
 */
public final class CpuCeilingBenchmark {

    private static final byte[] REQUEST = "POST /e HTTP/1.1\r\nHost: l\r\nContent-Length: 7\r\n\r\n{\"e\":1}".getBytes();
    private static final byte[] RESPONSE = "HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\n\r\nok".getBytes();

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  CPU Ceiling Benchmark - Finding Theoretical Maximum");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("  CPU Cores: %d%n", cores);
        System.out.println();

        // Test 1: Raw counter speed
        System.out.println("TEST 1: Raw Counter Speed (single thread)");
        long rawSpeed = measureRawCounter(1, 3);
        System.out.printf("  Single thread: %,d increments/sec%n", rawSpeed);

        // Test 2: LongAdder across multiple threads
        System.out.println();
        System.out.println("TEST 2: LongAdder Contention");
        System.out.println("  Threads    Throughput        Per-Thread    Scaling");
        System.out.println("  ──────────────────────────────────────────────────────");

        for (int threads : new int[]{1, 2, 4, 8}) {
            long throughput = measureLongAdder(threads, 3);
            double perThread = throughput / (double) threads;
            double scaling = throughput / (double) rawSpeed;
            System.out.printf("  %7d %,14d %,15.0f %10.2fx%n",
                threads, throughput, perThread, scaling);
        }

        // Test 3: ByteBuffer processing speed
        System.out.println();
        System.out.println("TEST 3: ByteBuffer Processing (simulated request)");
        System.out.println("  Threads    Throughput        Per-Thread");
        System.out.println("  ─────────────────────────────────────────");

        for (int threads : new int[]{1, 2, 4}) {
            long throughput = measureByteBufferProcessing(threads, 3);
            double perThread = throughput / (double) threads;
            System.out.printf("  %7d %,14d %,15.0f%n", threads, throughput, perThread);
        }

        // Test 4: Full request simulation (no I/O)
        System.out.println();
        System.out.println("TEST 4: Full Request Simulation (no I/O)");
        System.out.println("  Threads    Throughput        Per-Thread");
        System.out.println("  ─────────────────────────────────────────");

        for (int threads : new int[]{1, 2, 4}) {
            long throughput = measureFullRequestSimulation(threads, 3);
            double perThread = throughput / (double) threads;
            System.out.printf("  %7d %,14d %,15.0f%n", threads, throughput, perThread);
        }

        // Test 5: Compare with actual I/O benchmark
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  ANALYSIS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        long cpuCeiling = measureFullRequestSimulation(4, 5);
        System.out.printf("  CPU ceiling (no I/O): %,d req/s%n", cpuCeiling);
        System.out.println();
        System.out.println("  If actual throughput << CPU ceiling → I/O is bottleneck");
        System.out.println("  If actual throughput ≈ CPU ceiling → CPU is bottleneck");
        System.out.println();

        // Calculate I/O overhead
        double actualThroughput = 580_000; // From previous benchmarks
        double ioOverhead = 1 - (actualThroughput / cpuCeiling);
        System.out.printf("  Actual throughput (from benchmarks): %,.0f req/s%n", actualThroughput);
        System.out.printf("  I/O overhead: %.1f%%%n", ioOverhead * 100);
        System.out.printf("  CPU utilization: %.1f%%%n", (actualThroughput / cpuCeiling) * 100);
    }

    private static long measureRawCounter(int threads, int seconds) throws Exception {
        LongAdder counter = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long endTime = System.currentTimeMillis() + seconds * 1000L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    while (System.currentTimeMillis() < endTime) {
                        counter.increment();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();

        return counter.sum() / seconds;
    }

    private static long measureLongAdder(int threads, int seconds) throws Exception {
        LongAdder counter = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long endTime = System.currentTimeMillis() + seconds * 1000L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    while (System.currentTimeMillis() < endTime) {
                        counter.increment();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();

        return counter.sum() / seconds;
    }

    private static long measureByteBufferProcessing(int threads, int seconds) throws Exception {
        LongAdder counter = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long endTime = System.currentTimeMillis() + seconds * 1000L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ByteBuffer readBuf = ByteBuffer.allocateDirect(4096);
                ByteBuffer writeBuf = ByteBuffer.allocateDirect(256);

                try {
                    start.await();
                    while (System.currentTimeMillis() < endTime) {
                        // Simulate read
                        readBuf.clear();
                        readBuf.put(REQUEST);
                        readBuf.flip();

                        // Simulate write
                        writeBuf.clear();
                        writeBuf.put(RESPONSE);
                        writeBuf.flip();

                        counter.increment();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();

        return counter.sum() / seconds;
    }

    private static long measureFullRequestSimulation(int threads, int seconds) throws Exception {
        LongAdder counter = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long endTime = System.currentTimeMillis() + seconds * 1000L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ByteBuffer readBuf = ByteBuffer.allocateDirect(4096);
                ByteBuffer writeBuf = ByteBuffer.allocateDirect(256);

                try {
                    start.await();
                    while (System.currentTimeMillis() < endTime) {
                        // Simulate full request processing

                        // 1. Read into buffer
                        readBuf.clear();
                        readBuf.put(REQUEST);
                        readBuf.flip();

                        // 2. Parse (find header end)
                        int limit = readBuf.limit();
                        for (int j = 0; j < limit - 3; j++) {
                            if (readBuf.get(j) == '\r' && readBuf.get(j + 1) == '\n' &&
                                readBuf.get(j + 2) == '\r' && readBuf.get(j + 3) == '\n') {
                                break;
                            }
                        }

                        // 3. Write response
                        writeBuf.clear();
                        writeBuf.put(RESPONSE);
                        writeBuf.flip();

                        counter.increment();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        executor.shutdown();

        return counter.sum() / seconds;
    }
}
