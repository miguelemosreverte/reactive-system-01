package com.reactive.platform.benchmark;

import com.reactive.platform.http.*;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Latency Diagnostics - Measure where time is spent in each phase.
 *
 * Phases measured:
 * 1. Connection establishment
 * 2. Request write (client → kernel)
 * 3. Network transit (kernel → server)
 * 4. Server processing
 * 5. Response transit (server → client kernel)
 * 6. Response read (kernel → client)
 *
 * This helps identify the actual bottleneck.
 */
public final class LatencyDiagnostics {

    private static final String POST_BODY = "{\"e\":1}";
    private static final byte[] REQUEST = buildRequest();

    private static byte[] buildRequest() {
        return ("POST /e HTTP/1.1\r\n" +
                "Host: l\r\n" +
                "Content-Length: " + POST_BODY.length() + "\r\n" +
                "\r\n" +
                POST_BODY).getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("  Latency Diagnostics - Where is time being spent?");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Start server
        RocketHttpServer server = RocketHttpServer.create()
            .reactors(4)
            .onBody(buf -> {});
        RocketHttpServer.Handle handle = server.start(9999);
        Thread.sleep(200);

        try {
            // 1. Measure connection establishment time
            System.out.println("1. CONNECTION ESTABLISHMENT");
            measureConnectionTime(9999, 100);

            // 2. Measure single request latency breakdown
            System.out.println();
            System.out.println("2. SINGLE REQUEST LATENCY BREAKDOWN");
            measureSingleRequestLatency(9999, 1000);

            // 3. Measure throughput at different concurrency levels
            System.out.println();
            System.out.println("3. THROUGHPUT vs CONCURRENCY");
            measureThroughputVsConcurrency(9999);

            // 4. Measure server processing time (isolated)
            System.out.println();
            System.out.println("4. SERVER PROCESSING TIME (isolated)");
            measureServerProcessingTime(handle);

            // 5. Measure client-side overhead
            System.out.println();
            System.out.println("5. CLIENT-SIDE OVERHEAD ANALYSIS");
            measureClientOverhead(9999);

            // 6. Measure kernel buffer effects
            System.out.println();
            System.out.println("6. BUFFER SIZE EFFECTS");
            measureBufferEffects(9999);

            // Summary
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");
            System.out.println("  SUMMARY & RECOMMENDATIONS");
            System.out.println("═══════════════════════════════════════════════════════════════════════════════");

        } finally {
            handle.close();
        }
    }

    /**
     * Measure time to establish TCP connections.
     */
    private static void measureConnectionTime(int port, int count) throws Exception {
        long[] times = new long[count];

        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            Socket socket = new Socket("localhost", port);
            times[i] = System.nanoTime() - start;
            socket.close();
        }

        Arrays.sort(times);
        double avg = Arrays.stream(times).average().orElse(0) / 1000.0;
        double p50 = times[count / 2] / 1000.0;
        double p99 = times[(int)(count * 0.99)] / 1000.0;
        double min = times[0] / 1000.0;
        double max = times[count - 1] / 1000.0;

        System.out.printf("   Connections measured: %d%n", count);
        System.out.printf("   Min:  %,.1f µs%n", min);
        System.out.printf("   Avg:  %,.1f µs%n", avg);
        System.out.printf("   P50:  %,.1f µs%n", p50);
        System.out.printf("   P99:  %,.1f µs%n", p99);
        System.out.printf("   Max:  %,.1f µs%n", max);
    }

    /**
     * Measure latency breakdown for single requests on persistent connection.
     */
    private static void measureSingleRequestLatency(int port, int count) throws Exception {
        long[] writeTime = new long[count];
        long[] readTime = new long[count];
        long[] totalTime = new long[count];

        byte[] responseBuffer = new byte[256];

        try (Socket socket = new Socket("localhost", port)) {
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Warmup
            for (int i = 0; i < 100; i++) {
                out.write(REQUEST);
                out.flush();
                in.read(responseBuffer);
            }

            // Measure
            for (int i = 0; i < count; i++) {
                long t0 = System.nanoTime();

                out.write(REQUEST);
                out.flush();
                long t1 = System.nanoTime();

                in.read(responseBuffer);
                long t2 = System.nanoTime();

                writeTime[i] = t1 - t0;
                readTime[i] = t2 - t1;
                totalTime[i] = t2 - t0;
            }
        }

        Arrays.sort(writeTime);
        Arrays.sort(readTime);
        Arrays.sort(totalTime);

        System.out.printf("   Requests measured: %d (on persistent connection)%n", count);
        System.out.println();
        System.out.println("   Phase          Min (µs)    Avg (µs)    P50 (µs)    P99 (µs)    Max (µs)");
        System.out.println("   ─────────────────────────────────────────────────────────────────────────");

        printStats("Write", writeTime);
        printStats("Read", readTime);
        printStats("Total", totalTime);

        double avgTotal = Arrays.stream(totalTime).average().orElse(0) / 1000.0;
        double theoreticalMax = 1_000_000.0 / avgTotal;
        System.out.println();
        System.out.printf("   → At %.1f µs avg latency, max single-connection throughput: %,.0f req/s%n",
            avgTotal, theoreticalMax);
    }

    private static void printStats(String name, long[] times) {
        int n = times.length;
        double min = times[0] / 1000.0;
        double avg = Arrays.stream(times).average().orElse(0) / 1000.0;
        double p50 = times[n / 2] / 1000.0;
        double p99 = times[(int)(n * 0.99)] / 1000.0;
        double max = times[n - 1] / 1000.0;

        System.out.printf("   %-12s %10.1f %11.1f %11.1f %11.1f %11.1f%n",
            name, min, avg, p50, p99, max);
    }

    /**
     * Measure how throughput scales with concurrency.
     */
    private static void measureThroughputVsConcurrency(int port) throws Exception {
        int[] concurrencyLevels = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};

        System.out.println("   Concurrency    Throughput    Per-Conn     Efficiency");
        System.out.println("   ──────────────────────────────────────────────────────");

        double baselinePerConn = 0;

        for (int concurrency : concurrencyLevels) {
            double throughput = runQuickBenchmark(port, concurrency, 3);
            double perConn = throughput / concurrency;

            if (concurrency == 1) {
                baselinePerConn = perConn;
            }

            double efficiency = (perConn / baselinePerConn) * 100;

            System.out.printf("   %,10d %,13.0f %,11.0f %10.1f%%%n",
                concurrency, throughput, perConn, efficiency);
        }
    }

    private static double runQuickBenchmark(int port, int concurrency, int durationSeconds) throws Exception {
        LongAdder count = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                byte[] buf = new byte[256];
                try {
                    start.await();
                    try (Socket s = new Socket("localhost", port)) {
                        s.setTcpNoDelay(true);
                        OutputStream out = s.getOutputStream();
                        InputStream in = s.getInputStream();

                        while (System.currentTimeMillis() < endTime) {
                            out.write(REQUEST);
                            out.flush();
                            if (in.read(buf) > 0) {
                                count.increment();
                            }
                        }
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

        return count.sum() * 1.0 / durationSeconds;
    }

    /**
     * Measure server processing time by looking at server-side metrics.
     */
    private static void measureServerProcessingTime(RocketHttpServer.Handle handle) {
        long startCount = handle.requestCount();

        // Run a controlled burst
        int burstSize = 100_000;
        long startTime = System.nanoTime();

        try (Socket socket = new Socket("localhost", 9999)) {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(65536);
            socket.setReceiveBufferSize(65536);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[256];

            for (int i = 0; i < burstSize; i++) {
                out.write(REQUEST);
                out.flush();
                in.read(buf);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        long elapsed = System.nanoTime() - startTime;
        long endCount = handle.requestCount();
        long processed = endCount - startCount;

        double avgTimePerRequest = (elapsed / 1000.0) / processed;
        double serverProcessingRate = processed * 1_000_000_000.0 / elapsed;

        System.out.printf("   Requests sent: %,d%n", burstSize);
        System.out.printf("   Server processed: %,d%n", processed);
        System.out.printf("   Total time: %.2f ms%n", elapsed / 1_000_000.0);
        System.out.printf("   Avg per request: %.2f µs%n", avgTimePerRequest);
        System.out.printf("   Server rate: %,.0f req/s%n", serverProcessingRate);
    }

    /**
     * Measure client-side overhead (syscalls, buffer copies, etc.)
     */
    private static void measureClientOverhead(int port) throws Exception {
        System.out.println("   Testing different client implementations...");
        System.out.println();

        // Blocking Socket
        double blockingThroughput = runQuickBenchmark(port, 4, 3);

        // NIO with selector
        double nioThroughput = runNioBenchmark(port, 4, 25, 3);

        System.out.printf("   Blocking I/O (4 threads):     %,.0f req/s%n", blockingThroughput);
        System.out.printf("   NIO Selector (4 threads):     %,.0f req/s%n", nioThroughput);
        System.out.printf("   NIO advantage: %.1f%%%n", ((nioThroughput / blockingThroughput) - 1) * 100);
    }

    private static double runNioBenchmark(int port, int threads, int connsPerThread, int durationSeconds) throws Exception {
        LongAdder count = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        long endTimeMs = System.currentTimeMillis() + durationSeconds * 1000L;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    start.await();
                    Selector selector = Selector.open();
                    InetSocketAddress addr = new InetSocketAddress("localhost", port);

                    ByteBuffer[] wbufs = new ByteBuffer[connsPerThread];
                    ByteBuffer[] rbufs = new ByteBuffer[connsPerThread];

                    for (int i = 0; i < connsPerThread; i++) {
                        wbufs[i] = ByteBuffer.allocateDirect(REQUEST.length);
                        rbufs[i] = ByteBuffer.allocateDirect(256);

                        SocketChannel ch = SocketChannel.open();
                        ch.configureBlocking(false);
                        ch.connect(addr);
                        SelectionKey key = ch.register(selector, SelectionKey.OP_CONNECT);
                        key.attach(i);
                    }

                    while (System.currentTimeMillis() < endTimeMs) {
                        if (selector.select(1) == 0) continue;

                        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                        while (keys.hasNext()) {
                            SelectionKey key = keys.next();
                            keys.remove();
                            if (!key.isValid()) continue;

                            int idx = (Integer) key.attachment();
                            SocketChannel ch = (SocketChannel) key.channel();

                            try {
                                if (key.isConnectable() && ch.finishConnect()) {
                                    ch.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                                    key.interestOps(SelectionKey.OP_WRITE);
                                } else if (key.isWritable()) {
                                    ByteBuffer wb = wbufs[idx];
                                    wb.clear();
                                    wb.put(REQUEST);
                                    wb.flip();
                                    while (wb.hasRemaining()) ch.write(wb);
                                    key.interestOps(SelectionKey.OP_READ);
                                } else if (key.isReadable()) {
                                    ByteBuffer rb = rbufs[idx];
                                    rb.clear();
                                    if (ch.read(rb) > 0) {
                                        count.increment();
                                        key.interestOps(SelectionKey.OP_WRITE);
                                    }
                                }
                            } catch (IOException e) {
                                key.cancel();
                            }
                        }
                    }

                    selector.close();
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

        return count.sum() * 1.0 / durationSeconds;
    }

    /**
     * Measure effect of buffer sizes on throughput.
     */
    private static void measureBufferEffects(int port) throws Exception {
        int[] bufferSizes = {1024, 4096, 16384, 65536, 262144};

        System.out.println("   Buffer Size     Throughput");
        System.out.println("   ───────────────────────────");

        for (int bufSize : bufferSizes) {
            double throughput = runBufferedBenchmark(port, bufSize, 3);
            System.out.printf("   %,10d B %,12.0f req/s%n", bufSize, throughput);
        }
    }

    private static double runBufferedBenchmark(int port, int bufferSize, int durationSeconds) throws Exception {
        LongAdder count = new LongAdder();
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        try (Socket socket = new Socket("localhost", port)) {
            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(bufferSize);
            socket.setReceiveBufferSize(bufferSize);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[256];

            while (System.currentTimeMillis() < endTime) {
                out.write(REQUEST);
                out.flush();
                if (in.read(buf) > 0) {
                    count.increment();
                }
            }
        }

        return count.sum() * 1.0 / durationSeconds;
    }
}
